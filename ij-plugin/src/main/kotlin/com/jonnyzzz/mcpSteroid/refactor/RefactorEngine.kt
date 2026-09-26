/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.refactor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.QuickFix
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.PairProcessor
import com.jonnyzzz.mcpSteroid.server.RefactorOp
import com.jonnyzzz.mcpSteroid.server.RefactorParams
import com.jonnyzzz.mcpSteroid.ui.CodeLocation
import com.jonnyzzz.mcpSteroid.ui.UiStepFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A refactoring that could not run or did not finish. The message says why, and which files it changed, if any. */
class RefactorFailure(message: String) : RuntimeException(message)

/**
 * Runs steroid_refactor operations. Targets resolve in a smart read action. Changes run on the EDT the way the IDE
 * runs them: a refactoring processor under write intent and never inside a write action of ours, a quick fix or a
 * file edit in one write command. Conflicts are found before anything changes, so no dialog opens in the user's
 * IDE; [RefactorApplier] reads and cancels one that still does.
 */
class RefactorEngine(private val project: Project) {
    private val applier = RefactorApplier(project)

    suspend fun run(params: RefactorParams): String = when (params.op) {
        RefactorOp.USAGES -> usages(named(target(params)))
        RefactorOp.RENAME -> rename(params)
        RefactorOp.SAFE_DELETE -> safeDelete(params)
        RefactorOp.MOVE -> move(params)
        RefactorOp.FIX -> fix(params)
        RefactorOp.INTENTION -> intention(params)
        RefactorOp.OPTIMIZE_IMPORTS -> optimizeImports(params)
        RefactorOp.REFORMAT -> fileEdit(params, "Reformat") { CodeStyleManager.getInstance(project).reformat(it.psiFile) }
    }

    private class Target(val file: VirtualFile, val psiFile: PsiFile, val document: Document, val offset: Int, val located: Boolean)

    private class Named(val element: PsiElement, val description: String)

    private suspend fun target(params: RefactorParams): Target {
        val path = params.file ?: throw RefactorFailure("give the target's file")
        val file = withContext(Dispatchers.IO) { CodeLocation.findFile(project, path) } ?: throw RefactorFailure("file not found: $path")
        return smartReadAction(project) {
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: throw RefactorFailure("$path is not a source file the IDE parses")
            val document = FileDocumentManager.getInstance().getDocument(file) ?: throw RefactorFailure("$path has no text")
            val located = params.line != null || params.symbol != null
            val offset = if (!located) 0 else try {
                CodeLocation.resolve(document.text, params.line, params.column, params.symbol, null, params.nth).first
            } catch (e: UiStepFailure) {
                throw RefactorFailure(e.message ?: "no such location")
            }
            Target(file, psiFile, document, offset, located)
        }
    }

    /** The declaration at the target: a reference's target, or the named element whose name is at the offset. */
    private suspend fun named(target: Target): Named {
        if (!target.located) throw RefactorFailure("give the target's symbol, or its line and column")
        return smartReadAction(project) {
            val file = target.psiFile
            val element = file.findReferenceAt(target.offset)?.resolve()
                ?: generateSequence(file.findElementAt(target.offset)) { it.parent.takeIf { p -> p !is PsiFile } }
                    .firstOrNull { it is PsiNameIdentifierOwner && it.nameIdentifier?.textRange?.contains(target.offset) == true }
                ?: PsiTreeUtil.getParentOfType(file.findElementAt(target.offset), PsiNamedElement::class.java, false)?.takeIf { it !is PsiFile }
                ?: throw RefactorFailure("no named declaration or reference at ${where(target.document, target.file, target.offset)}")
            Named(element, describe(element))
        }
    }

    private fun describe(element: PsiElement): String {
        val name = (element as? PsiNamedElement)?.name ?: element.text.take(40)
        val kind = element.javaClass.simpleName.removePrefix("Kt").removePrefix("Psi").removeSuffix("Impl")
        val file = element.containingFile?.virtualFile
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        val at = if (file != null && document != null) " at ${where(document, file, element.textOffset)}" else ""
        return "$kind $name$at"
    }

    private fun where(document: Document, file: VirtualFile, offset: Int): String {
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        return "${CodeLocation.shortPath(project, file)}:${line + 1}"
    }

