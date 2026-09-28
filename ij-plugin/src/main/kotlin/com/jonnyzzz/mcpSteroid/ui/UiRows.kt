/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.popup.PopupFactoryImpl
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.Rectangle
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.ComboPopup
import javax.swing.text.JTextComponent
import javax.swing.tree.TreePath

/**
 * One row of a list, tree, table or tabbed pane as a snapshot shows it. [expanded] is null for a list row or a tree
 * leaf; [cells] are a table row's cells after its first, which [text] holds. [action] is the id of the IDE action a
 * popup row runs, as in Refactor This or Generate, which a run step takes.
 */
data class UiRow(
    val index: Int,
    val text: String,
    val depth: Int,
    val selected: Boolean,
    val expanded: Boolean?,
    val cells: List<String> = emptyList(),
    val action: String? = null,
)

/** The rows a snapshot lists for a component: the ones in view, and how many rows it has in all. */
data class UiRowsView(val rows: List<UiRow>, val total: Int)

/**
 * The rows of lists, trees, tables and combo boxes, read through their cell renderers, as a user sees them, and the
 * tabs of a tabbed pane. A tree's rows are its expanded rows. Call on the EDT.
 */
object UiRows {
    private const val MAX_ROWS = 2_000
    private const val MAX_SHOWN = 40
    const val PATH_SEPARATOR = " > "

    fun rows(c: Component): List<String>? = when (c) {
        is JList<*> -> (0 until minOf(c.model.size, MAX_ROWS)).map { listRow(c, it) }
        is JTree -> (0 until minOf(c.rowCount, MAX_ROWS)).map { treeRow(c, it) }
        is JTable -> (0 until minOf(c.rowCount, MAX_ROWS)).map { tableRow(c, it) }
        is JComboBox<*> -> (0 until minOf(c.itemCount, MAX_ROWS)).map { comboRow(c, it) }
        is JTabbedPane -> (0 until c.tabCount).map { tabRow(c, it) }
        else -> null
    }

    /**
     * Where row [index] of [c] is, in [c]'s coordinates: a list's cell, a tree row's node, a table row's first cell, a
     * tab. Null for a combo box, whose items show in a popup, and for a row out of range.
     */
    fun bounds(c: Component, index: Int): Rectangle? = when (c) {
        is JList<*> -> c.getCellBounds(index, index)
        is JTree -> c.getRowBounds(index)
        is JTable -> if (index in 0 until c.rowCount) c.getCellRect(index, 0, true) else null
        is JTabbedPane -> if (index in 0 until c.tabCount) c.getBoundsAt(index) else null
        else -> null
    }

