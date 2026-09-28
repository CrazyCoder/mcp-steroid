/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree

class UiLayoutTest {
    /** A header 85 px wide holding a toolbar 183 px wide, right-aligned so that it starts 98 px left of the header. */
    private fun header(): Triple<JPanel, JPanel, List<JButton>> {
        val header = JPanel(null).apply { setBounds(0, 0, 85, 31) }
        val toolbar = JPanel(null).apply { setBounds(-98, 3, 183, 24) }
        val buttons = List(6) { i -> JButton("b$i").apply { setBounds(12 + i * 26, 0, 26, 24) } }
        buttons.forEach(toolbar::add)
        header.add(toolbar)
        return Triple(header, toolbar, buttons)
    }

    @Test
    fun `a button past its panel's edge shows nothing, one inside shows whole`() {
        val (_, toolbar, buttons) = header()
        // Toolbar x -98: button 1 spans -60..-34 in the header, button 5 spans 44..70.
        assertTrue(UiLayout.visiblePart(buttons[1]).isEmpty)
        assertEquals(Rectangle(0, 0, 26, 24), UiLayout.visiblePart(buttons[5]))
        assertEquals(Rectangle(98, 0, 85, 24), UiLayout.visiblePart(toolbar))
    }

    @Test
    fun `a control cut in half is clipped, one cut by a few pixels is not`() {
        assertEquals(UiClip.OUTSIDE, UiLayout.clipOf(26, 24, Rectangle()))
        assertEquals(UiClip.CLIPPED, UiLayout.clipOf(183, 24, Rectangle(98, 0, 85, 24)))
        // An editor tab's close button is 35 px high in a 31 px tab: its icon shows whole.
        assertNull(UiLayout.clipOf(16, 35, Rectangle(0, 2, 16, 31)))
        assertNull(UiLayout.clipOf(26, 24, Rectangle(0, 0, 26, 24)))
    }

    @Test
    fun `the walk stops at a scroll pane, whose scrolling is not a panel that is too small`() {
        val tree = JTree()
        val scroll = JScrollPane(tree).apply { setBounds(0, 0, 80, 100) }
        scroll.doLayout()
        tree.setBounds(0, -500, 400, 2000)
        assertEquals(Rectangle(0, 0, 400, 2000), UiLayout.visiblePart(tree))
        assertTrue(UiLayout.visiblePart(tree, throughViewports = true).height <= 100)
    }

    @Test
    fun `a window side fits to its preferred size without shrinking, and stays within its minimum and its screen`() {
        // size(wanted, current, preferred, minimum, screen)
        assertEquals(900, UiResize.size("fit", 700, 900, 400, 2500))
        assertEquals(1000, UiResize.size("fit", 1000, 900, 400, 2500))
        assertEquals(700, UiResize.size(null, 700, 900, 400, 2500))
        assertEquals(400, UiResize.size("300", 700, 900, 400, 2500))
        assertEquals(2500, UiResize.size("3000", 700, 900, 400, 2500))
        assertEquals(2500, UiResize.size("fit", 700, 4000, 400, 2500))
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun refs(root: UiNode): (UiNode) -> String {
        val ids = root.walk().withIndex().associate { (i, n) -> n.component to "e$i" }
        return { ids.getValue(it.component) }
    }

    /** A stacked splitter 400 x 600 whose top pane holds a tree of [rows] rows 20 px high in a scroll pane [height] px high. */
    private fun stackedTree(rows: Int, height: Int): Pair<com.intellij.openapi.ui.Splitter, JTree> {
        val root = javax.swing.tree.DefaultMutableTreeNode("root").apply { repeat(rows) { add(javax.swing.tree.DefaultMutableTreeNode("row $it")) } }
        val tree = JTree(root).apply { rowHeight = 20; isRootVisible = false }
        val scroll = JScrollPane(tree)
        val s = com.intellij.openapi.ui.Splitter(true, height / 600f).apply { firstComponent = scroll; secondComponent = JPanel(); setSize(400, 600); doLayout() }
        scroll.doLayout()
        return s to tree
    }

    @Test
    fun `rows cut in height in a splitter pane name a splitter fit`() = onEdt {
        val (s, tree) = stackedTree(6, 50)
        val root = FallbackUiWalker(onlyShowing = false).build(s)
        val cuts = UiLayout.cuts(root, refs(root)) { false }
        assertEquals(1, cuts.size)
        val cut = cuts.single()
        assertTrue(cut.what, cut.what.contains("of 6 rows"))
        assertEquals("""{"action":"splitter","ref":"${refs(root)(root.walk().first { it.component === tree })}","size":"fit"}""", cut.fix)
    }

    @Test
    fun `a long list that scrolls is not cut`() = onEdt {
        val (s, _) = stackedTree(200, 300)
        val root = FallbackUiWalker(onlyShowing = false).build(s)
        assertEquals(emptyList<UiLayout.Cut>(), UiLayout.cuts(root, refs(root)) { false })
    }

    @Test
    fun `a table header cut in a dialog names no splitter`() = onEdt {
        val table = javax.swing.JTable(javax.swing.table.DefaultTableModel(arrayOf(arrayOf<Any>("url", "string", "")), arrayOf("Name", "Type", "Default parameter value")))
        val scroll = JScrollPane(table).apply { setBounds(0, 0, 240, 120) }
        scroll.doLayout()
        table.setSize(240, 40)
        table.doLayout()
        val root = FallbackUiWalker(onlyShowing = false).build(scroll)
        val cuts = UiLayout.cuts(root, refs(root)) { true }
        assertTrue(cuts.toString(), cuts.any { it.what.contains("Default parameter value") && it.fix == null })
        // In the IDE window a header squeezed by its tool window is not reported.
        assertTrue(UiLayout.cuts(root, refs(root)) { false }.none { it.what.contains("Default parameter value") })
    }

    @Test
    fun `a combo box narrower than its text is cut in a dialog`() = onEdt {
        val combo = javax.swing.JComboBox(arrayOf("{channelId: string; ts: string} | null")).apply { setSize(60, 24) }
        val panel = JPanel(null).apply { setSize(200, 40); add(combo) }
        val root = FallbackUiWalker(onlyShowing = false).build(panel)
        val cuts = UiLayout.cuts(root, refs(root)) { true }
        assertTrue(cuts.toString(), cuts.any { it.what.startsWith("the text of JComboBox") && it.what.endsWith("is cut") })
        combo.setSize(400, 24)
        assertTrue(UiLayout.cuts(root, refs(root)) { true }.none { it.what.startsWith("the text of JComboBox") })
    }

    @Test
    fun `a tree wider than its view says its rows are cut, a narrow one says nothing`() {
        val tree = object : JTree() {
            var wide = 452
            override fun getPreferredSize() = Dimension(wide, 400)
        }
        val scroll = JScrollPane(tree).apply { setBounds(0, 0, 90, 300) }
        scroll.doLayout()
        val cut = UiLayout.rowsCut(tree)
        assertTrue(cut, cut!!.startsWith("rows need 452 px and the view shows "))
        tree.wide = 40
        assertNull(UiLayout.rowsCut(tree))
    }
}
