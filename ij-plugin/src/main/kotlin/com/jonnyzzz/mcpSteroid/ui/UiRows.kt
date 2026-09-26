/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ui.SimpleColoredComponent
import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.text.JTextComponent

/** One row of a list, tree or table as a snapshot shows it. [expanded] is null for a list row or a tree leaf. */
data class UiRow(val index: Int, val text: String, val depth: Int, val selected: Boolean, val expanded: Boolean?)

/** The rows a snapshot lists for a component: the ones in view, and how many rows it has in all. */
data class UiRowsView(val rows: List<UiRow>, val total: Int)

/**
 * The rows of lists, trees, tables and combo boxes, read through their cell renderers, as a user sees them. A
 * tree's rows are its expanded rows. Call on the EDT.
 */
object UiRows {
    private const val MAX_ROWS = 2_000
    const val MAX_SHOWN = 40
    const val PATH_SEPARATOR = " > "

    fun rows(c: Component): List<String>? = when (c) {
        is JList<*> -> (0 until minOf(c.model.size, MAX_ROWS)).map { listRow(c, it) }
        is JTree -> (0 until minOf(c.rowCount, MAX_ROWS)).map { treeRow(c, it) }
        is JTable -> (0 until minOf(c.rowCount, MAX_ROWS)).map { tableRow(c, it) }
        is JComboBox<*> -> (0 until minOf(c.itemCount, MAX_ROWS)).map { comboRow(c, it) }
        else -> null
    }

    /**
     * The rows of a list, tree or table in its view, at most [MAX_SHOWN], with their depth and state. Null for other
     * components, and for one with no rows.
     */
    fun view(c: Component): UiRowsView? {
        val total = when (c) {
            is JList<*> -> c.model.size
            is JTree -> c.rowCount
            is JTable -> c.rowCount
            else -> return null
        }
        if (total == 0) return null
        val range = visibleRange(c, total)
        val shown = (range.first..minOf(range.last, range.first + MAX_SHOWN - 1)).map { i -> row(c, i) }
        return UiRowsView(shown, total)
    }

    /**
     * The row [wanted] names in [c]: the rows whose text is [wanted], else those that contain it. In a tree,
     * `A > B > C` names a row by its path. Several matching rows are an error that lists them by index.
     */
    fun find(c: Component, rows: List<String>, wanted: String): Int {
        val labels = if (c is JTree && PATH_SEPARATOR in wanted) rows.indices.map { treePath(c, it) } else rows
        val exact = labels.indices.filter { labels[it] == wanted }
        val candidates = exact.ifEmpty { labels.indices.filter { labels[it].contains(wanted) } }
        return when (candidates.size) {
            0 -> -1
            1 -> candidates.single()
            else -> throw UiStepFailure(
                "${candidates.size} rows match \"$wanted\"; pass \"index\" or a longer row text: " +
                    candidates.take(10).joinToString("; ") { "#$it ${if (c is JTree) treePath(c, it) else rows[it]}" }
            )
        }
    }

    /** Selects row [index] of [c] as a user's selection does, without clicking it, and scrolls it into view. */
    fun select(c: Component, index: Int) {
        when (c) {
            is JList<*> -> {
                c.selectedIndex = index
                c.ensureIndexIsVisible(index)
            }
            is JTree -> {
                c.setSelectionRow(index)
                c.scrollRowToVisible(index)
            }
            is JTable -> {
                c.setRowSelectionInterval(index, index)
                c.scrollRectToVisible(c.getCellRect(index, 0, true))
            }
            is JComboBox<*> -> c.selectedIndex = index
            else -> throw UiStepFailure("${UiComponentFacts.simpleClassName(c)} has no rows")
        }
    }

    /** Whether row [index] of [c] is selected. */
    fun isSelected(c: Component, index: Int): Boolean = when (c) {
        is JList<*> -> c.isSelectedIndex(index)
        is JTree -> c.isRowSelected(index)
        is JTable -> c.isRowSelected(index)
        is JComboBox<*> -> c.selectedIndex == index
        else -> false
    }