    /** The combo box whose open popup shows [list], or null when [list] is not a combo box's popup list. */
    fun comboOf(list: Component): JComboBox<*>? {
        if (list !is JList<*>) return null
        val popup = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, list) as? JPopupMenu ?: return null
        return (popup as? ComboPopup)?.let { popup.invoker as? JComboBox<*> }
    }

    /**
     * The rows of a list, tree, table or tabbed pane in its view, at most [max], with their depth and state. Null for
     * other components, and for one with no rows.
     */
    fun view(c: Component, max: Int = MAX_SHOWN): UiRowsView? {
        val total = when (c) {
            is JList<*> -> c.model.size
            is JTree -> c.rowCount
            is JTable -> c.rowCount
            is JTabbedPane -> c.tabCount
            else -> return null
        }
        if (total == 0) return null
        val range = visibleRange(c, total)
        val shown = (range.first..minOf(range.last, range.first + max - 1)).map { i -> row(c, i) }
        return UiRowsView(shown, total)
    }

    /**
     * The row [wanted] names in [c]: the rows whose text is [wanted], else those that contain it. In a tree,
     * `A > B > C` names a row by its path. Several matching rows are an error that lists them by index.
     */
    fun find(c: Component, rows: List<String>, wanted: String): Int {
        val candidates = if (c is JTree && PATH_SEPARATOR in wanted) {
            val want = wanted.split(PATH_SEPARATOR).map { it.trim() }
            val paths = rows.indices.map { treeSegments(c, it) }
            paths.indices.filter { paths[it] == want }
                .ifEmpty { paths.indices.filter { pathEndsWith(paths[it], want) { have, w -> have.startsWith("$w ") } } }
                .ifEmpty { paths.indices.filter { pathEndsWith(paths[it], want) { have, w -> have.contains(w) } } }
        } else {
            rows.indices.filter { rows[it] == wanted }.ifEmpty { rows.indices.filter { rows[it].contains(wanted) } }
        }
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
            is JComboBox<*> -> {
                c.selectedIndex = index
                c.hidePopup()
            }
            is JTabbedPane -> c.selectedIndex = index
            else -> throw UiStepFailure("${UiComponentFacts.simpleClassName(c)} has no rows")
        }
    }

    /** Scrolls row [index] of [c] into view without selecting it. */
    fun scrollTo(c: Component, index: Int) {
        when (c) {
            is JList<*> -> c.ensureIndexIsVisible(index)
            is JTree -> c.scrollRowToVisible(index)
            else -> bounds(c, index)?.let { (c as? JComponent)?.scrollRectToVisible(it) }
        }
    }

    /** Whether row [index] of [c] is selected. */
    fun isSelected(c: Component, index: Int): Boolean = when (c) {
        is JList<*> -> c.isSelectedIndex(index)
        is JTree -> c.isRowSelected(index)
        is JTable -> c.isRowSelected(index)
        is JComboBox<*> -> c.selectedIndex == index
        is JTabbedPane -> c.selectedIndex == index
        else -> false
    }

    /**
     * The row of the child of [parent] that shows [segment]: its text exactly, else the start of it followed by more,
     * such as a location, else part of it; -1 when no child matches. Without a parent, any row counts. Several
     * matching rows are an error that lists them.
     */
    fun childRow(tree: JTree, parent: TreePath?, segment: String): Int {
        val children = (0 until tree.rowCount).filter { parent == null || tree.getPathForRow(it)?.parentPath == parent }
        val candidates = children.filter { treeRow(tree, it) == segment }
            .ifEmpty { children.filter { treeRow(tree, it).startsWith("$segment ") } }
            .ifEmpty { children.filter { treeRow(tree, it).contains(segment) } }
        return when (candidates.size) {
            0 -> -1
            1 -> candidates.single()
            else -> throw UiStepFailure(
                "${candidates.size} rows match \"$segment\"; pass \"index\" or a longer path: " +
                    candidates.take(10).joinToString("; ") { "#$it ${treePath(tree, it)}" }
            )
        }
    }

    /** The children of [parent] in view, as `#index text`, for a message about a child that is not there. */
    fun childRows(tree: JTree, parent: TreePath): List<String> =
        (0 until tree.rowCount).filter { tree.getPathForRow(it)?.parentPath == parent }.map { "#$it ${treeRow(tree, it)}" }

    /** A tree row's text with its ancestors', such as `Editor > General > Appearance`. The root is left out when hidden. */
    fun treePath(tree: JTree, row: Int): String = treeSegments(tree, row).joinToString(PATH_SEPARATOR)

    private fun treeSegments(tree: JTree, row: Int): List<String> {
        val path = tree.getPathForRow(row) ?: return listOf(treeRow(tree, row))
        return generateSequence(path) { it.parentPath }
            .map { tree.getRowForPath(it) }
            .takeWhile { it >= 0 }
            .toList()
            .reversed()
            .map { treeRow(tree, it) }
    }

    /**
     * Whether a row's [path] ends with the [wanted] segments, each its row's text or a text that [loose] accepts. A
     * row's text can carry more than its name, such as the Project view's root `mcp C:\work\mcp`, so a segment
     * that starts the text is tried before one that is only part of it.
     */
    private fun pathEndsWith(path: List<String>, wanted: List<String>, loose: (String, String) -> Boolean): Boolean =
        wanted.size <= path.size && path.takeLast(wanted.size).zip(wanted).all { (have, want) -> have == want || loose(have, want) }

    private fun row(c: Component, i: Int): UiRow = when (c) {
        is JList<*> -> UiRow(i, listRow(c, i), 0, c.isSelectedIndex(i), null,
            action = (c.model.getElementAt(i) as? PopupFactoryImpl.ActionItem)?.let { ActionManager.getInstance().getId(it.action) })
        is JTree -> {
            val path = c.getPathForRow(i)
            val leaf = path?.let { c.model.isLeaf(it.lastPathComponent) } ?: true
            val depth = (path?.pathCount ?: 1) - if (c.isRootVisible) 1 else 2
            UiRow(i, treeRow(c, i), depth.coerceAtLeast(0), c.isRowSelected(i), if (leaf) null else c.isExpanded(i))
        }
        is JTable -> UiRow(i, tableRow(c, i), 0, c.isRowSelected(i), null, cells(c, i))
        is JTabbedPane -> UiRow(i, tabRow(c, i), 0, c.selectedIndex == i, null)
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

    /**
     * The cells of a table row after its first, as the user sees them: the values beside the names of a Code Style
     * or registry table. Empty for a row out of range and for a component that is not a table.
     */
    fun cells(c: Component, row: Int): List<String> {
        val table = c as? JTable ?: return emptyList()
        if (row !in 0 until table.rowCount) return emptyList()
        return (1 until table.columnCount).map { column -> cell(table, row, column) }
    }

    private fun cell(table: JTable, row: Int, column: Int): String {
        val shown = runCatching { table.prepareRenderer(table.getCellRenderer(row, column), row, column) }.getOrNull()
        // A checkbox cell shows no text, only its state.
        if (shown is AbstractButton && shown.text.isNullOrBlank()) return if (shown.isSelected) "[x]" else "[ ]"
        return shown?.let(::text) ?: table.getValueAt(row, column)?.toString().orEmpty()
    }

    private fun tableRow(table: JTable, row: Int): String {
        val value = table.getValueAt(row, 0)
        val shown = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0)
        return text(shown) ?: value?.toString().orEmpty()
    }

    /** A tab's title, else the text of the component shown as its tab, such as a label with a counter. */
    private fun tabRow(tabs: JTabbedPane, i: Int): String =
        tabs.getTitleAt(i)?.let(UiComponentFacts::clean)?.takeIf { it.isNotEmpty() }
            ?: tabs.getTabComponentAt(i)?.let(::text)
            ?: ""

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
