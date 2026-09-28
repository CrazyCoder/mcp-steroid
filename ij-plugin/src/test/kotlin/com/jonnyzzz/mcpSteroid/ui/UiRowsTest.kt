/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.ComboPopup
import javax.swing.tree.DefaultMutableTreeNode

class UiRowsTest {
    /** Trees repaint on selection and expansion, which IntelliJ's tree UI asserts happens on the EDT. */
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun inspectionsLike(): com.intellij.ui.treeStructure.treetable.TreeTable {
        val root = DefaultMutableTreeNode("root").apply {
            add(DefaultMutableTreeNode("Java").apply {
                add(DefaultMutableTreeNode("Probable bugs").apply { add(DefaultMutableTreeNode("Nullability problems")) })
            })
            add(DefaultMutableTreeNode("Kotlin"))
        }
        val model = com.intellij.ui.treeStructure.treetable.ListTreeTableModel(root, arrayOf(com.intellij.ui.treeStructure.treetable.TreeColumnInfo("Name")))
        return com.intellij.ui.treeStructure.treetable.TreeTable(model).apply { setRootVisible(false) }
    }

    @Test
    fun `a tree table's rows read as a tree's, with depth and expansion`() = onEdt {
        val table = inspectionsLike()
        assertEquals(listOf("Java", "Kotlin"), UiRows.rows(table))
        val view = UiRows.view(table)!!
        assertEquals(false, view.rows[0].expanded)
        assertEquals(null, view.rows[1].expanded)
        assertSame(table.tree, UiRows.treeOf(table))
    }

    @Test
    fun `a tree table path finds a row once its parents are expanded`() = onEdt {
        val table = inspectionsLike()
        val tree = UiRows.treeOf(table)!!
        tree.expandRow(0)
        tree.expandRow(1)
        val rows = UiRows.rows(table)!!
        val index = UiRows.find(table, rows, "Java > Probable bugs > Nullability problems")
        assertEquals("Nullability problems", rows[index])
        UiRows.select(table, index)
        assertTrue(UiRows.isSelected(table, index))
        assertEquals(table.getCellRect(index, 0, true), UiRows.bounds(table, index))
    }

    @Test
    fun `a row asked for by its path is named by its path, and by its text otherwise`() = onEdt {
        val table = inspectionsLike()
        UiRows.treeOf(table)!!.apply { expandRow(0); expandRow(1) }
        val rows = UiRows.rows(table)!!
        val index = UiRows.find(table, rows, "Java > Probable bugs > Nullability problems")
        assertEquals("Java > Probable bugs > Nullability problems", UiRows.named(table, rows, index, "Java > Probable bugs > Nullability problems"))
        assertEquals("Nullability problems", UiRows.named(table, rows, index, "Nullability"))
        assertEquals("Nullability problems", UiRows.named(table, rows, index, null))
    }

    @Test
    fun `a cell that paints only an icon is left out of the row`() = onEdt {
        val model = javax.swing.table.DefaultTableModel(arrayOf(arrayOf<Any>("Lossy encoding", com.intellij.util.ui.EmptyIcon.ICON_16, "x")), arrayOf("n", "i", "v"))
        val table = JTable(model)
        table.columnModel.getColumn(1).cellRenderer = javax.swing.table.TableCellRenderer { _, v, _, _, _, _ -> JLabel(v as javax.swing.Icon) }
        assertEquals(listOf("x"), UiRows.cells(table, 0))
    }

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
    fun `a table row's cells are its columns after the first, and a checkbox cell shows its state`() {
        val table = object : JTable(arrayOf(arrayOf<Any>("Hard wrap at:", 90, true), arrayOf<Any>("Wrap", 0, false)), arrayOf<Any>("name", "value", "on")) {
            override fun getColumnClass(column: Int): Class<*> = if (column == 2) java.lang.Boolean::class.java else Any::class.java
        }
        assertEquals(listOf("90", "[x]"), onEdt { UiRows.cells(table, 0) })
        assertEquals(listOf("0", "[ ]"), onEdt { UiRows.cells(table, 1) })
        assertEquals(emptyList<String>(), UiRows.cells(table, 5))
        assertEquals(emptyList<String>(), UiRows.cells(JLabel("x"), 0))
    }

