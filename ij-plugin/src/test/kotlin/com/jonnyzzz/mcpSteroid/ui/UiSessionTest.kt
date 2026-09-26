/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSessionTest {
    @Test
    fun `a name that ends with an ellipsis opens a window`() {
        assertTrue(UiSession.opensWindow("Settings…"))
        assertTrue(UiSession.opensWindow("Edit... "))
        assertFalse(UiSession.opensWindow("Show Line Numbers"))
        assertFalse(UiSession.opensWindow("Wait…for it"))
    }
}
