/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField

class FallbackUiWalkerTest {
    private fun sample(): JPanel = JPanel().apply {
        add(JLabel("Font size:"))
        add(JTextField("13").apply { accessibleContext.accessibleName = "Font size" })
        add(JCheckBox("Show tool window bars", true))
        add(JButton("Apply").apply { isEnabled = false })
    }

    @Test
    fun `the walker reads names, text, values and states`() {
        val kids = FallbackUiWalker(onlyShowing = false).build(sample()).children
        assertEquals(listOf("JLabel", "JTextField", "JCheckBox", "JButton"), kids.map { it.className })
        assertEquals(listOf("Font size:"), kids[0].text)
        assertEquals("Font size", kids[1].name)
        assertEquals("13", kids[1].value)
        assertTrue(UiState.EDITABLE in kids[1].states)
        assertTrue(UiState.CHECKED in kids[2].states)
        assertTrue(UiState.DISABLED in kids[3].states)
        assertTrue(kids[3].interactive)
        assertFalse(kids[0].interactive)
    }

    @Test
    fun `a leaf reads the component without its children`() {
        val leaf = FallbackUiWalker(onlyShowing = false).leaf(sample())
        assertEquals("JPanel", leaf.className)
        assertTrue(leaf.children.isEmpty())
    }

    @Test
    fun `invisible children are skipped when asked`() {
        val panel = sample()
        panel.getComponent(0).isVisible = false
        assertEquals(4, FallbackUiWalker(onlyShowing = false).build(panel).children.size)
        assertEquals(3, FallbackUiWalker(onlyShowing = false, skipInvisible = true).build(panel).children.size)
    }

    @Test
    fun `a combo box value is the selected item as its renderer shows it`() {
        val combo = JComboBox(arrayOf(Item("a"), Item("b"))).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
                    super.getListCellRendererComponent(list, "Shown ${(value as Item).id}", index, selected, focus)
            }
            selectedIndex = 1
        }
        assertEquals("Shown b", FallbackUiWalker(onlyShowing = false).build(combo).value)
    }

    private class Item(val id: String) {
        override fun toString() = "Item@$id"
    }
}
