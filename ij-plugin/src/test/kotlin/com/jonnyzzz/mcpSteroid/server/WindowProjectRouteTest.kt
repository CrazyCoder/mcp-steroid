/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowProjectRouteTest {
    private val owners = mapOf("dialog" to "frame", "nested" to "dialog", "cycleA" to "cycleB", "cycleB" to "cycleA")
    private val frameProjects = mapOf("frame" to "proj", "otherFrame" to "other")

    private fun route(window: String, fallback: String? = "fallbackProj") =
        windowProjectRoute(window, owners::get, frameProjects::get, fallback)

    @Test
    fun `a dialog owned by a project frame belongs to that project`() {
        assertEquals(WindowProjectRoute("proj", owned = true), route("dialog"))
    }

    @Test
    fun `a dialog owned by another dialog follows the chain to the frame`() {
        assertEquals(WindowProjectRoute("proj", owned = true), route("nested"))
    }

    @Test
    fun `a window without a project owner routes through the fallback project`() {
        assertEquals(WindowProjectRoute("fallbackProj", owned = false), route("survey"))
    }

    @Test
    fun `with no project open there is no route`() {
        assertEquals(WindowProjectRoute<String>(null, owned = false), route("survey", fallback = null))
    }

    @Test
    fun `an owner cycle ends in the fallback instead of looping`() {
        assertEquals(WindowProjectRoute("fallbackProj", owned = false), route("cycleA"))
    }
}
