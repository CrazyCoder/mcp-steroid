/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamPluginGuardTest {
    @Test
    fun `upstream id is the Marketplace plugin`() {
        assertEquals("com.jonnyzzz.mcp-steroid", UpstreamPluginGuard.UPSTREAM_PLUGIN_ID)
    }

    @Test
    fun `an installed and enabled upstream is disabled`() {
        assertTrue(UpstreamPluginGuard.shouldDisable(installed = true, disabled = false))
    }

    @Test
    fun `an already disabled upstream is left alone`() {
        assertFalse(UpstreamPluginGuard.shouldDisable(installed = true, disabled = true))
    }

    @Test
    fun `no upstream means nothing to do`() {
        assertFalse(UpstreamPluginGuard.shouldDisable(installed = false, disabled = false))
    }
}
