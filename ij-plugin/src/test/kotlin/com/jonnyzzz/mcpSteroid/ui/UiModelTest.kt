/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class UiModelTest {
    private val panel = JPanel().apply { add(JButton("OK")) }

    @Test
    fun `a failing remote-driver build falls back to the walker and says why`() {
        val result = UiModel.build(panel, onlyShowing = false, remote = { error("paint failed") }, remoteUnavailable = { null })
        assertEquals(UiModel.SOURCE_SWING, result.source)
        assertTrue(result.note, result.note!!.contains("paint failed"))
        assertEquals(listOf("JButton"), result.root.children.map { it.className })
    }

    @Test
    fun `an unavailable remote driver is named in the note`() {
        val result = UiModel.build(panel, onlyShowing = false, remote = { error("unused") }, remoteUnavailable = { "plugin disabled" })
        assertEquals(UiModel.SOURCE_SWING, result.source)
        assertEquals("remote driver unavailable: plugin disabled", result.note)
    }

    @Test
    fun `an unnamed field takes the caption of the label or checkbox before it`() {
        val form = JPanel().apply {
            add(JCheckBox("Show line numbers:"))
            add(JComboBox(arrayOf("Absolute", "Relative")))
            add(JLabel("Name:"))
            add(JTextField("x").apply { accessibleContext.accessibleName = "Project name" })
        }
        val kids = UiModel.labelled(FallbackUiWalker(onlyShowing = false).build(form)).children
        assertEquals(listOf(null, "Show line numbers:", null, null), kids.map { it.label })
    }

    @Test
    fun `a working remote-driver build is used`() {
        val fake = FallbackUiWalker(onlyShowing = false).build(panel)
        val result = UiModel.build(panel, onlyShowing = false, remote = { UiModelBuild(fake) }, remoteUnavailable = { null })
        assertEquals(UiModel.SOURCE_REMOTE_DRIVER, result.source)
        assertNull(result.note)
    }
}
