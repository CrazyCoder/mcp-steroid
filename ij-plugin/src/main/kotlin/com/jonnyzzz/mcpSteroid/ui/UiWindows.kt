/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import java.awt.Window
import javax.swing.JToolTip
import javax.swing.RootPaneContainer

/** Which windows belong to a project frame, in the order a snapshot lists them. */
object UiWindows {
    /**
     * [all] in creation order, as `Window.getWindows()` returns them. [tops] are the windows without an owner that
     * belong to the project: its frame, and frames such as the separate Settings window. The result is every top and
     * every window a top owns, directly or through another owned window, newest first: a window opened later shows
     * over the ones before it.
     */
    fun <W : Any> order(tops: List<W>, all: List<W>, ownerOf: (W) -> W?): List<W> =
        all.filter { w -> w in tops || generateSequence(ownerOf(w), ownerOf).any { it in tops } }.reversed()

    /**
     * The showing windows of [project]: [frame], the unowned windows whose content belongs to the project, and their
     * dialogs and heavyweight popups, topmost first. Tooltips are left out: they come and go with the mouse. Call on
     * the EDT.
     */
    fun projectWindows(project: Project, frame: Window): List<Window> {
        val showing = Window.getWindows().filter { it.isShowing && !isTooltip(it) }
        val tops = listOf(frame) + showing.filter { it !== frame && it.owner == null && projectOf(it) === project }
        return order(tops, showing, Window::getOwner)
    }

    /** The project a window's content belongs to, from its data context. EDT. */
    private fun projectOf(w: Window): Project? {
        val root = (w as? RootPaneContainer)?.rootPane ?: return null
        return CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(root))
    }

    /** A popup window that hosts a tooltip: a Swing one, or the IDE's help tooltip over a toolbar button. EDT. */
    fun isTooltip(w: Window): Boolean {
        if (w.owner == null) return false
        val root = (w as? RootPaneContainer)?.rootPane ?: return false
        return UIUtil.uiTraverser(root).any { it is JToolTip || it.javaClass.name.startsWith(HELP_TOOLTIP) }
    }

    private const val HELP_TOOLTIP = "com.intellij.ide.HelpTooltip"
}
