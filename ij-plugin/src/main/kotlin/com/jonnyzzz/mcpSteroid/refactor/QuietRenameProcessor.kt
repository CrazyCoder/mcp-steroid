/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.refactor

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * The platform rename with every dialog answered in advance, after the IDE MCP server's rename in 2026.3: no usage
 * preview, no automatic renamers (the variables named after a renamed class), and conflicts kept in [conflicts]
 * instead of a dialog. A language that refuses the write still shows a message box, which 2026.1 offers no hook
 * for; RefactorApplier reads and closes it. 2026.3 adds HeadlessRenameProcessor, which does all of this, but the
 * plugin also runs on 2026.1 and 2026.2.
 */
internal class QuietRenameProcessor(project: Project, private val target: PsiElement, private val newName: String) :
    RenameProcessor(project, target, newName, false, false) {

    /** What stopped the rename before it wrote, one line each. */
    val conflicts = mutableListOf<String>()

    /** Whether the rename reached its write step. */
    var applied = false
        private set

    override fun isPreviewUsages(usages: Array<out UsageInfo>): Boolean = false

    override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer?): Boolean = false

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        conflicts += renameConflicts(target, newName, refUsages.get(), myAllRenames)
        return conflicts.isEmpty()
    }

    override fun performRefactoring(usages: Array<UsageInfo>) {
        super.performRefactoring(usages)
        applied = true
    }
}

/** The conflicts the rename dialog would show for [usages]: name clashes and the usages' own conflicts, as plain text. */
internal fun renameConflicts(target: PsiElement, newName: String, usages: Array<UsageInfo>, allRenames: Map<PsiElement, String>): List<String> {
    val found = MultiMap<PsiElement, String>()
    RenameUtil.addConflictDescriptions(usages, found)
    RenamePsiElementProcessor.forElement(target).findExistingNameConflicts(target, newName, found, allRenames)
    return found.values().map { plainText(it) }.distinct()
}

/** [html] as plain text: tags and entities dropped, whitespace folded, as refactoring messages carry markup. */
internal fun plainText(html: String): String =
    html.replace(Regex("(?i)<(br|p|/p|li|tr)\\b[^>]*>"), " ").replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        // Numeric entities, such as the &#32; a JavaScript type message puts between words.
        .replace(Regex("&#(x[0-9a-fA-F]+|[0-9]+);")) { m ->
            val code = m.groupValues[1]
            (if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull())?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
