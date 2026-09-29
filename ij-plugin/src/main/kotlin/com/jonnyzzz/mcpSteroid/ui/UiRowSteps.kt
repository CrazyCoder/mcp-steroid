/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath
import kotlin.time.TimeSource

/** Row [pick] of [node] scrolled into view, with where it is: the row's area in the component and how to name it. */
internal class RowArea(val area: Rectangle, val label: String)

/** The steps on rows: select a row, scroll to a control or a row, and find a tree path or a tab. */
internal class UiRowSteps(private val ctx: UiStepContext, private val finder: UiHighlightFinder) {
    private val edtAny get() = ctx.edtAny
    private suspend fun resolve(target: UiTarget, timeoutMs: Long, requireEnabled: Boolean) = ctx.resolve(target, timeoutMs, requireEnabled)
    private suspend fun pickRow(node: UiNode, step: UiStep) = ctx.pickRow(node, step)
    private fun describe(node: UiNode) = ctx.describe(node)

    /**
     * Selects a row through the component's selection, as the keyboard does. A click would also activate the row in
     * a list that acts on a click, such as Find Action's results, and a combo box would need its popup opened.
     */
    suspend fun select(given: UiStep): String {
        // A JetBrains Client's Inspections page is the backend's, whose profile names the rows: the backend selects.
        given.inspection?.let { name -> finder.backendInspectionSelect(name)?.let { return it } }
        val step = given.inspection?.let { given.copy(target = UiTarget(cls = INSPECTIONS_TREE), row = finder.inspectionPath(it), inspection = null) } ?: given
        val found = resolve(step.target!!, step.timeoutMs, requireEnabled = true)
        // An open combo box popup's list shows the combo box's items: selecting in the list alone would not pick one.
        val node = withContext(edtAny) { UiRows.comboOf(found.component)?.let { FallbackUiWalker().leaf(it) } } ?: found
        val host = node.component
        val pick = pickRow(node, step)!!
        return withContext(edtAny) {
            UiRows.select(host, pick.index)
            if (!UiRows.isSelected(host, pick.index)) throw UiStepFailure("${describe(node)} did not take the selection of row #${pick.index}")
            pick.expandedNote() + "selected row #${pick.index} \"${pick.text}\" in ${describe(node)}"
        }
    }

    /**
     * [step] aimed at a tab when it clicks a tabbed pane without a row: the tab its name or text names. A tabbed pane's
     * name is its selected tab's title, so a click by that name means the tab, not the middle of the pane's content.
     */
    fun tabOf(node: UiNode, step: UiStep): UiStep {
        if (node.component !is JTabbedPane && node.component !is com.intellij.ui.tabs.JBTabs || step.row != null || step.index != null) return step
        val tab = step.target?.name ?: step.target?.text
            ?: throw UiStepFailure("${describe(node)} is clicked on a tab: pass \"row\" with the tab's title, or a row ref")
        return step.copy(row = tab)
    }

    /** The row a click or hover step names, scrolled into view, or null when it names none. */
    suspend fun rowArea(node: UiNode, step: UiStep): RowArea? {
        val pick = pickRow(node, step) ?: return null
        return withContext(edtAny) {
            val c = node.component
            val area = UiRows.bounds(c, pick.index)
                ?: throw UiStepFailure("${describe(node)} shows its items in a popup: pick one with select")
            UiRows.scrollTo(c, pick.index)
            RowArea(area, pick.expandedNote() + "row #${pick.index} \"${pick.text.take(80)}\"")
        }
    }

