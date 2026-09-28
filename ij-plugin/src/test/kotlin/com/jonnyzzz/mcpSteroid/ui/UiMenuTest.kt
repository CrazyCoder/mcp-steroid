/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMenuTest {
    private val appearance = listOf("Enter Presentation Mode", "Compact Mode", "Zoom IDE (Current: 100%)…", "Status Bar", "Status Bar Widgets")

    @Test
    fun `an item is found by its text without case or ellipsis, then by its start, then by part of it`() {
        assertEquals(3, UiMenu.pickIndex(appearance, "status bar", "View > Appearance"))
        assertEquals(2, UiMenu.pickIndex(appearance, "Zoom IDE", "View > Appearance"))
        assertEquals(0, UiMenu.pickIndex(appearance, "Presentation", "View > Appearance"))
        assertEquals(1, UiMenu.pickIndex(listOf("Settings…", "Zoom IDE…"), "Zoom IDE...", "File"))
    }

    @Test
    fun `several matches of one tier fail and list them, none fails and lists the items`() {
        val many = assertThrows(UiStepFailure::class.java) { UiMenu.pickIndex(appearance, "Mode", "View > Appearance") }
        assertTrue(many.message, many.message!!.startsWith("\"Mode\" matches 2 items in View > Appearance: Enter Presentation Mode; Compact Mode"))
        val none = assertThrows(UiStepFailure::class.java) { UiMenu.pickIndex(appearance, "Nope", "View > Appearance") }
        assertTrue(none.message, none.message!!.startsWith("no item \"Nope\" in View > Appearance; its items: Enter Presentation Mode; "))
    }

    @Test
    fun `folded menus read as a list`() {
        assertEquals("Help", UiMenu.andList(listOf("Help")))
        assertEquals("Window and Help", UiMenu.andList(listOf("Window", "Help")))
        assertEquals("VCS, Window and Help", UiMenu.andList(listOf("VCS", "Window", "Help")))
    }
}
