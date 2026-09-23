/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.common.timeoutRunBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pins [awaitRefreshUnlessModal] and [runBoundedByTimeout] with fake probes. Test JVMs are
 * headless, so real modal dialogs cannot be shown here; the live behavior is smoke-tested against a
 * running IDE.
 */
class ModalAwareWaitsTest {

    private fun refreshScope() = CoroutineScope(SupervisorJob())

    @Test
    fun `refresh is not awaited when the IDE is modal at the start`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = refreshScope()
        try {
            val outcome = awaitRefreshUnlessModal(
                isModal = { true },
                refresh = { error("must not start a refresh under a modal") },
                launchIn = scope,
                cap = 5.seconds,
                poll = 10.milliseconds,
            )
            assertEquals(RefreshWait.SKIPPED_MODAL, outcome)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a refresh that finishes is awaited`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = refreshScope()
        try {
            val done = AtomicInteger(0)
            val outcome = awaitRefreshUnlessModal(
                isModal = { false },
                refresh = { delay(50.milliseconds); done.incrementAndGet() },
                launchIn = scope,
                cap = 5.seconds,
                poll = 10.milliseconds,
            )
            assertEquals(RefreshWait.DONE, outcome)
            assertEquals(1, done.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `waiting stops as soon as a modal appears mid-refresh`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = refreshScope()
        try {
            val probes = AtomicInteger(0)
            val elapsed = measureTimeMillis {
                val outcome = awaitRefreshUnlessModal(
                    // not modal at the start, modal from the third probe on
                    isModal = { probes.incrementAndGet() >= 3 },
                    refresh = { awaitCancellation() },
                    launchIn = scope,
                    cap = 5.seconds,
                    poll = 10.milliseconds,
                )
                assertEquals(RefreshWait.STOPPED_MODAL, outcome)
            }
            assertTrue("stopped waiting after ${elapsed}ms, expected well under the 5 s cap", elapsed < 2_000)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a refresh that never finishes is capped`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = refreshScope()
        try {
            val outcome = awaitRefreshUnlessModal(
                isModal = { false },
                refresh = { awaitCancellation() },
                launchIn = scope,
                cap = 200.milliseconds,
                poll = 10.milliseconds,
            )
            assertEquals(RefreshWait.TIMED_OUT, outcome)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `closed dialogs are named by title`() {
        assertEquals("no dialog", emptyList<String>().describeDialogs())
        assertEquals("dialog 'Settings'", listOf("Settings").describeDialogs())
        assertEquals("dialogs 'Rename', 'Conflicts'", listOf("Rename", "Conflicts").describeDialogs())
    }

    private fun detachScope() = CoroutineScope(SupervisorJob())

    @Test
    fun `a block that finishes in time returns its value`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = detachScope()
        try {
            val value = runBoundedByTimeout(
                timeout = 5.seconds,
                grace = 100.milliseconds,
                detachIn = scope,
                closeDialogsOpenedDuringRun = { error("the watchdog must not fire") },
                onClosed = { },
                onStuck = { error("the watchdog must not fire") },
            ) { 42 }
            assertEquals(42, value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a cooperative block times out without the watchdog`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = detachScope()
        try {
            val stuck = AtomicInteger(0)
            try {
                runBoundedByTimeout(
                    timeout = 100.milliseconds,
                    grace = 300.milliseconds,
                    detachIn = scope,
                    closeDialogsOpenedDuringRun = { stuck.incrementAndGet(); emptyList() },
                    onClosed = { fail("nothing should be closed") },
                    onStuck = { stuck.incrementAndGet() },
                ) { awaitCancellation() }
                fail("expected a timeout")
            } catch (_: TimeoutCancellationException) {
            }
            assertEquals("a block that stops on cancellation is not stuck", 0, stuck.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a block stuck in a dialog it opened is released at the timeout`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = detachScope()
        try {
            // The block ignores cancellation until the dialog closes, as a script parked in a
            // modal dialog's event loop does.
            val dialogClosed = CompletableDeferred<Unit>()
            val closed = mutableListOf<String>()
            val stuck = AtomicInteger(0)
            val elapsed = measureTimeMillis {
                try {
                    runBoundedByTimeout(
                        timeout = 200.milliseconds,
                        grace = 100.milliseconds,
                        detachIn = scope,
                        closeDialogsOpenedDuringRun = {
                            if (dialogClosed.isCompleted) emptyList() else listOf("Modal Probe").also { dialogClosed.complete(Unit) }
                        },
                        onClosed = { closed += it },
                        onStuck = { stuck.incrementAndGet() },
                    ) { withContext(NonCancellable) { dialogClosed.await() } }
                    fail("expected a timeout")
                } catch (_: TimeoutCancellationException) {
                }
            }
            assertEquals(listOf("Modal Probe"), closed)
            assertEquals(1, stuck.get())
            assertTrue("returned after ${elapsed}ms, expected about timeout + grace", elapsed < 2_000)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a block that never stops is left running and the call returns`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = detachScope()
        try {
            val release = CompletableDeferred<Unit>()
            val stuck = AtomicInteger(0)
            val elapsed = measureTimeMillis {
                try {
                    runBoundedByTimeout(
                        timeout = 200.milliseconds,
                        grace = 500.milliseconds,
                        detachIn = scope,
                        closeDialogsOpenedDuringRun = { emptyList() },
                        onClosed = { fail("nothing should be closed") },
                        onStuck = { stuck.incrementAndGet() },
                    ) { withContext(NonCancellable) { release.await() } }
                    fail("expected the block to be left running")
                } catch (e: ScriptLeftRunningException) {
                    assertEquals(200.milliseconds, e.timeout)
                }
            }
            assertEquals(1, stuck.get())
            // With no dialog to close, nothing can release the block, so the call returns right after the grace.
            assertTrue("returned after ${elapsed}ms, expected about timeout + one grace", elapsed < 1_000)
            release.complete(Unit)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failing block rethrows and leaves the detach scope usable`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = detachScope()
        try {
            try {
                runBoundedByTimeout(
                    timeout = 5.seconds,
                    grace = 100.milliseconds,
                    detachIn = scope,
                    closeDialogsOpenedDuringRun = { emptyList() },
                    onClosed = { },
                    onStuck = { },
                ) { error("script failed") }
                fail("expected the script's exception")
            } catch (e: IllegalStateException) {
                assertEquals("script failed", e.message)
            }
            assertTrue("a failing script must not cancel the long-lived scope", scope.isActive)
            val next = runBoundedByTimeout(
                timeout = 5.seconds,
                grace = 100.milliseconds,
                detachIn = scope,
                closeDialogsOpenedDuringRun = { emptyList() },
                onClosed = { },
                onStuck = { },
            ) { "next run" }
            assertEquals("next run", next)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling the call cancels the detached block`(): Unit = timeoutRunBlocking(10.seconds) {
        val scope = detachScope()
        try {
            val blockCancelled = CompletableDeferred<Unit>()
            val caller = launch {
                runBoundedByTimeout(
                    timeout = 5.seconds,
                    grace = 100.milliseconds,
                    detachIn = scope,
                    closeDialogsOpenedDuringRun = { emptyList() },
                    onClosed = { },
                    onStuck = { },
                ) {
                    try {
                        awaitCancellation()
                    } finally {
                        blockCancelled.complete(Unit)
                    }
                }
            }
            delay(100.milliseconds)
            caller.cancel()
            withTimeout(2.seconds) { blockCancelled.await() }
        } finally {
            scope.cancel()
        }
    }
}
