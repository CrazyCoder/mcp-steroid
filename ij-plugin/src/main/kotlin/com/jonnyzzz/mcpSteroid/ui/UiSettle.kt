/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Window
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class UiBarrierTimeout(ms: Long) : RuntimeException("the IDE did not process the input within $ms ms (the event thread is busy)")

/** Waiting for the IDE to take input and for the windows it opens or closes to settle. */
object UiSettle {
    private const val BARRIER_MS = 10_000L

    /**
     * Returns once every EDT task queued before it with any modality has run, or once an event loop nested in one
     * of them, such as a modal dialog's, reaches it.
     */
    suspend fun barrier() {
        val done = CompletableDeferred<Unit>()
        ApplicationManager.getApplication().invokeLater({ done.complete(Unit) }, ModalityState.any())
        try {
            withTimeout(BARRIER_MS.milliseconds) { done.await() }
        } catch (e: TimeoutCancellationException) {
            throw UiBarrierTimeout(BARRIER_MS)
        }
    }

    /**
     * Waits until no window has opened or closed and the event thread has not been busy for [quietMs], at most
     * [maxMs]. A busy event thread is building UI, such as a Settings page that loads after its window shows, so it
     * does not count as quiet.
     */
    suspend fun settle(quietMs: Long = 150, maxMs: Long = 1_000) {
        barrier()
        val started = TimeSource.Monotonic.markNow()
        var last = showingWindows()
        var stableSince = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < maxMs) {
            delay(50)
            val asked = TimeSource.Monotonic.markNow()
            val now = showingWindows()
            val busy = asked.elapsedNow().inWholeMilliseconds >= BUSY_MS
            if (now != last || busy) {
                last = now
                stableSince = TimeSource.Monotonic.markNow()
            } else if (stableSince.elapsedNow().inWholeMilliseconds >= quietMs) {
                return
            }
        }
    }

    /**
     * Waits up to [maxMs] for a window to open or close compared to [before], and returns whether one did, or whether
     * [stopWhen] says the wait is over without one. An action that opens a dialog may prepare it on a background
     * thread first, with an idle event thread meanwhile: Settings takes over a second on the first open after the
     * IDE starts.
     */
    suspend fun awaitWindowChange(before: Set<Window>, maxMs: Long, stopWhen: suspend () -> Boolean = { false }): Boolean {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < maxMs) {
            if (showingWindows() != before || stopWhen()) return true
            delay(50)
        }
        return false
    }

    /** An event thread that takes this long to answer is busy. */
    private const val BUSY_MS = 100L

    /** The showing windows, without hover popups: a tooltip is not a window a step opened. */
    suspend fun showingWindows(): Set<Window> = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
        Window.getWindows().filter { it.isShowing && !UiWindows.isHoverPopup(it) }.toSet()
    }
}