    /** `path:line: text` for the line of [file] that holds [offset], as usages and blocking usages print. Read action. */
    private fun lineAt(file: VirtualFile, offset: Int): String? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        return "${CodeLocation.shortPath(project, file)}:${line + 1}: ${text.take(120)}"
    }

    private suspend fun usageLines(named: Named): List<String> = smartReadAction(project) {
        ReferencesSearch.search(named.element, GlobalSearchScope.projectScope(project)).findAll().mapNotNull { ref ->
            val element = ref.element
            val file = element.containingFile?.virtualFile ?: return@mapNotNull null
            lineAt(file, element.textRange.startOffset + ref.rangeInElement.startOffset)
        }.distinct().sorted()
    }

    private suspend fun usages(named: Named): String {
        val lines = usageLines(named)
        return buildString {
            append(named.description).append(": used on ").append(lines.size).append(" line(s)")
            lines.take(MAX_LINES).forEach { append('\n').append(it) }
            if (lines.size > MAX_LINES) append("\n… ").append(lines.size - MAX_LINES).append(" more")
        }
    }

    private suspend fun rename(params: RefactorParams): String {
        val newName = params.newName?.takeIf { it.isNotBlank() } ?: throw RefactorFailure("rename needs new_name")
        val target = target(params)
        val named = named(target)
        readAction {
            if ((named.element as? PsiNamedElement)?.name == newName) throw RefactorFailure("${named.description} is already named $newName")
            if (!RenameUtil.isValidName(project, named.element, newName)) throw RefactorFailure("$newName is not a valid name for ${named.description}")
        }
        if (!params.apply) return "dry run: rename ${named.description} to $newName\n" + conflictLines(dryRunRenameConflicts(named.element, newName)) + usages(named)
        val pathBefore = target.file.path
        val processor = readAction {
            if (!named.element.isValid) throw RefactorFailure("the code changed since the target was found; nothing was changed, try again")
            QuietRenameProcessor(project, named.element, newName)
        }
        val report = applier.apply("Rename to $newName") { processor.run() }
        if (processor.conflicts.isNotEmpty()) throw RefactorFailure("rename to $newName has conflicts; nothing was changed\n" + conflictLines(processor.conflicts).trimEnd())
        if (!processor.applied) throw RefactorFailure("the rename did not reach its write step; nothing was changed")
        // A class rename also renames its file, whose old path no longer resolves.
        val moved = if (target.file.isValid && target.file.path != pathBefore) "\nrenamed the file to ${CodeLocation.shortPath(project, target.file)}" else ""
        return "renamed ${named.description} to $newName$moved\n$report"
    }

    private suspend fun safeDelete(params: RefactorParams): String {
        val named = named(target(params))
        val blocking = blockingUsages(named.element)
        if (!params.apply) return "dry run: safe delete ${named.description}\n" + conflictLines(blocking) + usages(named)
        if (blocking.isNotEmpty()) throw RefactorFailure("${named.description} is still used; nothing was changed\n" + conflictLines(blocking).trimEnd())
        val processor = readAction { SafeDeleteProcessor.createInstance(project, null, arrayOf(named.element), false, false) }
        return applier.apply("Safe Delete") { processor.run() }.let { "deleted ${named.description}\n$it" }
    }

    /** The conflicts a rename would show in its dialog, found the way QuietRenameProcessor finds them, for a dry run. */
    private suspend fun dryRunRenameConflicts(element: PsiElement, newName: String): List<String> = smartReadAction(project) {
        val renames = linkedMapOf(element to newName)
        renameConflicts(element, newName, RenameUtil.findUsages(element, newName, false, false, renames), renames)
    }

    /** The usages that would stop a safe delete, from the language's safe-delete delegate, with no dialog. */
    private suspend fun blockingUsages(element: PsiElement): List<String> = smartReadAction(project) {
        val found = mutableListOf<UsageInfo>()
        SafeDeleteProcessorDelegate.EP_NAME.extensionList.firstOrNull { it.handlesElement(element) }?.findUsages(element, arrayOf(element), found)
        found.filter { it is SafeDeleteReferenceUsageInfo && !it.isSafeDelete }.mapNotNull { usage ->
            val file = usage.virtualFile ?: return@mapNotNull null
            lineAt(file, usage.navigationOffset)?.let { "used at $it" }
        }.distinct()
    }

    private fun conflictLines(conflicts: List<String>): String =
        if (conflicts.isEmpty()) "" else "conflicts:\n" + conflicts.joinToString("") { "- $it\n" }

    private suspend fun move(params: RefactorParams): String {
        val target = target(params)
        val to = params.to ?: throw RefactorFailure("move needs to: the target directory")
        val directory = withContext(Dispatchers.IO) { CodeLocation.findFile(project, to) }?.takeIf { it.isDirectory }
            ?: throw RefactorFailure("directory not found: $to")
        val psiDirectory = readAction { PsiManager.getInstance(project).findDirectory(directory) } ?: throw RefactorFailure("$to is not in the project")
        val describe = "${CodeLocation.shortPath(project, target.file)} to ${CodeLocation.shortPath(project, directory)}"
        if (!params.apply) return "dry run: move $describe\n" + usages(Named(target.psiFile, CodeLocation.shortPath(project, target.file)))
        val processor = MoveFilesOrDirectoriesProcessor(project, arrayOf(target.psiFile), psiDirectory, true, false, false, null, null)
        return applier.apply("Move") { processor.run() }.let { "moved $describe\n$it" }
    }

    private suspend fun problems(target: Target, shortName: String): List<ProblemDescriptor> = smartReadAction(project) {
        val wrapper = InspectionProjectProfileManager.getInstance(project).currentProfile.getInspectionTool(shortName, target.psiFile)
            as? LocalInspectionToolWrapper ?: throw RefactorFailure("no local inspection with the short name $shortName")
        InspectionEngine.inspectEx(
            listOf(wrapper), target.psiFile, target.psiFile.textRange, target.psiFile.textRange, false, false, true,
            EmptyProgressIndicator(), PairProcessor<LocalInspectionToolWrapper, Any> { _, _ -> true },
        ).values.flatten().sortedBy { it.textRangeInElement?.startOffset?.plus(it.psiElement?.textRange?.startOffset ?: 0) ?: it.psiElement?.textOffset ?: 0 }
    }

    private fun problemOffset(problem: ProblemDescriptor): Int =
        (problem.psiElement?.textRange?.startOffset ?: 0) + (problem.textRangeInElement?.startOffset ?: 0)

    private suspend fun fix(params: RefactorParams): String {
        val shortName = params.inspection ?: throw RefactorFailure("fix needs inspection: the inspection's short name")
        val target = target(params)
        val found = problems(target, shortName)
        val listing = readAction {
            found.map { p ->
                val line = target.document.getLineNumber(problemOffset(p).coerceIn(0, target.document.textLength)) + 1
                "${CodeLocation.shortPath(project, target.file)}:$line: ${plainText(p.descriptionTemplate.replace("#ref", "").replace("#loc", ""))}" +
                    (p.fixes?.joinToString(prefix = " [fix: ", postfix = "]") { it.name } ?: " [no fix]")
            }
        }
        if (!params.apply) return "dry run: $shortName reports ${found.size} problem(s)" + listing.joinToString("") { "\n$it" }
        if (params.all) {
            var applied = 0
            val report = applier.collectChanges("fixes for $shortName", "each fix is one Edit > Undo step") {
                // Stops when no fixable problem is left, or when a fix leaves as many problems as before.
                var remaining = Int.MAX_VALUE
                repeat(MAX_FIXES) {
                    val fixable = problems(target, shortName).filter { !it.fixes.isNullOrEmpty() }
                    if (fixable.isEmpty() || fixable.size >= remaining) return@collectChanges
                    remaining = fixable.size
                    applyFix(fixable.first(), fixable.first().fixes!!.first())
                    applied++
                }
            }
            return "applied $applied fix(es) for $shortName\n$report"
        }
        val line = readAction { target.document.getLineNumber(target.offset) }
        val chosen = found.firstOrNull { p -> !p.fixes.isNullOrEmpty() && (!target.located || readAction { target.document.getLineNumber(problemOffset(p)) } == line) }
            ?: throw RefactorFailure("no $shortName problem with a fix" + if (target.located) " on line ${line + 1}" else "")
        val fix = chosen.fixes!!.first()
        return applier.collectChanges(fix.name) { applyFix(chosen, fix) }.let { "applied \"${fix.name}\"\n$it" }
    }

    /** The editor's way: on the EDT, in one command, in a write action only when the fix asks for one. */
    private suspend fun applyFix(problem: ProblemDescriptor, fix: QuickFix<in ProblemDescriptor>) {
        @Suppress("UNCHECKED_CAST")
        val typed = fix as QuickFix<ProblemDescriptor>
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            CommandProcessor.getInstance().executeCommand(project, {
                if (typed.startInWriteAction()) ApplicationManager.getApplication().runWriteAction { typed.applyFix(project, problem) }
                else typed.applyFix(project, problem)
            }, "${RefactorApplier.UNDO_PREFIX}${typed.name}", null)
        }
    }

    private suspend fun intention(params: RefactorParams): String {
        val target = target(params)
        if (!target.located) throw RefactorFailure("give the target's symbol, or its line and column")
        // A hidden editor, released at the end: no tab opens and the user's caret stays where it was.
        val editor = withContext(Dispatchers.EDT) {
            EditorFactory.getInstance().createEditor(target.document, project, target.file, false).also { it.caretModel.moveToOffset(target.offset) }
        }
        try {
            val available = readAction { availableIntentions(target.psiFile, editor, target.offset) }
            val listing = available.joinToString("; ") { it.text }
            if (!params.apply) return "dry run: intentions at${where(target.document, target.file, target.offset)}: $listing"
            val wanted = params.name ?: throw RefactorFailure("intention needs name: one of $listing")
            val action = available.firstOrNull { it.text == wanted } ?: available.firstOrNull { it.text.startsWith(wanted, ignoreCase = true) }
                ?: throw RefactorFailure("no intention \"$wanted\" at the target; available: $listing")
            val text = action.text
            return applier.apply(text) {
                ShowIntentionActionsHandler.chooseActionAndInvoke(target.psiFile, editor, action, text)
            }.let { "applied \"$text\"\n$it" }
        } finally {
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { EditorFactory.getInstance().releaseEditor(editor) }
        }
    }

    private fun availableIntentions(file: PsiFile, editor: Editor, offset: Int): List<IntentionAction> =
        IntentionManager.getInstance().availableIntentions.filter { action ->
            runCatching { ShowIntentionActionsHandler.availableFor(file, editor, offset, action) }.getOrDefault(false)
        }

    /**
     * The language's import optimizers, called directly: computed in a read action, applied in one write command.
     * OptimizeImportsProcessor schedules its work and returns before it ran.
     */
    private suspend fun optimizeImports(params: RefactorParams): String {
        val target = target(params)
        val optimizers = readAction { LanguageImportStatements.INSTANCE.forFile(target.psiFile).filter { it.supports(target.psiFile) } }
        if (optimizers.isEmpty()) throw RefactorFailure("${CodeLocation.shortPath(project, target.file)}: its language has no import optimizer")
        if (!params.apply) return "dry run: optimize imports in ${CodeLocation.shortPath(project, target.file)}; pass apply to change it"
        val changes = smartReadAction(project) { optimizers.map { it.processFile(target.psiFile) } }
        return fileEdit(params, "Optimize Imports", target) { changes.forEach { it.run() } }
    }

    /** A synchronous edit of the target's file, on the EDT in one write command. */
    private suspend fun fileEdit(params: RefactorParams, title: String, known: Target? = null, edit: (Target) -> Unit): String {
        val target = known ?: target(params)
        val path = CodeLocation.shortPath(project, target.file)
        if (!params.apply) return "dry run: ${title.lowercase()} $path; pass apply to change it"
        return applier.collectChanges(title) {
            withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                WriteCommandAction.runWriteCommandAction(project, RefactorApplier.UNDO_PREFIX + title, null, { edit(target) })
            }
        }.let { "${title.lowercase()}: $path\n$it" }
    }

    private companion object {
        const val MAX_LINES = 30
        const val MAX_FIXES = 50
    }
}