    /**
     * Brings the target, or its row, into view, as a user scrolls to it; or with "pages", scrolls the scroll pane
     * around the target by that many pages, down when positive. The report says what part of the content shows.
     */
    suspend fun scroll(step: UiStep): String {
        val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
        val c = node.component
        val pages = step.pages
        step.align?.let { align ->
            val pick = pickRow(node, step)
            return withContext(edtAny) {
                val area = pick?.let { UiRows.bounds(c, it.index) ?: throw UiStepFailure("${describe(node)} shows its items in a popup: pick one with select") }
                    ?: Rectangle(0, 0, c.width, c.height)
                val what = pick?.let { "row #${it.index} \"${it.text.take(80)}\" of " }.orEmpty() + describe(node)
                // A control in no scroll pane shows where it is: the step reports that, and its bounds, which a JetBrains
                // Client's highlight on a host page reads.
                val port = UiScrollAlign.scroll(c, area, align)
                val moved = if (port == null) "$what is in no scroll pane, so it stays where it is"
                else "scrolled $what to the ${if (align == "top") "top" else "middle"} of its view; ${position(port)}"
                "$moved; ${UiScrollAlign.boundsNote(onScreen(c, area))}"
            }
        }
        if (pages == null) {
            val row = rowArea(node, step)
            return withContext(edtAny) {
                if (row == null) (c as? JComponent)?.scrollRectToVisible(Rectangle(0, 0, c.width, c.height))
                "scrolled ${row?.let { "${it.label} of " }.orEmpty()}${describe(node)} into view" + (viewport(c)?.let { "; ${position(it)}" }.orEmpty()) +
                    "; " + UiScrollAlign.boundsNote(onScreen(c, row?.area ?: Rectangle(0, 0, c.width, c.height)))
            }
        }
        return withContext(edtAny) {
            val port = viewport(c) ?: throw UiStepFailure("${describe(node)} is not in a scroll pane")
            val view = port.view ?: throw UiStepFailure("the scroll pane around ${describe(node)} shows nothing")
            val extent = port.extentSize
            val maxY = maxOf(0, view.height - extent.height)
            val y = (port.viewPosition.y.toLong() + pages.toLong() * extent.height).coerceIn(0, maxY.toLong()).toInt()
            port.viewPosition = Point(port.viewPosition.x, y)
            "scrolled ${UiComponentFacts.simpleClassName(port.parent ?: port)} by $pages page(s); ${position(port)}"
        }
    }

    /** The viewport of the scroll pane that holds [c], or [c]'s own when it is a scroll pane. EDT. */
    private fun viewport(c: Component): JViewport? =
        (c as? JScrollPane)?.viewport ?: SwingUtilities.getAncestorOfClass(JViewport::class.java, c) as? JViewport

    /** Which part of a viewport's content shows, such as `showing 600-1200 of 2400 px, the bottom`. EDT. */
    private fun position(port: JViewport): String {
        val view = port.view ?: return "the scroll pane is empty"
        val top = port.viewPosition.y
        val bottom = top + port.extentSize.height
        val where = when {
            top <= 0 && bottom >= view.height -> "all of it"
            top <= 0 -> "the top"
            bottom >= view.height -> "the bottom"
            else -> "${top * 100 / maxOf(1, view.height)}% down"
        }
        return "showing $top-$bottom of ${view.height} px, $where"
    }

    /**
     * Finds `A > B > C` in [tree] one segment at a time, expanding each parent as a user would and waiting up to
     * [timeoutMs] for its children to load. The first segment may be any row in view.
     */
    suspend fun expandPath(tree: JTree, wanted: String, timeoutMs: Long): UiRowPick {
        val started = TimeSource.Monotonic.markNow()
        val segments = wanted.split(UiRows.PATH_SEPARATOR).map { it.trim() }
        val expanded = mutableListOf<String>()
        var parent: TreePath? = null
        for ((i, segment) in segments.withIndex()) {
            var row: Int
            while (true) {
                // An async tree model shows a "loading" child first, so wait for the row itself. Read the deadline
                // first: a busy EDT can run the expansion only after the time is up, and the look after it counts.
                val late = started.elapsedNow().inWholeMilliseconds >= timeoutMs
                row = withContext(edtAny) { UiRows.childRow(tree, parent, segment) }
                if (row >= 0 || late) break
                delay(POLL_MS)
            }
            if (row < 0) {
                val reached = segments.take(i).joinToString(UiRows.PATH_SEPARATOR)
                val children = withContext(edtAny) { parent?.let { UiRows.childRows(tree, it) } }
                throw UiStepFailure(
                    when {
                        parent == null -> "no row \"$segment\" in the tree's rows in view"
                        children.isNullOrEmpty() -> "\"$reached\" shows no children after $timeoutMs ms"
                        else -> "no row \"$segment\" under \"$reached\"; its rows: ${children.take(20).joinToString("; ")}"
                    }
                )
            }
            parent = withContext(edtAny) {
                val path = tree.getPathForRow(row)
                if (i < segments.lastIndex && !tree.isExpanded(path)) {
                    tree.expandPath(path)
                    expanded += UiRows.treePath(tree, row)
                }
                path
            }
        }
        return withContext(edtAny) {
            val row = tree.getRowForPath(parent)
            UiRowPick(row, UiRows.treePath(tree, row), expanded)
        }
    }
}
