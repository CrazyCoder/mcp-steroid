/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton
import javax.swing.JCheckBox
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
