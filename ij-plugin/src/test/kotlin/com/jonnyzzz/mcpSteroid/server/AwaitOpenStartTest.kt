/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class AwaitOpenStartTest {
    private val poll = 10.milliseconds

    @Test
    fun `an open that finishes reports the opened project`() = runBlocking {
        val opening = CompletableDeferred<String?>("proj")
        assertEquals(OpenStart.Opened("proj"), awaitOpenStart(opening, 5.seconds, poll) { false })
    }

    @Test
    fun `an open that returns null reports that it was declined`() = runBlocking {
        val opening = CompletableDeferred<String?>().apply { complete(null) }
        assertEquals(OpenStart.Declined, awaitOpenStart(opening, 5.seconds, poll) { false })
    }

    @Test
    fun `a dialog shown while the open waits returns before the bound`() = runBlocking {
        val opening = CompletableDeferred<String?>()
        val started = TimeSource.Monotonic.markNow()
        var probes = 0
        val result = awaitOpenStart(opening, 30.seconds, poll) { ++probes >= 3 }
        assertEquals(OpenStart.DialogShowing, result)
        assertTrue("returned after ${started.elapsedNow()}", started.elapsedNow() < 10.seconds)
    }

    @Test
    fun `an open that neither finishes nor shows a dialog returns at the bound`() = runBlocking {
        val opening = CompletableDeferred<String?>()
        assertEquals(OpenStart.StillOpening, awaitOpenStart(opening, 200.milliseconds, poll) { false })
        assertTrue("the open keeps running in the background", opening.isActive)
    }

    @Test
    fun `an open that fails rethrows its exception`() {
        val opening = CompletableDeferred<String?>().apply { completeExceptionally(IllegalStateException("broken")) }
        val e = assertThrows(IllegalStateException::class.java) {
            runBlocking { awaitOpenStart(opening, 5.seconds, poll) { false } }
        }
        assertEquals("broken", e.message)
    }
}
