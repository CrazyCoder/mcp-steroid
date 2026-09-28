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
