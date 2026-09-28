/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.ScreenUtil
import com.intellij.ui.SimpleColoredComponent
import java.awt.Component
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities

/** How much of a control its panels and window leave showing, as a snapshot marks it. */
enum class UiClip(val label: String) {
    /** Nothing of it shows: it lies past the edge of a panel or of its window, and no click reaches it. */
    OUTSIDE("outside"),

    /** Part of it lies past the edge of a panel or of its window. */
    CLIPPED("clipped"),

    /** It shows whole, but its text is cut to its width. */
    TRUNCATED("truncated"),
}

/**
 * What a person notices at a glance and a component tree hides: a control past the edge of a panel that is too small
 * for it, a label cut to its width, a tree whose rows are wider than its view, a tool window narrower than its header.
 * Call on the EDT.
 */
object UiLayout {
    /** Pixels of rounding that do not count as cut. */
    private const val SLACK = 2

    /**
     * A control counts as clipped when this share of its width or height is cut, and at least [CLIP_MIN] pixels: an
     * icon button a few pixels taller than its tab, such as an editor tab's close button, still shows its icon whole.
     */
    private const val CLIP_SHARE = 0.2
    private const val CLIP_MIN = 6

    /** A fitted side tool window takes at most this share of the IDE window's width, a bottom one this share of its height. */
    private const val FIT_SHARE = 0.4

    private const val MAX_REFS = 8

    /**
     * The part of [c] that its panels and its window leave showing, in [c]'s coordinates. The walk stops at a scroll
     * pane's viewport unless [throughViewports]: what a scroll pane keeps out of view is scrolling, not a panel that is
     * too small, and a snapshot marks it apart.
     */
    fun visiblePart(c: Component, throughViewports: Boolean = false): Rectangle {
        var rect = Rectangle(0, 0, c.width, c.height)
        var child = c
        var parent = c.parent
        while (parent != null && child !is Window) {
            if (parent is JViewport && !throughViewports) break
            rect.translate(child.x, child.y)
            rect = rect.intersection(Rectangle(0, 0, parent.width, parent.height))
            if (rect.isEmpty) return Rectangle()
            child = parent
            parent = parent.parent
        }
        return SwingUtilities.convertRectangle(child, rect, c)
    }

    fun clip(c: Component): UiClip? {
        if (!c.isShowing || c.width <= 0 || c.height <= 0) return null
        return clipOf(c.width, c.height, visiblePart(c)) ?: if (truncated(c)) UiClip.TRUNCATED else null
    }

    /** How a control of [width] x [height] is cut when [part] of it shows. */
    internal fun clipOf(width: Int, height: Int, part: Rectangle): UiClip? = when {
        part.isEmpty -> UiClip.OUTSIDE
        cut(width - part.width, width) || cut(height - part.height, height) -> UiClip.CLIPPED
        else -> null
    }

    private fun cut(lost: Int, size: Int): Boolean = lost >= CLIP_MIN && lost >= size * CLIP_SHARE

    /** A label or a button whose text needs more width than it has, so the IDE cuts it, often with an ellipsis. */
    private fun truncated(c: Component): Boolean {
        val text = when (c) {
            is JLabel -> c.text
            is AbstractButton -> c.text
            is SimpleColoredComponent -> c.getCharSequence(false).toString()
            else -> null
        }
        return !text.isNullOrBlank() && c.preferredSize.width > c.width + SLACK
    }

    /** For a list, tree or table wider than its scroll pane shows: how much, as the rows are cut at the right. */
    fun rowsCut(c: Component): String? {
        if (c !is JTree && c !is JList<*> && c !is JTable) return null
        // A table that resizes its columns to the view squeezes them instead of cutting its rows.
        if (c is JTable && c.autoResizeMode != JTable.AUTO_RESIZE_OFF) return null
        val port = c.parent as? JViewport ?: return null
        val need = c.preferredSize.width
        val shown = port.extentSize.width
        return if (need > shown + SLACK) "rows need $need px and the view shows $shown px, so they are cut at the right" else null
    }

    /** A showing tool window of a project, with its id. */
    class ToolWindowView(val id: String, val window: ToolWindowEx) {
        private val decorator get() = window.decorator
        private val sideways get() = window.anchor == ToolWindowAnchor.LEFT || window.anchor == ToolWindowAnchor.RIGHT

        /** Its size along the axis a person drags: the width of a side tool window, the height of a top or bottom one. */
        val size: Int get() = if (sideways) decorator.width else decorator.height
        val axis: String get() = if (sideways) "width" else "height"

        /** What its header needs: the tool window's minimum size, which the IDE lets a drag go below. */
        val needs: Int get() = if (sideways) decorator.minimumSize.width else decorator.minimumSize.height

        /** The size that shows its header and, within [FIT_SHARE] of the IDE window, its content whole. */
        fun fit(): Int {
            val frame = SwingUtilities.getWindowAncestor(decorator)
            val preferred = if (sideways) decorator.preferredSize.width else decorator.preferredSize.height
            val room = frame?.let { ((if (sideways) it.width else it.height) * FIT_SHARE).toInt() } ?: preferred
            return maxOf(needs, minOf(preferred, room))
        }

        val tooSmall: Boolean get() = decorator.isShowing && size < needs - SLACK
        val step: String get() = """{"action":"toolwindow","id":"$id","$axis":"fit"}"""
        fun describe(): String = "the $id tool window is $size px ${if (sideways) "wide" else "high"} and its header needs $needs px"
        fun holds(c: Component): Boolean = SwingUtilities.isDescendingFrom(c, decorator)
    }

