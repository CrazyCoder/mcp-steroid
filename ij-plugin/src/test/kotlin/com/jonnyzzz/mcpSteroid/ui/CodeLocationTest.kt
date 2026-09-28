/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeLocationTest {
    private val text = "class UiStateX\nenum class UiState {\n    A, UiState_B\n}\nval s = UiState.A\n"

    private fun caret(offset: Int) = offset until offset
    private fun fails(block: () -> Unit): String = assertThrows(UiStepFailure::class.java) { block() }.message!!

    @Test
    fun `a click at a symbol lands past its end, at a line and column exactly there`() {
        assertEquals(text.indexOf("UiState {") + "UiState".length, CodeLocation.clickOffset(text, symbol = "UiState"))
        assertEquals(text.indexOf("UiState.A") + "UiState".length, CodeLocation.clickOffset(text, symbol = "UiState", nth = 1))
        assertEquals(text.indexOf("enum") + 5, CodeLocation.clickOffset(text, line = 2, column = 6))
    }

    @Test
    fun `line and column give a caret`() {
        assertEquals(caret(text.indexOf("enum") + 5), CodeLocation.resolve(text, line = 2, column = 6))
    }

    @Test
    fun `column past the line end stops at the line end, and a missing column is the line start`() {
        assertEquals(caret(text.indexOf("\nenum")), CodeLocation.resolve(text, line = 1, column = 99))
        assertEquals(caret(text.indexOf("enum")), CodeLocation.resolve(text, line = 2))
    }

    @Test
    fun `a line out of range fails naming the line count`() {
        assertTrue(fails { CodeLocation.resolve(text, line = 40) }.contains("6 lines"))
    }

    @Test
    fun `symbol matches whole words only and nth picks a later one`() {
        val first = text.indexOf("UiState {")
        assertEquals(caret(first), CodeLocation.resolve(text, symbol = "UiState"))
        assertEquals(caret(text.indexOf("UiState.A")), CodeLocation.resolve(text, symbol = "UiState", nth = 1))
    }

    @Test
    fun `a snippet is selected`() {
        val start = text.indexOf("UiState.A")
        assertEquals(start until start + "UiState.A".length, CodeLocation.resolve(text, snippet = "UiState.A"))
    }

    @Test
    fun `a missing symbol or snippet, or an nth past the matches, fails with the count found`() {
        assertTrue(fails { CodeLocation.resolve(text, symbol = "Nope") }.contains("found 0"))
        assertTrue(fails { CodeLocation.resolve(text, snippet = "zzz") }.contains("found 0"))
        assertTrue(fails { CodeLocation.resolve(text, symbol = "UiState", nth = 5) }.contains("found 2"))
    }
}
