/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiThemesTest {
    private val themes = listOf(
        UiThemes.Theme("ExperimentalLight", "Light", dark = false, plugin = null, current = false),
        UiThemes.Theme("ExperimentalDark", "Dark", dark = true, plugin = null, current = true),
        UiThemes.Theme("com.x.dracula", "Dracula Pro", dark = true, plugin = "Dracula Theme", current = false),
    )

    @Test
    fun `a theme is picked by name without case, or by id`() {
        assertEquals("ExperimentalLight", UiThemes.pick(themes, "light").id)
        assertEquals("com.x.dracula", UiThemes.pick(themes, "com.x.dracula").id)
    }

    @Test
    fun `an unknown theme lists the installed names`() {
        val e = assertThrows(UiStepFailure::class.java) { UiThemes.pick(themes, "Solarized") }
        assertTrue(e.message!!, "Light, Dark, Dracula Pro" in e.message!!)
    }

    @Test
    fun `the list marks the current theme and names the plugin`() {
        val text = UiThemes.render(themes, syncWithOs = false)
        assertTrue(text, "Dark (dark, id ExperimentalDark) [current]" in text)
        assertTrue(text, "Dracula Pro (dark, id com.x.dracula, plugin Dracula Theme)" in text)
        assertTrue(text, "Light (light, id ExperimentalLight)" in text)
    }

    @Test
    fun `the list says when the theme follows the OS`() {
        assertTrue(UiThemes.render(themes, syncWithOs = true).contains("follows the OS"))
    }
}