    /** The project's tool windows that show, docked or in a window of their own. */
    fun toolWindows(project: Project): List<ToolWindowView> {
        val manager = ToolWindowManager.getInstance(project)
        return manager.toolWindowIds.mapNotNull { id ->
            (manager.getToolWindow(id) as? ToolWindowEx)?.takeIf { it.isVisible && it.decorator.isShowing }?.let { ToolWindowView(id, it) }
        }
    }

    /**
     * One layout line of a snapshot: a tool window, named by [toolWindow], or a window, that cuts controls, and [fix],
     * the step that makes room, or null when there is none to take.
     */
    class Problem(val line: String, val fix: String?, val toolWindow: String?)

    /** The layout lines of [window]'s snapshot, as [problems] finds them. */
    fun summary(window: Window, root: UiNode, refOf: (UiNode) -> String, project: Project): List<String> =
        problems(window, root, refOf, project).map { it.line }

    /**
     * Each tool window of [window] narrower than its header, and the controls that lie past an edge, grouped by the
     * tool window or the window that holds them, each with the step that makes room.
     */
    fun problems(window: Window, root: UiNode, refOf: (UiNode) -> String, project: Project): List<Problem> {
        val toolWindows = toolWindows(project).filter { SwingUtilities.isDescendingFrom(it.window.decorator, window) }
        val hidden = root.walk().filter { it.listed && (it.clip == UiClip.OUTSIDE || it.clip == UiClip.CLIPPED) }.toList()
        val byToolWindow = hidden.groupBy { node -> toolWindows.firstOrNull { it.holds(node.component) } }
        val problems = mutableListOf<Problem>()
        for (tw in toolWindows) {
            val inside = byToolWindow[tw].orEmpty()
            if (!tw.tooSmall && inside.isEmpty()) continue
            val line = buildString {
                append("layout: ")
                append(if (tw.tooSmall) tw.describe() else "the ${tw.id} tool window cuts controls")
                if (inside.isNotEmpty()) append(": ").append(controls(inside, refOf))
                append("; ").append(tw.step).append(" makes room")
            }
            problems += Problem(line, tw.step, tw.id)
        }
        byToolWindow[null]?.let { rest ->
            problems += Problem("layout: ${controls(rest, refOf)} in this window; ${windowStep(window)}", windowFix(window), null)
        }
        return problems
    }

    /**
     * The step that gives [window] room, which runs as it is: the IDE window, by its class, fills the screen unless it
     * does already; any other window, the topmost one, grows to its content.
     */
    private fun windowFix(window: Window): String? = when {
        window is IdeFrame && window is Frame ->
            if (window.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) null
            else """{"action":"window","class":"${window.javaClass.simpleName}","maximize":true}"""
        else -> """{"action":"window"}"""
    }

    /** The step that makes room for [c], a control past the edge of a panel or of its window, or null when none does. */
    fun fixFor(c: Component, project: Project): String? =
        toolWindows(project).firstOrNull { it.holds(c) }?.step ?: SwingUtilities.getWindowAncestor(c)?.let(::windowFix)

    private fun controls(nodes: List<UiNode>, refOf: (UiNode) -> String): String {
        val outside = nodes.count { it.clip == UiClip.OUTSIDE }
        val clipped = nodes.size - outside
        val counts = listOfNotNull(
            outside.takeIf { it > 0 }?.let { "$it past an edge" },
            clipped.takeIf { it > 0 }?.let { "$it clipped" },
        ).joinToString(", ")
        val refs = nodes.take(MAX_REFS).joinToString(", ") { refOf(it) } + if (nodes.size > MAX_REFS) ", …" else ""
        return "${if (nodes.size == 1) "1 control is cut" else "${nodes.size} controls are cut"} ($counts: $refs)"
    }

    /** The window step that gives [window] room, with the sizes that tell whether there is any. */
    fun windowStep(window: Window): String {
        val screen = ScreenUtil.getScreenRectangle(window)
        val size = "it is ${window.width}x${window.height} of ${screen.width}x${screen.height} on its screen"
        return if (window is IdeFrame && window is Frame) {
            if (window.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) "the IDE window fills the screen already, so a panel inside it needs room"
            else """{"action":"window","maximize":true} makes the IDE window fill the screen ($size)"""
        } else {
            """{"action":"window"} grows it to show its content ($size)"""
        }
    }

    /**
     * Why no click reaches [c], a control past the edge of a panel or of its window, and the step that makes room: a
     * toolwindow step when a tool window holds it, a window step otherwise.
     */
    fun unreachable(c: Component, project: Project): String {
        val tw = toolWindows(project).firstOrNull { it.holds(c) }
        val window = SwingUtilities.getWindowAncestor(c)
        val run = UiInspect.actionId(c)?.let { """; or run its action without clicking: {"action":"run","id":"$it"}""" }.orEmpty()
        return when {
            tw != null -> "it lies past the edge of the ${tw.id} tool window" +
                (if (tw.tooSmall) ": ${tw.describe()}" else "") + ". ${tw.step} makes room"
            window != null -> "it lies past the edge of its panel or window; ${windowStep(window)}"
            else -> "it lies past the edge of its panel"
        } + run
    }
}
