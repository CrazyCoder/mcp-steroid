/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class UiLocatorTest {
    private val ok = JButton("OK")
    private val cancel = JButton("Cancel")
    private val apply = JButton("Apply")
    private val applyToo = JButton("Apply")
    private val field = JTextField("13").apply { accessibleContext.accessibleName = "Font size" }
    private val box = JCheckBox("Show tool window bars")
    private val root = FallbackUiWalker(onlyShowing = false).build(JPanel().apply {
        add(ok); add(cancel); add(apply); add(field); add(JPanel().apply { add(box); add(applyToo) })
    })

    private fun one(target: UiTarget) = (UiLocator.find(listOf(root), target) as UiMatch.One).node.component

    @Test
    fun `name matches exactly`() {
        assertEquals(field, one(UiTarget(name = "Font size")))
        assertTrue(UiLocator.find(listOf(root), UiTarget(name = "Font")) is UiMatch.None)
    }

    @Test
    fun `text matches a substring of what the component shows`() {
        assertEquals(box, one(UiTarget(text = "tool window")))
    }

    @Test
    fun `class matches the simple name of the class or a superclass`() {
        assertEquals(field, one(UiTarget(cls = "JTextComponent")))
        assertEquals(box, one(UiTarget(cls = "JCheckBox")))
    }

    @Test
    fun `two matches fail as Many unless nth picks one`() {
        val many = UiLocator.find(listOf(root), UiTarget(name = "Apply"))
        assertTrue(many is UiMatch.Many)
        assertEquals(listOf(apply, applyToo), (many as UiMatch.Many).matches.map { it.component })
        assertEquals(applyToo, one(UiTarget(name = "Apply", nth = 1)))
    }

    @Test
    fun `matches in the topmost window win over the windows behind it`() {
        val dialogCancel = JButton("Cancel")
        val dialog = FallbackUiWalker(onlyShowing = false).build(JPanel().apply { add(dialogCancel) })
        val found = UiLocator.find(listOf(dialog, root), UiTarget(name = "Cancel"))
        assertEquals(dialogCancel, (found as UiMatch.One).node.component)
        // A window with no match does not hide the matches behind it.
        assertEquals(apply, (UiLocator.find(listOf(dialog, root), UiTarget(name = "Apply", nth = 0)) as UiMatch.One).node.component)
    }

    @Test
    fun `combined fields must all match`() {
        assertEquals(cancel, one(UiTarget(name = "Cancel", cls = "JButton")))
        assertTrue(UiLocator.find(listOf(root), UiTarget(name = "Cancel", cls = "JCheckBox")) is UiMatch.None)
    }

    @Test
    fun `no match returns the nearest candidates, closest first`() {
        val none = UiLocator.find(listOf(root), UiTarget(name = "Cancle")) as UiMatch.None
        assertEquals(cancel, none.candidates.first().component)
        assertTrue(none.candidates.size <= UiLocator.MAX_CANDIDATES)
    }

    @Test
    fun `a class that matches nothing suggests controls whose class shares its words`() {
        val none = UiLocator.find(listOf(root), UiTarget(cls = "SearchTextField")) as UiMatch.None
        assertEquals(listOf<Any>(field), none.candidates.map { it.component })
    }

    @Test
    fun `a label that shares its field's name loses to the field`() {
        val label = JLabel("Zoom:")
        val combo = JTextField("100%").apply { accessibleContext.accessibleName = "Zoom:" }
        val tree = FallbackUiWalker(onlyShowing = false).build(JPanel().apply { add(label); add(combo) })
        assertEquals(combo, (UiLocator.find(listOf(tree), UiTarget(name = "Zoom:")) as UiMatch.One).node.component)
    }

    @Test
    fun `a match inside another match is dropped`() {
        val outer = JPanel().apply { accessibleContext.accessibleName = "Zoom:" }
        val inner = JTextField().apply { accessibleContext.accessibleName = "Zoom:" }
        outer.add(inner)
        val tree = FallbackUiWalker(onlyShowing = false).build(JPanel().apply { add(outer) })
        val found = UiLocator.find(listOf(tree), UiTarget(name = "Zoom:"))
        assertEquals(inner, (found as UiMatch.One).node.component)
    }

    @Test
    fun `an editable combo box wins over its own editor field`() {
        val combo = JComboBox(arrayOf("100%", "110%")).apply {
            isEditable = true
            accessibleContext.accessibleName = "Zoom:"
            editor.editorComponent.accessibleContext.accessibleName = "Zoom:"
        }
        val tree = FallbackUiWalker(onlyShowing = false).build(JPanel().apply { add(combo) })
        assertEquals(combo, (UiLocator.find(listOf(tree), UiTarget(name = "Zoom:")) as UiMatch.One).node.component)
    }

    @Test
    fun `the same component listed under two roots is one match`() {
        val found = UiLocator.find(listOf(root, root), UiTarget(name = "Cancel"))
        assertEquals(cancel, (found as UiMatch.One).node.component)
    }

    @Test
    fun `an xpath target uses the matcher it is given`() {
        val found = UiLocator.find(listOf(root), UiTarget(xpath = "//div[@x]")) { setOf(ok) }
        assertEquals(ok, (found as UiMatch.One).node.component)
    }

    @Test
    fun `an xpath target without a matcher fails with the reason`() {
        val e = runCatching { UiLocator.find(listOf(root), UiTarget(xpath = "//div")) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.contains("remote-driver"))
    }
}
