/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class UiRowsTest {
    @Test
    fun `list rows are read through the renderer`() {
        val list = JList(arrayOf("a", "b")).apply {
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                    super.getListCellRendererComponent(list, "row $value", index, selected, focus)
            }
        }
        assertEquals(listOf("row a", "row b"), UiRows.rows(list))
    }

    @Test
    fun `tree rows are the visible rows`() {
        val root = DefaultMutableTreeNode("root").apply {
            add(DefaultMutableTreeNode("child one"))
            add(DefaultMutableTreeNode("child two"))
        }
        val tree = JTree(root)
        assertEquals(listOf("root", "child one", "child two"), UiRows.rows(tree))
    }

    @Test
    fun `table rows are the first column`() {
        val table = JTable(arrayOf(arrayOf<Any>("x", 1), arrayOf<Any>("y", 2)), arrayOf<Any>("name", "n"))
        assertEquals(listOf("x", "y"), UiRows.rows(table))
    }

    @Test
    fun `a row is found by exact text before substring`() {
        assertEquals(1, UiRows.indexOf(listOf("Editor Tabs", "Editor"), "Editor"))
        assertEquals(0, UiRows.indexOf(listOf("Editor Tabs", "Keymap"), "Tabs"))
        assertEquals(-1, UiRows.indexOf(listOf("Keymap"), "Editor"))
    }

    @Test
    fun `row bounds exist for list rows`() {
        val list = JList(arrayOf("a", "b")).apply { setSize(100, 100) }
        assertNotNull(UiRows.rowBounds(list, 1))
    }
}
