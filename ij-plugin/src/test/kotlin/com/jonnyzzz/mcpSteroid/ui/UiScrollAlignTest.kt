/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScrollAlignTest {
    @Test
    fun `top puts the area at the top of the view`() {
        assertEquals(500, UiScrollAlign.viewY(500, 20, 300, 2000, "top"))
    }

    @Test
    fun `center puts the area in the middle of the view`() {
        assertEquals(360, UiScrollAlign.viewY(500, 20, 300, 2000, "center"))
    }

    @Test
    fun `the view stays within the content`() {
        assertEquals(1700, UiScrollAlign.viewY(1990, 10, 300, 2000, "top"))
        assertEquals(0, UiScrollAlign.viewY(10, 10, 300, 2000, "center"))
        assertEquals(0, UiScrollAlign.viewY(100, 10, 300, 200, "top"))
    }
}