    @Test
    fun `a tree path matches rows whose text carries more than the segment, and several matches fail`() {
        val root = DefaultMutableTreeNode("mcp  C:\\work\\mcp").apply {
            add(DefaultMutableTreeNode("a").apply { add(DefaultMutableTreeNode(".env")) })
            add(DefaultMutableTreeNode("b").apply { add(DefaultMutableTreeNode(".env")) })
            add(DefaultMutableTreeNode("ba").apply { add(DefaultMutableTreeNode(".env")) })
        }
        val tree = JTree(root)
        onEdt { var i = 0; while (i < tree.rowCount) tree.expandRow(i++) }
        val rows = onEdt { UiRows.rows(tree)!! }
        val bEnv = onEdt { UiRows.find(tree, rows, "mcp > b > .env") }
        assertEquals("mcp C:\\work\\mcp > b > .env", onEdt { UiRows.treePath(tree, bEnv) })
        assertEquals(bEnv, onEdt { UiRows.find(tree, rows, "b > .env") })
        assertEquals(-1, onEdt { UiRows.find(tree, rows, "mcp > c > .env") })
        assertEquals("a path names every level it spans", -1, onEdt { UiRows.find(tree, rows, "mcp > .env") })
        assertThrows(UiStepFailure::class.java) { onEdt { UiRows.find(tree, rows, "cp > a > .env") } }
    }

    @Test
    fun `a row is found by exact text before substring`() {
        val list = JList(arrayOf("Editor Tabs", "Editor", "Keymap"))
        assertEquals(1, UiRows.find(list, UiRows.rows(list)!!, "Editor"))
        assertEquals(2, UiRows.find(list, UiRows.rows(list)!!, "map"))
        assertEquals(-1, UiRows.find(list, UiRows.rows(list)!!, "Plugins"))
    }

    @Test
    fun `several matching rows are an error that lists them by index`() {
        val list = JList(arrayOf("Show Line Numbers", "Keymap", "Show line numbers: Settings"))
        val e = assertThrows(UiStepFailure::class.java) { UiRows.find(list, UiRows.rows(list)!!, "Show") }
        assertTrue(e.message, e.message!!.contains("#0 Show Line Numbers") && e.message!!.contains("#2 Show line numbers: Settings"))
    }

    @Test
    fun `a tree row is found by its path`() {
        val root = DefaultMutableTreeNode("root").apply {
            add(DefaultMutableTreeNode("Appearance & Behavior").apply { add(DefaultMutableTreeNode("Appearance")) })
            add(DefaultMutableTreeNode("Editor").apply { add(DefaultMutableTreeNode("Appearance")) })
        }
        onEdt {
            val tree = JTree(root).apply { isRootVisible = false; expandRow(0); expandRow(2) }
            val rows = UiRows.rows(tree)!!
            assertEquals(listOf("Appearance & Behavior", "Appearance", "Editor", "Appearance"), rows)
            assertEquals(3, UiRows.find(tree, rows, "Editor > Appearance"))
            assertThrows(UiStepFailure::class.java) { UiRows.find(tree, rows, "Appearance") }
        }
    }

