/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.intellij.ui.JBSplitter
import com.intellij.ui.treeStructure.treetable.TreeTable
import java.awt.Component
import java.awt.Dimension
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JViewport
import kotlin.math.abs

/**
 * The splitters a person drags to give one pane more room: the IDE's `Splitter` family (`JBSplitter`,
 * `OnePixelSplitter`, the editor's split panes), `ThreeComponentsSplitter` (tool window areas, the debugger's grid)
 * and Swing's `JSplitPane`. Sizes are in logical pixels along the splitter's axis: the width of side-by-side panes, the
 * height of stacked ones. Call on the EDT.
 */
object UiSplitters {
    /** Which size of its panes a splitter shares out. */
    enum class Axis { WIDTH, HEIGHT }

    /** A pane of [splitter]: [child], the component it lays out, which holds the control a step named. */
    class Pane(val splitter: JComponent, val child: Component, val axis: Axis)

    /** What a change did: the pane's size and the first pane's share before and after, and what held it back. */
    data class Result(val before: Int, val after: Int, val proportionBefore: Double, val proportionAfter: Double, val heldBack: String?)

    /** Pixels of rounding that do not count as cut. */
    private const val SLACK = 2
    /** How far from the proportion asked a splitter may land before the report says it held back. */
    private const val CLAMP_TOLERANCE = 0.01

    fun isSplitter(c: Component): Boolean = c is Splitter || c is ThreeComponentsSplitter || c is JSplitPane

    fun axisOf(splitter: Component): Axis = when (splitter) {
        is Splitter -> if (splitter.orientation) Axis.HEIGHT else Axis.WIDTH
        is ThreeComponentsSplitter -> if (splitter.orientation) Axis.HEIGHT else Axis.WIDTH
        is JSplitPane -> if (splitter.orientation == JSplitPane.VERTICAL_SPLIT) Axis.HEIGHT else Axis.WIDTH
        else -> throw IllegalArgumentException("${splitter.javaClass.name} is not a splitter")
    }

    /** A splitter's state as a snapshot shows it, such as `horizontal 0.25`, or null for any other component. */
    fun describe(c: Component): String? {
        if (!isSplitter(c)) return null
        val way = if (axisOf(c) == Axis.HEIGHT) "vertical" else "horizontal"
        return when (c) {
            is ThreeComponentsSplitter -> "$way first ${c.firstSize} px, last ${c.lastSize} px"
            else -> "$way ${format(proportion(c as JComponent))}"
        }
    }

    /**
     * The pane that holds [c]: the child of the nearest splitter above [c] that [c] lies in, or with [axis], of the
     * nearest splitter along that axis. A splitter itself stands for its first pane. Null when none holds it.
     */
    fun paneOf(c: Component, axis: Axis? = null): Pane? {
        if (isSplitter(c) && (axis == null || axisOf(c) == axis)) panes(c as JComponent).firstOrNull()?.let { return Pane(c, it, axisOf(c)) }
        var child = c
        var parent = c.parent
        while (parent != null) {
            if (isSplitter(parent) && (axis == null || axisOf(parent) == axis) && child in panes(parent as JComponent)) return Pane(parent, child, axisOf(parent))
            child = parent
            parent = parent.parent
        }
        return null
    }

    /** The panes of [splitter] that show, in order. */
    private fun panes(splitter: JComponent): List<Component> = when (splitter) {
        is Splitter -> listOfNotNull(splitter.firstComponent, splitter.secondComponent)
        is ThreeComponentsSplitter -> listOfNotNull(splitter.firstComponent, splitter.innerComponent, splitter.lastComponent)
        is JSplitPane -> listOfNotNull(splitter.leftComponent, splitter.rightComponent)
        else -> emptyList()
    }.filter { it.isVisible }

    /** The axes along which [c]'s content is cut: its rows or text need more than its view shows. Height first. */
    fun cutAxes(c: Component): List<Axis> = Axis.entries.reversed().filter { shortfall(c, it) > SLACK }

    /**
     * How many pixels [c]'s content needs beyond what it shows along [axis]: a list, tree or table in a scroll pane
     * against the pane's view, a tree table's tree against its tree column, any other control against its own size.
     */
    fun shortfall(c: Component, axis: Axis): Int {
        if (c is TreeTable && axis == Axis.WIDTH && c.columnCount > 0) {
            return c.tree.preferredSize.width - c.columnModel.getColumn(0).width
        }
        val port = (c as? JScrollPane)?.viewport ?: c.parent as? JViewport
        val need = along(axis, (port?.view ?: c).preferredSize)
        val shown = along(axis, port?.extentSize ?: c.size)
        return need - shown
    }

    fun size(p: Pane): Int = along(p.axis, p.child.size)

