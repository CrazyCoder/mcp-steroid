/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.common.timeoutRunBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pins [awaitHighlighting] with fake probes: test JVMs are headless and have no
 * editor or daemon.
 */
class HighlightingWaitTest {

    @Test
    fun `completed analysis returns without a restart`(): Unit = timeoutRunBlocking(10.seconds) {
        val restarts = AtomicInteger(0)
        val outcome = awaitHighlighting(
            isCompleted = { true },
            restart = { restarts.incrementAndGet() },
            timeout = 2.seconds,
            restartAfter = 100.milliseconds,
            poll = 10.milliseconds,
        )
        assertEquals(HighlightingWait.COMPLETED, outcome)
        assertEquals(0, restarts.get())
    }

    @Test
    fun `an analysis that never reports completion is restarted once`(): Unit = timeoutRunBlocking(10.seconds) {
        // A file the daemon analyzed before stays not-completed until the daemon runs again.
        val restarted = AtomicBoolean(false)
        val restarts = AtomicInteger(0)
        val outcome = awaitHighlighting(
            isCompleted = { restarted.get() },
            restart = { restarts.incrementAndGet(); restarted.set(true) },
            timeout = 2.seconds,
            restartAfter = 100.milliseconds,
            poll = 10.milliseconds,
        )
        assertEquals(HighlightingWait.COMPLETED, outcome)
        assertEquals(1, restarts.get())
    }

    @Test
    fun `an analysis that never runs times out after one restart`(): Unit = timeoutRunBlocking(10.seconds) {
        val restarts = AtomicInteger(0)
        val outcome = awaitHighlighting(
            isCompleted = { false },
            restart = { restarts.incrementAndGet() },
            timeout = 300.milliseconds,
            restartAfter = 50.milliseconds,
            poll = 10.milliseconds,
        )
        assertEquals(HighlightingWait.TIMED_OUT, outcome)
        assertEquals(1, restarts.get())
    }
}