    @Test
    fun `select sets the selection without a click`() {
        val list = JList(arrayOf("a", "b", "c"))
        var clicks = 0
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { clicks++ }
        })
        UiRows.select(list, 2)
        assertTrue(UiRows.isSelected(list, 2))
        assertEquals(0, clicks)
    }

    @Test
    fun `a combo box's items are its rows and select picks one`() {
        val combo = JComboBox(arrayOf("Absolute", "Relative", "Hybrid"))
        assertEquals(listOf("Absolute", "Relative", "Hybrid"), UiRows.rows(combo))
        UiRows.select(combo, 1)
        assertEquals("Relative", combo.selectedItem)
    }

    @Test
    fun `a tabbed pane's tabs are its rows, by title or tab component, and select switches the tab`() {
        onEdt {
            val tabs = JTabbedPane().apply {
                addTab("Marketplace", JPanel())
                addTab("", JPanel())
                setTabComponentAt(1, JLabel("Installed"))
            }
            assertEquals(listOf("Marketplace", "Installed"), UiRows.rows(tabs))
            UiRows.select(tabs, 1)
            assertEquals(1, tabs.selectedIndex)
            assertEquals(listOf(UiRow(0, "Marketplace", 0, false, null), UiRow(1, "Installed", 0, true, null)), UiRows.view(tabs)!!.rows)
        }
    }

    @Test
    fun `row bounds are in the component, and a combo box has none`() {
        onEdt {
            val list = JList(arrayOf("a", "b", "c")).apply { setSize(100, 300); fixedCellHeight = 20 }
            assertEquals(Rectangle(0, 20, 100, 20), UiRows.bounds(list, 1))
            val table = JTable(arrayOf(arrayOf<Any>("x", 1), arrayOf<Any>("y", 2)), arrayOf<Any>("name", "n")).apply { setSize(200, 100) }
            assertEquals(table.getCellRect(1, 0, true), UiRows.bounds(table, 1))
            assertNull(UiRows.bounds(table, 5))
            assertNull(UiRows.bounds(JComboBox(arrayOf("a")), 0))
        }
    }

    @Test
    fun `a child row is found under its parent, and a missing or ambiguous one is reported`() {
        val root = DefaultMutableTreeNode("root").apply {
            add(DefaultMutableTreeNode("Appearance & Behavior").apply { add(DefaultMutableTreeNode("General")) })
            add(DefaultMutableTreeNode("Editor").apply { add(DefaultMutableTreeNode("General")); add(DefaultMutableTreeNode("Code Style")) })
        }
        onEdt {
            val tree = JTree(root).apply { isRootVisible = false; expandRow(0); expandRow(2) }
            val editor = tree.getPathForRow(2)
            assertEquals(2, UiRows.childRow(tree, null, "Editor"))
            assertEquals(4, UiRows.childRow(tree, editor, "Code"))
            assertEquals(-1, UiRows.childRow(tree, editor, "Java"))
            assertEquals(listOf("#3 General", "#4 Code Style"), UiRows.childRows(tree, editor))
            val e = assertThrows(UiStepFailure::class.java) { UiRows.childRow(tree, null, "General") }
            assertTrue(e.message, e.message!!.contains("Appearance & Behavior > General") && e.message!!.contains("Editor > General"))
        }
    }

    @Test
    fun `an open combo box popup's list belongs to the combo box`() {
        onEdt {
            val combo = JComboBox(arrayOf("Always", "Never"))
            val popup = combo.ui.getAccessibleChild(combo, 0) as ComboPopup
            // Showing the popup sets its invoker; a headless test cannot show it.
            (popup as JPopupMenu).invoker = combo
            assertSame(combo, UiRows.comboOf(popup.list))
            assertNull(UiRows.comboOf(JList(arrayOf("a"))))
        }
    }

    @Test
    fun `the view of a tree has depth, expansion and selection`() {
        val root = DefaultMutableTreeNode("root").apply {
            add(DefaultMutableTreeNode("src").apply { add(DefaultMutableTreeNode("Main.kt")) })
            add(DefaultMutableTreeNode("build.gradle"))
        }
        val view = onEdt { UiRows.view(JTree(root).apply { expandRow(1); setSelectionRow(2) })!! }
        assertEquals(4, view.total)
        assertEquals(
            listOf(UiRow(0, "root", 0, false, true), UiRow(1, "src", 1, false, true), UiRow(2, "Main.kt", 2, true, null), UiRow(3, "build.gradle", 1, false, null)),
            view.rows,
        )
    }
}
