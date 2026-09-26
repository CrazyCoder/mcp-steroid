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
        val found = MultiMap<PsiElement, String>()
        RenameUtil.addConflictDescriptions(refUsages.get(), found)
        RenamePsiElementProcessor.forElement(target).findExistingNameConflicts(target, newName, found, myAllRenames)
        conflicts += found.values().map { it.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim() }.distinct()
        return conflicts.isEmpty()
    }

    override fun performRefactoring(usages: Array<UsageInfo>) {
        super.performRefactoring(usages)
        applied = true
    }
}
