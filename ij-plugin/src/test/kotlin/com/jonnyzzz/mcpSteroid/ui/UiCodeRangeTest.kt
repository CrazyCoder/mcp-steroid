/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.ui.UiCodeRange.LineSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Rectangle

class UiCodeRangeTest {
    @Test
    fun `the outline spans the leftmost text to the widest line, blank lines between included`() {
        val r = UiCodeRange.outline(listOf(LineSpan(40, 300, 0, 20), LineSpan(null, 0, 20, 40), LineSpan(20, 500, 40, 60)))
        assertEquals(Rectangle(20, 0, 480, 60), r)
    }

    @Test
    fun `a wrapped line counts each of its visual lines`() {
        // One logical line painted on two visual lines: the second starts at the wrap indent and is wider.
        val r = UiCodeRange.outline(listOf(LineSpan(60, 400, 100, 120), LineSpan(null, 520, 120, 140)))
        assertEquals(Rectangle(60, 100, 460, 40), r)
    }

    @Test
    fun `lines of blanks only give no outline`() {
        assertNull(UiCodeRange.outline(listOf(LineSpan(null, 0, 0, 20))))
        assertNull(UiCodeRange.outline(emptyList()))
    }
}
