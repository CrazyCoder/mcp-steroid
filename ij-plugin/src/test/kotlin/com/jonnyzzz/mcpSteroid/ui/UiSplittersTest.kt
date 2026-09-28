/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.ThreeComponentsSplitter
import com.jonnyzzz.mcpSteroid.ui.UiSplitters.Axis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode

class UiSplittersTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** A side-by-side splitter 1000 x 400 with two empty panes, laid out. */
    private fun sideBySide(proportion: Float = 0.5f, min: Float = 0f, max: Float = 1f): Triple<Splitter, JPanel, JPanel> {
        val first = JPanel()
        val second = JPanel()
        val s = Splitter(false, proportion, min, max).apply {
            firstComponent = first
            secondComponent = second
            setSize(1000, 400)
            doLayout()
        }
        return Triple(s, first, second)
    }

    @Test
    fun `a pane of a splitter takes a size`() = onEdt {
        val (s, first, _) = sideBySide()
        val pane = UiSplitters.paneOf(first)!!
        assertSame(s, pane.splitter)
        assertEquals(Axis.WIDTH, pane.axis)
        val r = UiSplitters.setSize(pane, 300)
        assertEquals(300.0, first.width.toDouble(), 2.0)
        assertEquals(500.0, r.before.toDouble(), 3.0)
        assertEquals(300.0, r.after.toDouble(), 2.0)
        assertTrue("proportion ${r.proportionAfter}", r.proportionAfter in 0.29..0.31)
        assertNull(r.heldBack)
    }

    @Test
    fun `a splitter with no room fails a size and keeps its proportion`() = onEdt {
        val first = JPanel()
        val s = Splitter(false, 0.4f).apply { firstComponent = first; secondComponent = JPanel() }
        val e = assertThrows(UiStepFailure::class.java) { UiSplitters.setSize(UiSplitters.paneOf(first)!!, 300) }
        assertEquals("the splitter has no room to share out: it is 0 px wide until it shows", e.message)
        assertEquals(0.4f, s.proportion, 0.0001f)
    }

    @Test
    fun `sizing the second pane moves the divider the other way`() = onEdt {
        val (s, first, second) = sideBySide()
        UiSplitters.setSize(UiSplitters.paneOf(second)!!, 300)
        assertEquals(300.0, second.width.toDouble(), 2.0)
        assertEquals((1000 - 300 - s.dividerWidth).toDouble(), first.width.toDouble(), 2.0)
    }

    @Test
    fun `a proportion the splitter clamps is reported as held back`() = onEdt {
        val (s, _, _) = sideBySide(0.5f, 0.3f, 0.7f)
        val r = UiSplitters.setProportion(s, 0.1)
        assertEquals(0.3, r.proportionAfter, 0.001)
        assertEquals("the splitter keeps its first pane between 0.30 and 0.70", r.heldBack)
    }

    @Test
    fun `a three components splitter sizes its first and last panes, and its inner one through the last`() = onEdt {
        val first = JPanel()
        val inner = JPanel()
        val last = JPanel()
        val s = ThreeComponentsSplitter(false).apply {
            firstComponent = first
            innerComponent = inner
            lastComponent = last
            firstSize = 100
            lastSize = 100
            setSize(1000, 300)
            doLayout()
        }
        UiSplitters.setSize(UiSplitters.paneOf(last)!!, 200)
        assertEquals(200, s.lastSize)
        UiSplitters.setSize(UiSplitters.paneOf(inner)!!, 500)
        s.doLayout()
        assertEquals(500.0, inner.width.toDouble(), 3.0)
    }

    @Test
    fun `a split pane moves its divider`() = onEdt {
        val left = JPanel()
        val right = JPanel()
        val s = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { setSize(800, 300); doLayout() }
        UiSplitters.setSize(UiSplitters.paneOf(right)!!, 200)
        assertEquals(800 - 200 - s.dividerSize, s.dividerLocation)
    }

    @Test
    fun `fit gives a tree in a stacked pane the height of its rows`() = onEdt {
        val root = DefaultMutableTreeNode("root").apply { repeat(6) { add(DefaultMutableTreeNode("row $it")) } }
        val tree = JTree(root).apply { rowHeight = 20 }
        val scroll = JScrollPane(tree)
        val rest = JPanel().apply { minimumSize = Dimension(10, 50) }
        val s = Splitter(true, 0.1f).apply { firstComponent = scroll; secondComponent = rest; setSize(400, 600); doLayout() }
        scroll.doLayout()
        assertEquals(listOf(Axis.HEIGHT), UiSplitters.cutAxes(tree))
        val pane = UiSplitters.paneOf(tree, Axis.HEIGHT)!!
        assertSame(scroll, pane.child)
        UiSplitters.setSize(pane, UiSplitters.fitSize(pane))
        scroll.doLayout()
        assertTrue("the tree shows its ${tree.preferredSize.height} px: ${scroll.viewport.extentSize}", scroll.viewport.extentSize.height >= tree.preferredSize.height)
        assertTrue(s.proportion > 0.1f)
    }

    @Test
    fun `a tree table whose tree column is narrower than its tree is cut at the right, and fit adds the shortfall`() = onEdt {
        val root = DefaultMutableTreeNode("root").apply { add(DefaultMutableTreeNode("Unused local symbol with a long name")) }
        val model = com.intellij.ui.treeStructure.treetable.ListTreeTableModel(root, arrayOf(com.intellij.ui.treeStructure.treetable.TreeColumnInfo("Name")))
        val table = com.intellij.ui.treeStructure.treetable.TreeTable(model).apply { setRootVisible(false) }
        val scroll = JScrollPane(table)
        val s = Splitter(false, 0.1f).apply { firstComponent = scroll; secondComponent = JPanel(); setSize(1000, 300); doLayout() }
        scroll.doLayout()
        table.doLayout()
        val short = UiSplitters.shortfall(table, Axis.WIDTH)
        assertTrue("short by $short", short > 0)
        assertTrue(Axis.WIDTH in UiSplitters.cutAxes(table))
        val pane = UiSplitters.paneOf(table, Axis.WIDTH)!!
        val before = UiSplitters.size(pane)
        assertEquals(before + short, UiSplitters.fitSize(pane, table))
        assertTrue(s.proportion < 0.2f)
    }

    @Test
    fun `fit never shrinks a pane when the other pane's minimum leaves no room`() = onEdt {
        val tree = JTree(DefaultMutableTreeNode("a rather long root name that needs room")).apply { }
        val scroll = JScrollPane(tree)
        val greedy = JPanel().apply { minimumSize = Dimension(900, 10) }
        // As the Inspections page's splitter does: the other pane's minimum does not hold the divider.
        val s = Splitter(false, 0.3f).apply {
            setHonorComponentsMinimumSize(false)
            firstComponent = scroll; secondComponent = greedy; setSize(1000, 300); doLayout()
        }
        scroll.doLayout()
        val pane = UiSplitters.paneOf(tree, Axis.WIDTH)!!
        assertTrue("the pane is wider than the room the minimum leaves: ${UiSplitters.size(pane)}", UiSplitters.size(pane) > 200)
        assertEquals(UiSplitters.size(pane), UiSplitters.fitSize(pane, tree))
        assertTrue(s.proportion >= 0.29f)
    }

    @Test
    fun `fit leaves the other pane its minimum size`() = onEdt {
        val big = JPanel().apply { preferredSize = Dimension(10, 5000) }
        val rest = JPanel().apply { minimumSize = Dimension(10, 100) }
        val s = Splitter(true, 0.5f).apply { firstComponent = big; secondComponent = rest; setSize(400, 600); doLayout() }
        val fit = UiSplitters.fitSize(UiSplitters.paneOf(big)!!)
        assertEquals(600 - s.dividerWidth - 100, fit)
    }

    @Test
    fun `a control in no splitter has no pane, and a splitter describes itself`() = onEdt {
        assertNull(UiSplitters.paneOf(JPanel()))
        val (s, _, _) = sideBySide(0.25f)
        assertEquals("horizontal 0.25", UiSplitters.describe(s))
        assertEquals("vertical 0.60", UiSplitters.describe(Splitter(true, 0.6f)))
        assertNotNull(UiSplitters.describe(ThreeComponentsSplitter(true)))
        assertNull(UiSplitters.describe(JPanel()))
    }
}