    /** A tree row's text with its ancestors', such as `Editor > General > Appearance`. The root is left out when hidden. */
    fun treePath(tree: JTree, row: Int): String {
        val path = tree.getPathForRow(row) ?: return treeRow(tree, row)
        return generateSequence(path) { it.parentPath }
            .map { tree.getRowForPath(it) }
            .takeWhile { it >= 0 }
            .toList()
            .reversed()
            .joinToString(PATH_SEPARATOR) { treeRow(tree, it) }
    }

    private fun row(c: Component, i: Int): UiRow = when (c) {
        is JList<*> -> UiRow(i, listRow(c, i), 0, c.isSelectedIndex(i), null)
        is JTree -> {
            val path = c.getPathForRow(i)
            val leaf = path?.let { c.model.isLeaf(it.lastPathComponent) } ?: true
            val depth = (path?.pathCount ?: 1) - if (c.isRootVisible) 1 else 2
            UiRow(i, treeRow(c, i), depth.coerceAtLeast(0), c.isRowSelected(i), if (leaf) null else c.isExpanded(i))
        }
        is JTable -> UiRow(i, tableRow(c, i), 0, c.isRowSelected(i), null)
        else -> error("no rows in ${c.javaClass.name}")
    }

    /** The rows in the component's visible rectangle, or all of them while it has no size. */
    private fun visibleRange(c: Component, total: Int): IntRange {
        val rect = (c as? javax.swing.JComponent)?.visibleRect
        if (rect == null || rect.isEmpty) return 0 until total
        val top = Point(rect.x, rect.y)
        val bottom = Point(rect.x, rect.y + rect.height - 1)
        val (first, last) = when (c) {
            is JList<*> -> c.locationToIndex(top) to c.locationToIndex(bottom)
            is JTree -> c.getClosestRowForLocation(top.x, top.y) to c.getClosestRowForLocation(bottom.x, bottom.y)
            is JTable -> c.rowAtPoint(top).let { if (it < 0) 0 else it } to c.rowAtPoint(bottom).let { if (it < 0) total - 1 else it }
            else -> 0 to total - 1
        }
        return first.coerceIn(0, total - 1)..last.coerceIn(0, total - 1)
    }

    @Suppress("UNCHECKED_CAST")
    private fun listRow(list: JList<*>, i: Int): String {
        val value = (list as JList<Any?>).model.getElementAt(i)
        val shown = list.cellRenderer?.getListCellRendererComponent(list, value, i, false, false)
        return shown?.let(::text) ?: value.toString()
    }

    private fun treeRow(tree: JTree, row: Int): String {
        val value = tree.getPathForRow(row)?.lastPathComponent ?: return ""
        val leaf = tree.model.isLeaf(value)
        val shown = tree.cellRenderer?.getTreeCellRendererComponent(tree, value, false, tree.isExpanded(row), leaf, row, false)
        return shown?.let(::text) ?: tree.convertValueToText(value, false, tree.isExpanded(row), leaf, row, false)
    }

    private fun tableRow(table: JTable, row: Int): String {
        val value = table.getValueAt(row, 0)
        val shown = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0)
        return text(shown) ?: value?.toString().orEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun comboRow(combo: JComboBox<*>, i: Int): String {
        val item = combo.getItemAt(i)
        val shown = try {
            (combo as JComboBox<Any?>).renderer?.getListCellRendererComponent(JList<Any?>(), item, i, false, false)?.let(::text)
        } catch (e: RuntimeException) {
            // A renderer may expect the combo box's own popup list; the item's text still serves.
            null
        }
        return shown ?: UiComponentFacts.clean(item.toString())
    }

    /** The text a renderer component shows: its label or colored text, else its accessible name. */
    fun text(c: Component): String? {
        val own = when (c) {
            is SimpleColoredComponent -> c.getCharSequence(false).toString()
            is JLabel -> c.text
            is JTextComponent -> c.text
            is Container -> c.components.asSequence().mapNotNull(::text).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { null }
            else -> null
        }
        val cleaned = own?.let(UiComponentFacts::clean)?.takeIf { it.isNotEmpty() }
        return cleaned ?: c.accessibleContext?.accessibleName?.let(UiComponentFacts::clean)?.takeIf { it.isNotEmpty() }
    }
}
