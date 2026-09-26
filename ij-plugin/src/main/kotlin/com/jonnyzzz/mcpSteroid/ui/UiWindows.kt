/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import java.awt.Window
import javax.swing.JToolTip
import javax.swing.RootPaneContainer

/** Which windows belong to a project, in the order a snapshot lists them. */
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
     * dialogs and heavyweight popups, topmost first. Hover popups are left out: they come and go with the mouse. Call
     * on the EDT.
     */
    fun projectWindows(project: Project, frame: Window): List<Window> {
        val showing = Window.getWindows().filter { it.isShowing && !isHoverPopup(it) }
        val tops = listOf(frame) + showing.filter { it !== frame && it.owner == null && projectOf(it) === project }
        return order(tops, showing, Window::getOwner)
    }

    /** The project a window's content belongs to, from its data context. EDT. */
    private fun projectOf(w: Window): Project? {
        val root = (w as? RootPaneContainer)?.rootPane ?: return null
        return CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(root))
    }

    /**
     * A popup window the mouse opens by hovering: a tooltip, the IDE's help tooltip over a toolbar button, or the hint
     * that shows the whole text of a cut-off tree or list row. EDT.
     */
    fun isHoverPopup(w: Window): Boolean {
        // Only a popup window can be one, so the walk never runs over a dialog's or a frame's controls.
        if (w.owner == null || w.type != Window.Type.POPUP) return false
        val root = (w as? RootPaneContainer)?.rootPane ?: return false
        return UIUtil.uiTraverser(root).any { c -> c is JToolTip || HOVER_CLASS_PREFIXES.any { c.javaClass.name.startsWith(it) } }
    }

    private val HOVER_CLASS_PREFIXES = listOf("com.intellij.ide.HelpTooltip", "com.intellij.ui.AbstractExpandableItemsHandler")
}
