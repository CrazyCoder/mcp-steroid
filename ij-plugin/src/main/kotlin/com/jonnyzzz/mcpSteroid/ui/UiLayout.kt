/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.ScreenUtil
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.treeStructure.treetable.TreeTable
import java.awt.Component
import javax.swing.JComboBox
import javax.swing.JComponent
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
     * the step that makes room, or null when there is none to take. [area] is where the cut content is on screen, or
     * null when it does not show.
     */
    class Problem(val line: String, val fix: String?, val toolWindow: String?, val area: Rectangle? = null)

    /**
     * Content a pane, field or header cuts: what is cut, the splitter step that makes room, or null when none does, and
     * [need], the pixels a window must grow along [axis] to show it: what it lacks, over its pane's share of a
     * splitter that has no room of its own.
     */
    data class Cut(val node: UiNode, val what: String, val fix: String?, val need: Int = 0, val axis: UiSplitters.Axis = UiSplitters.Axis.WIDTH)

    /** Pixels a window step adds beyond what the cut content lacks, for the borders and rounding of its layout. */
    private const val WINDOW_ROOM = 8

    /**
     * The window step that gives a dialog [width] px wide, on a screen [screenWidth] px wide, the width its [cuts]
     * lack: the most any of them lacks, as a wider dialog widens each of its fields and tables; null when none lacks
     * width. A fit to the dialog's preferred size does not do: a dialog that cuts its fields often prefers the size it has.
     */
    fun sizedWindowFix(width: Int, screenWidth: Int, cuts: List<Cut>): String? {
        val need = cuts.filter { it.axis == UiSplitters.Axis.WIDTH }.maxOfOrNull { it.need }?.takeIf { it > 0 } ?: return null
        return """{"action":"window","width":${minOf(width + need + WINDOW_ROOM, screenWidth)}}"""
    }

    /**
     * Fewer rows than this showing, of a list that has more, count as cut: a pane squeezed to a few rows, as the
     * debugger's Variables pane under a large console is.
     */
    private const val MIN_ROWS_SHOWN = 8

    /** A list with more rows than this scrolls by design; its rows out of view are not cut. */
    private const val MAX_ROWS_TO_FIT = 40

    /**
     * The splitters that lay out tool windows and the editor area belong to the IDE window: a tool window or window
     * step sizes those. A detected cut looks for a splitter below these only.
     */
    private val LAYOUT_ROOTS = setOf("InternalDecoratorImpl", "EditorsSplitters")

    private fun defaultInDialog(c: Component): Boolean = SwingUtilities.getWindowAncestor(c)?.let { it !is IdeFrame } == true

    /**
     * Content cut inside panes, fields and headers under [root], which a component tree hides and a picture shows: rows
     * a splitter pane squeezes, a tree or tree table cut at the right, the text of an editor field, a combo box or a
     * table header cut to its width, a truncated label. A splitter pane's cut names the splitter step that makes room;
     * the others count in a dialog only, per [inDialog], where the window step does. EDT.
     */
    fun cuts(root: UiNode, refOf: (UiNode) -> String, inDialog: (Component) -> Boolean = ::defaultInDialog): List<Cut> =
        root.walk().flatMap { node -> cutsOf(node, refOf, inDialog) }.toList()

    private fun cutsOf(node: UiNode, refOf: (UiNode) -> String, inDialog: (Component) -> Boolean): List<Cut> {
        val c = node.component
        val out = mutableListOf<Cut>()
        fun add(what: String, axis: UiSplitters.Axis, need: Int) {
            val pane = paneFor(c, axis)
            // A splitter whose other panes keep their minimum sizes has no room to give: the window's step does then.
            val fix = pane?.takeIf { UiSplitters.fitSize(it, c) > UiSplitters.size(it) + SLACK }
                ?.let { """{"action":"splitter","ref":"${refOf(node)}","size":"fit"}""" }
            // A window step then makes the room, of which the splitter gives the pane only its share.
            val windowNeed = if (pane != null && fix == null) kotlin.math.ceil(need / UiSplitters.windowShare(pane)).toInt() else need
            if (pane != null || inDialog(c)) out += Cut(node, what, fix, windowNeed, axis)
        }
        val name = describe(node, refOf)
        val total = when (c) {
            is JTree -> c.rowCount
            is JList<*> -> c.model.size
            is JTable -> c.rowCount
            else -> -1
        }
        if (total in 1..MAX_ROWS_TO_FIT && c.parent is JViewport) {
            val view = (c as JComponent).visibleRect
            val shown = (0 until total).count { i -> UiRows.bounds(c, i)?.let { view.contains(it) } == true }
            val high = UiSplitters.shortfall(c, UiSplitters.Axis.HEIGHT)
            if (shown < minOf(total, MIN_ROWS_SHOWN) && high > SLACK) add("$name shows $shown of $total rows", UiSplitters.Axis.HEIGHT, high)
        }
        val wide = UiSplitters.shortfall(c, UiSplitters.Axis.WIDTH)
        if ((c is JTree || c is TreeTable || rowsCut(c) != null) && wide > SLACK && (c is TreeTable || c.parent is JViewport)) {
            add("the rows of $name are cut at the right: they need $wide px more", UiSplitters.Axis.WIDTH, wide)
        }
        if (c is JTable) {
            c.tableHeader?.let { header ->
                val lacks = (0 until c.columnModel.columnCount).map { c.columnModel.getColumn(it) }.associateWith { column ->
                    header.defaultRenderer.getTableCellRendererComponent(c, column.headerValue, false, false, -1, column.modelIndex).preferredSize.width - column.width
                }.filterValues { it > SLACK }
                // A wider table shares its width out over its columns: each cut column needs its share of the whole.
                if (lacks.isNotEmpty()) add("the header ${lacks.keys.joinToString { "\"${it.headerValue}\"" }} of $name is cut", UiSplitters.Axis.WIDTH,
                    lacks.values.max() * c.columnModel.columnCount)
            }
        }
        val fieldNeed = when (c) {
            is EditorComponentImpl -> c.editor.contentComponent.preferredSize.width - c.editor.scrollingModel.visibleArea.width
            is JComboBox<*> -> c.preferredSize.width - c.width
            else -> 0
        }
        // An editor in the IDE window scrolls its code sideways by design; only a field in a dialog counts.
        if (fieldNeed > SLACK && (c !is EditorComponentImpl || inDialog(c))) add("the text of $name is cut", UiSplitters.Axis.WIDTH, fieldNeed)
        if (node.clip == UiClip.TRUNCATED) add("$name is truncated", UiSplitters.Axis.WIDTH, c.preferredSize.width - c.width)
        return out
    }

    /** The pane of a splitter along [axis] that holds [c], below the splitters that lay out the IDE window. */
    private fun paneFor(c: Component, axis: UiSplitters.Axis): UiSplitters.Pane? {
        val pane = UiSplitters.paneOf(c, axis) ?: return null
        var p: Component? = c
        while (p != null && p !== pane.splitter) {
            if (UiComponentFacts.simpleClassName(p) in LAYOUT_ROOTS) return null
            p = p.parent
        }
        return pane
    }

    private fun describe(node: UiNode, refOf: (UiNode) -> String): String =
        node.className + (node.name?.let { " \"${it.take(40)}\"" } ?: "") + " [ref=${refOf(node)}]"

    /** The layout lines of [window]'s snapshot, as [problems] finds them. */
    fun summary(window: Window, root: UiNode, refOf: (UiNode) -> String, project: Project): List<String> =
        problems(window, root, refOf, project).map { it.line }

    /**
     * Each tool window of [window] narrower than its header, the controls that lie past an edge, grouped by the tool
     * window or the window that holds them, and the content cut inside panes, fields and headers, each with the step
     * that makes room: a splitter step for a splitter pane, the tool window's step, or the window's.
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
            problems += Problem(line, tw.step, tw.id, screenArea(listOf(tw.window.decorator)))
        }
        byToolWindow[null]?.let { rest ->
            problems += Problem("layout: ${controls(rest, refOf)} in this window; ${windowStep(window)}", windowFix(window), null, screenArea(rest.map { it.component }))
        }
        // One line per step: several cuts one splitter or one window step fixes read together.
        val all = cuts(root, refOf)
        // The cuts no splitter or tool window fixes widen the window by what the widest of them lacks.
        val loose = all.filter { cut -> cut.fix == null && toolWindows.none { it.holds(cut.node.component) } }
        val screen = ScreenUtil.getScreenRectangle(window)
        val windowWide = if (window is IdeFrame) null else sizedWindowFix(window.width, screen.width, loose)
        val cuts = all.map { cut ->
            val tw = toolWindows.firstOrNull { it.holds(cut.node.component) }
            Triple(cut, cut.fix ?: tw?.step ?: windowWide ?: windowFix(window), tw)
        }
        for ((fix, group) in cuts.groupBy { it.second }) {
            val tw = group.first().third
            val how = when {
                fix == null -> windowStep(window)
                fix == windowFix(window) && tw == null -> windowStep(window)
                else -> "$fix makes room"
            }
            problems += Problem("layout: ${group.joinToString("; ") { it.first.what }}; $how", fix, tw?.id, screenArea(group.map { it.first.node.component }))
        }
        return problems
    }

    /** The screen area [components] cover, of those that show, or null when none does. */
    private fun screenArea(components: List<Component>): Rectangle? =
        components.filter { it.isShowing }.map { Rectangle(it.locationOnScreen, it.size) }.reduceOrNull { a, b -> a.union(b) }

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
