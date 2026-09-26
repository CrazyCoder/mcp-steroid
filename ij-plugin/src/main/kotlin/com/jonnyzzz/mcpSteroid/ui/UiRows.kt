/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ui.SimpleColoredComponent
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.text.JTextComponent

/** The rows of lists, trees and tables, read through their cell renderers, as a user sees them. Call on the EDT. */
object UiRows {
    private const val MAX_ROWS = 2_000

    fun rows(c: Component): List<String>? = when (c) {
        is JList<*> -> listRows(c)
        is JTree -> treeRows(c)
        is JTable -> tableRows(c)
        else -> null
    }

    /** The index of the row whose text is [wanted], else of the first row that contains it, else -1. */
    fun indexOf(rows: List<String>, wanted: String): Int {
        val exact = rows.indexOf(wanted)
        return if (exact >= 0) exact else rows.indexOfFirst { it.contains(wanted) }
    }

    /** Scrolls row [index] into view and returns its bounds in the component's coordinates. */
    fun reveal(c: Component, index: Int): Rectangle? = when (c) {
        is JList<*> -> c.ensureIndexIsVisible(index).let { c.getCellBounds(index, index) }
        is JTree -> c.scrollRowToVisible(index).let { c.getRowBounds(index) }
        is JTable -> c.getCellRect(index, 0, true).also { c.scrollRectToVisible(it) }
        else -> null
    }

    fun rowBounds(c: Component, index: Int): Rectangle? = when (c) {
        is JList<*> -> c.getCellBounds(index, index)
        is JTree -> c.getRowBounds(index)
        is JTable -> c.getCellRect(index, 0, true)
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun listRows(list: JList<*>): List<String> {
        val renderer = (list as JList<Any?>).cellRenderer
        return (0 until minOf(list.model.size, MAX_ROWS)).map { i ->
            val value = list.model.getElementAt(i)
            val shown = renderer?.getListCellRendererComponent(list, value, i, false, false)
            shown?.let(::text) ?: value.toString()
        }
    }

    private fun treeRows(tree: JTree): List<String> = (0 until minOf(tree.rowCount, MAX_ROWS)).map { row ->
        val path = tree.getPathForRow(row)
        val value = path.lastPathComponent
        val shown = tree.cellRenderer?.getTreeCellRendererComponent(
            tree, value, false, tree.isExpanded(row), tree.model.isLeaf(value), row, false,
        )
        shown?.let(::text) ?: tree.convertValueToText(value, false, tree.isExpanded(row), tree.model.isLeaf(value), row, false)
    }

    private fun tableRows(table: JTable): List<String> = (0 until minOf(table.rowCount, MAX_ROWS)).map { row ->
        val value = table.getValueAt(row, 0)
        val shown = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0)
        text(shown) ?: value?.toString().orEmpty()
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
