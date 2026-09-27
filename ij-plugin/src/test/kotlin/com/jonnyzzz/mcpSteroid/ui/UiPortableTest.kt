/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class UiPortableTest {
    private val ok = JButton("OK")
    private val apply = JButton("Apply")
    private val applyToo = JButton("Apply")
    private val search = JTextField("").apply { accessibleContext.accessibleName = "Search" }
    private val other = JTextField("")
    private val root = FallbackUiWalker(onlyShowing = false).build(JPanel().apply { add(ok); add(apply); add(applyToo); add(search); add(other) })

    private fun node(c: java.awt.Component) = root.walk().first { it.component === c }

    @Test
    fun `a named control is replayed by its name`() {
        assertEquals(UiTarget(name = "OK"), UiPortable.stableTarget(node(ok), listOf(root)))
        assertEquals(UiTarget(name = "Search"), UiPortable.stableTarget(node(search), listOf(root), textIsInput = true))
    }

    @Test
    fun `a control that shares its name gets nth among its namesakes`() {
        assertEquals(UiTarget(name = "Apply", nth = 1), UiPortable.stableTarget(node(applyToo), listOf(root)))
    }

    @Test
    fun `an unnamed control falls back to its class, with nth when there are several`() {
        assertEquals("JTextField", UiPortable.stableTarget(node(other), listOf(root)).cls)
        assertEquals(1, UiPortable.stableTarget(node(other), listOf(root)).nth)
    }

    @Test
    fun `a row index becomes the row text, or the tree path when the text repeats`() {
        val list = JList(arrayOf("Alpha", "Beta"))
        assertEquals("Beta", UiPortable.stableRow(list, UiRows.rows(list)!!, 1))
        val top = DefaultMutableTreeNode("root").apply {
            add(DefaultMutableTreeNode("Editor").apply { add(DefaultMutableTreeNode("General")) })
            add(DefaultMutableTreeNode("Tools").apply { add(DefaultMutableTreeNode("General")) })
        }
        val tree = JTree(top).apply { isRootVisible = false; expandRow(1); expandRow(0) }
        val rows = UiRows.rows(tree)!!
        val second = rows.indices.last { rows[it] == "General" }
        assertEquals("Tools > General", UiPortable.stableRow(tree, rows, second))
        assertNull(UiPortable.stableRow(list, UiRows.rows(list)!!, 5))
    }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a rewrite swaps the ref for the target, keeps what a fill enters, and puts the action first`() {
        val click = UiPortable.rewrite(obj("""{"ref":"e12","action":"click","intent":"confirm"}"""), UiTarget(name = "OK"), null)
        assertEquals("""{"action":"click","intent":"confirm","name":"OK"}""", click.toString())
        val fill = UiPortable.rewrite(obj("""{"action":"fill","ref":"e3","text":"Terminal"}"""), UiTarget(name = "Search"), null, textIsInput = true)
        assertEquals("""{"action":"fill","text":"Terminal","name":"Search"}""", fill.toString())
        val select = UiPortable.rewrite(obj("""{"action":"select","ref":"e1#4"}"""), UiTarget(name = "Settings categories"), "Terminal")
        assertEquals("""{"action":"select","name":"Settings categories","row":"Terminal"}""", select.toString())
        val page = UiPortable.rewrite(obj("""{"action":"settings","page":"Code Folding"}"""), null, null, mapOf("page" to "editor.preferences.folding"))
        assertEquals("""{"action":"settings","page":"editor.preferences.folding"}""", page.toString())
    }
}
