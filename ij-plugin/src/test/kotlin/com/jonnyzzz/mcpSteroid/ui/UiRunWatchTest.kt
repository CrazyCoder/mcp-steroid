/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiRunWatchTest {
    @Test
    fun `a run action that started nothing says so and lists what runs`() {
        assertEquals(
            "no run or debug session started; running now: 'tabs-alpha.js' (Debug)",
            UiRunWatch.render(started = emptyList(), notStarted = emptyList(), stopped = emptyList(), running = listOf("'tabs-alpha.js' (Debug)"), runAction = true, stopAction = false),
        )
    }

    @Test
    fun `a started run and a run that did not start are named`() {
        assertEquals(
            "started 'App' (Run); did not start 'Tests' (Run): no JDK",
            UiRunWatch.render(listOf("'App' (Run)"), listOf("'Tests' (Run): no JDK"), emptyList(), emptyList(), runAction = true, stopAction = false),
        )
    }

    @Test
    fun `a stop names what stopped and what still runs`() {
        assertEquals(
            "stopped 'App' (Run); still running: 'debug-demo.js' (Debug)",
            UiRunWatch.render(emptyList(), emptyList(), listOf("'App' (Run)"), listOf("'debug-demo.js' (Debug)"), runAction = false, stopAction = true),
        )
    }

    @Test
    fun `an action that is not about runs says nothing`() {
        assertNull(UiRunWatch.render(emptyList(), emptyList(), emptyList(), listOf("'x' (Run)"), runAction = false, stopAction = false))
    }
}
