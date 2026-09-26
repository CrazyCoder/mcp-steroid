/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.refactor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
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
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.blockingContextToIndicator
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
import kotlinx.coroutines.CancellationException
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
        RefactorOp.REFORMAT -> fileEdit(params, "Reformat") { file -> Runnable { CodeStyleManager.getInstance(project).reformat(file) } }
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

    /** A line of code that uses the target, printed as `path:line: text`, sorted by path, then line. */
    private data class UsageLine(val path: String, val line: Int, val text: String) : Comparable<UsageLine> {
        override fun compareTo(other: UsageLine) = compareValuesBy(this, other, { it.path }, { it.line })
        override fun toString() = "$path:$line: $text"
    }

    /** The line of [file] that holds [offset]. Read action. */
    private fun lineAt(file: VirtualFile, offset: Int): UsageLine? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        return UsageLine(CodeLocation.shortPath(project, file), line + 1, text.take(120))
    }

    private suspend fun usageLines(named: Named): List<UsageLine> = smartReadAction(project) {
        ReferencesSearch.search(named.element, GlobalSearchScope.projectScope(project)).findAll().mapNotNull { ref ->
            val element = ref.element
            val file = element.containingFile?.virtualFile ?: return@mapNotNull null
            lineAt(file, element.textRange.startOffset + ref.rangeInElement.startOffset)
        }.distinct().sorted()
    }

    private suspend fun usages(named: Named): String = named.description + listing("used on", usageLines(named))

    /** ": [verb] N line(s)" and the first lines, one per row. */
    private fun listing(verb: String, lines: List<UsageLine>): String = buildString {
        append(": ").append(verb).append(' ').append(lines.size).append(" line(s)")
        lines.take(MAX_LINES).forEach { append('\n').append(it) }
        if (lines.size > MAX_LINES) append("\n… ").append(lines.size - MAX_LINES).append(" more")
    }

    private suspend fun rename(params: RefactorParams): String {
        val newName = params.newName?.takeIf { it.isNotBlank() } ?: throw RefactorFailure("rename needs new_name")
        val target = target(params)
        val named = named(target)
        readAction {
            if ((named.element as? PsiNamedElement)?.name == newName) throw RefactorFailure("${named.description} is already named $newName")
            if (!RenameUtil.isValidName(project, named.element, newName)) throw RefactorFailure("$newName is not a valid name for ${named.description}")
        }
        if (!params.apply) return renameDryRun(named, newName)
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

    /**
     * What the rename would do, found the way QuietRenameProcessor finds it: its conflicts, the lines it would change,
     * the references it leaves alone (such as a Markdown code span naming the symbol), and declarations of the new
     * name next to the target, which a language without a clash check (Rust) does not report.
     */
    private suspend fun renameDryRun(named: Named, newName: String): String {
        val element = named.element
        val (conflicts, changed, sameName) = smartReadAction(project) {
            val renames = linkedMapOf(element to newName)
            val usages = RenameUtil.findUsages(element, newName, false, false, renames)
            val lines = usages.mapNotNull { usage -> usage.virtualFile?.let { lineAt(it, usage.navigationOffset) } }.distinct().sorted()
            val siblings = element.parent?.children.orEmpty().filter {
                it !== element && it.javaClass == element.javaClass && (it as? PsiNamedElement)?.name == newName
            }.map { describe(it) }
            Triple(renameConflicts(element, newName, usages, renames), lines, siblings)
        }
        val untouched = usageLines(named) - changed.toSet()
        return buildString {
            append("dry run: rename ${named.description} to $newName\n").append(conflictLines(conflicts))
            if (conflicts.isEmpty() && sameName.isNotEmpty()) {
                append("same name in the same scope, a clash unless the language allows overloads:\n")
                sameName.forEach { append("- ").append(it).append('\n') }
            }
            append(named.description).append(listing("would change", changed))
            if (untouched.isNotEmpty()) append("\nleaves alone").append(listing("the references on", untouched).removePrefix(":"))
        }
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

    private suspend fun problems(target: Target, shortName: String): List<ProblemDescriptor> =
        inspect(target, listOf(inspectionTool(target, shortName))).map { it.second }

    /** The inspection [shortName] of the current profile; an unknown name fails with the similar names it has. */
    private suspend fun inspectionTool(target: Target, shortName: String): LocalInspectionToolWrapper = readAction {
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        profile.getInspectionTool(shortName, target.psiFile) as? LocalInspectionToolWrapper ?: run {
            val similar = profile.allTools.map { it.tool.shortName }.distinct().filter { it.contains(shortName, ignoreCase = true) || shortName.contains(it, ignoreCase = true) }
                .sortedWith(compareBy({ !it.startsWith(shortName, ignoreCase = true) }, { it.length })).take(8)
            throw RefactorFailure("no local inspection with the short name $shortName" + if (similar.isEmpty()) "; a dry run without inspection lists the file's problems" else "; similar: ${similar.joinToString()}")
        }
    }

    /** The local inspections the current profile enables for the target's file, in its language. */
    private suspend fun enabledInspections(target: Target): List<LocalInspectionToolWrapper> = readAction {
        InspectionProjectProfileManager.getInstance(project).currentProfile.getAllEnabledInspectionTools(project)
            .mapNotNull { it.getEnabledTool(target.psiFile) as? LocalInspectionToolWrapper }
            .filter { it.isApplicable(target.psiFile.language) }
    }

    /** What [tools] report on the target's file, by position, each with its inspection's short name. */
    private suspend fun inspect(target: Target, tools: List<LocalInspectionToolWrapper>): List<Pair<String, ProblemDescriptor>> = smartReadAction(project) {
        // The read action's own indicator, which a pending write action cancels. A fresh indicator would never be
        // cancelled: the inspections would hold the read lock to the end and freeze the EDT while it waits to write.
        blockingContextToIndicator {
            InspectionEngine.inspectEx(
                tools, target.psiFile, target.psiFile.textRange, target.psiFile.textRange, false, false, true,
                ProgressManager.getGlobalProgressIndicator() ?: EmptyProgressIndicator(), PairProcessor<LocalInspectionToolWrapper, Any> { _, _ -> true },
            )
        }.flatMap { (tool, problems) -> problems.map { tool.shortName to it } }.sortedBy { problemOffset(it.second) }
    }

    private fun problemOffset(problem: ProblemDescriptor): Int =
        (problem.psiElement?.textRange?.startOffset ?: 0) + (problem.textRangeInElement?.startOffset ?: 0)

    /** `path:line: description [fix: ...]`, with the inspection's short name when [named]. Read action. */
    private fun problemLine(target: Target, problem: ProblemDescriptor, named: String?): String {
        val line = target.document.getLineNumber(problemOffset(problem).coerceIn(0, target.document.textLength)) + 1
        return "${CodeLocation.shortPath(project, target.file)}:$line: " + (named?.let { "[$it] " } ?: "") +
            // As the Problems view renders it: #ref becomes the reported code, #loc goes.
            plainText(ProblemDescriptorUtil.renderDescriptionMessage(problem, problem.psiElement)) +
            (problem.fixes?.takeIf { it.isNotEmpty() }?.joinToString(prefix = " [fix: ", postfix = "]") { it.name } ?: " [no fix]")
    }

    private suspend fun fix(params: RefactorParams): String {
        val target = target(params)
        val shortName = params.inspection ?: run {
            if (params.apply) throw RefactorFailure("fix needs inspection: the short name in brackets that a dry run without it lists")
            val found = inspect(target, enabledInspections(target))
            // An inspection can report one problem twice, as a warning and as an editor-only hint.
            val lines = readAction { found.map { (name, p) -> problemLine(target, p, name) }.distinct() }
            return "dry run: the enabled inspections report ${lines.size} problem(s); compiler and annotator errors are not listed" +
                lines.joinToString("") { "\n$it" }
        }
        val found = problems(target, shortName)
        val listing = readAction { found.map { problemLine(target, it, null) } }
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
            if (!params.apply) return "dry run: intentions at ${where(target.document, target.file, target.offset)}: $listing"
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
        return fileEdit(params, "Optimize Imports", target) { file ->
            val changes = optimizers.map { it.processFile(file) }
            Runnable { changes.forEach { it.run() } }
        }
    }

    /**
     * A synchronous edit of the target's file: [prepare] runs in a smart read action and returns the change, which runs
     * on the EDT in one write command. A dry run makes the change on a copy of the file and reports how many lines it
     * would add and remove.
     */
    private suspend fun fileEdit(params: RefactorParams, title: String, known: Target? = null, prepare: (PsiFile) -> Runnable): String {
        val target = known ?: target(params)
        val path = CodeLocation.shortPath(project, target.file)
        if (!params.apply) return "dry run: ${title.lowercase()} $path: " + preview(target, prepare)
        return applier.collectChanges(title) {
            withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            val change = smartReadAction(project) { prepare(target.psiFile) }
            withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                WriteCommandAction.runWriteCommandAction(project, RefactorApplier.UNDO_PREFIX + title, null, change)
            }
        }.let { "${title.lowercase()}: $path\n$it" }
    }

    /** The lines [prepare]'s change would add and remove, made on a copy of the file that nobody sees. */
    private suspend fun preview(target: Target, prepare: (PsiFile) -> Runnable): String = try {
        val (before, copy) = smartReadAction(project) { target.psiFile.text to target.psiFile.copy() as PsiFile }
        val change = smartReadAction(project) { prepare(copy) }
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { ApplicationManager.getApplication().runWriteAction(change) }
        val after = readAction { copy.text }
        if (after == before) "no change"
        else lineCounts(before, after).let { (added, removed) -> "would change the file +$added -$removed; pass apply to change it" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "the change cannot be previewed (${e.javaClass.simpleName}); pass apply to change it"
    }

    private companion object {
        const val MAX_LINES = 30
        const val MAX_FIXES = 50
    }
}