    /**
     * The size that shows [p]'s content whole, within what the other panes' minimum sizes leave: its size now plus what
     * [cut], the control a step named, lacks along the axis, or without it, the pane's preferred size.
     */
    fun fitSize(p: Pane, cut: Component? = null): Int {
        val wanted = cut?.let { size(p) + shortfall(it, p.axis).coerceAtLeast(0) } ?: along(p.axis, p.child.preferredSize)
        return minOf(wanted, room(p)).coerceAtLeast(0)
    }

    /** The most [p] can get: the splitter's room less the other panes' minimum sizes. */
    private fun room(p: Pane): Int = total(p.splitter) - others(p).sumOf { along(p.axis, it.minimumSize) }

    private fun others(p: Pane): List<Component> = panes(p.splitter as JComponent).filter { it !== p.child }

    /**
     * Whether the other panes of [p] now show less than they want, as a pane that gave its room to [p] does: then only
     * a larger window gives both room. The pixels they lack, or 0.
     */
    fun othersShort(p: Pane): Int = others(p).sumOf { (along(p.axis, it.preferredSize) - along(p.axis, it.size)).coerceAtLeast(0) }

    /** Gives [p] [px] pixels by moving its splitter's divider, as a drag does. */
    fun setSize(p: Pane, px: Int): Result {
        val s = p.splitter
        val before = size(p)
        val proportionBefore = proportion(s)
        val total = total(s)
        when (s) {
            is Splitter -> {
                val share = px.toDouble() / total
                s.proportion = (if (p.child === s.firstComponent) share else 1 - share).coerceIn(0.0, 1.0).toFloat()
            }
            is ThreeComponentsSplitter -> when (p.child) {
                s.firstComponent -> s.firstSize = px
                s.lastComponent -> s.lastSize = px
                // The inner pane takes what the others leave: the last one gives, or the first when there is no last.
                else -> if (s.lastComponent?.isVisible == true) s.lastSize = total - s.firstSize - px else s.firstSize = total - s.lastSize - px
            }
            is JSplitPane -> s.dividerLocation = if (p.child === s.leftComponent) px else along(p.axis, s.size) - px - s.dividerSize
        }
        s.doLayout()
        val after = size(p)
        val held = when {
            s is Splitter && abs(after - px) > SLACK -> clampNote(s)
            abs(after - px) > SLACK -> "the other panes keep their minimum sizes"
            else -> null
        }
        return Result(before, after, proportionBefore, proportion(s), held)
    }

    /** Gives the first pane of [s] the share [share], as a drag does. */
    fun setProportion(s: JComponent, share: Double): Result {
        val first = panes(s).firstOrNull() ?: throw UiStepFailure("the splitter has no pane")
        val pane = Pane(s, first, axisOf(s))
        val before = size(pane)
        val proportionBefore = proportion(s)
        when (s) {
            is Splitter -> s.proportion = share.toFloat()
            is ThreeComponentsSplitter -> s.firstSize = (share * total(s)).toInt()
            is JSplitPane -> s.setDividerLocation(share)
        }
        s.doLayout()
        val after = proportion(s)
        val held = if (abs(after - share) > CLAMP_TOLERANCE) (if (s is Splitter) clampNote(s) else "the other panes keep their minimum sizes") else null
        return Result(before, size(pane), proportionBefore, after, held)
    }

    /** The key a `JBSplitter` saves its proportion under for the next opening, or null when it saves none. */
    fun savedKey(s: Component): String? = (s as? JBSplitter)?.splitterProportionKey?.takeIf { it.isNotEmpty() }

    /** The first pane's share of [s]. */
    fun proportion(s: JComponent): Double = when (s) {
        is Splitter -> s.proportion.toDouble()
        is ThreeComponentsSplitter -> total(s).takeIf { it > 0 }?.let { s.firstSize.toDouble() / it } ?: 0.0
        is JSplitPane -> total(s).takeIf { it > 0 }?.let { s.dividerLocation.toDouble() / it } ?: 0.0
        else -> 0.0
    }

    fun format(share: Double): String = String.format(Locale.ROOT, "%.2f", share)

    private fun clampNote(s: Splitter) = "the splitter keeps its first pane between ${format(s.minimumProportion.toDouble())} and ${format(s.maximumProportion.toDouble())}"

    /** The room [s] shares out along its axis: its size less its dividers. */
    private fun total(s: Component): Int = when (s) {
        is Splitter -> along(axisOf(s), s.size) - s.dividerWidth
        is ThreeComponentsSplitter -> along(axisOf(s), s.size) - s.dividerWidth * (panes(s).size - 1).coerceAtLeast(0)
        is JSplitPane -> along(axisOf(s), s.size) - s.dividerSize
        else -> 0
    }

    private fun along(axis: Axis, d: Dimension): Int = if (axis == Axis.HEIGHT) d.height else d.width
}
