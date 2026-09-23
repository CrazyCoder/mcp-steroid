/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How [awaitRefreshUnlessModal] ended. */
internal enum class RefreshWait { DONE, SKIPPED_MODAL, STOPPED_MODAL, TIMED_OUT }

/**
 * Runs [refresh] in [launchIn] and waits for it, but never behind a modal dialog.
 *
 * A VFS refresh fires its events in a non-modal write action, which the platform holds back until
 * every modal dialog closes (threading model: `ModalityState.nonModal()` work runs only after all
 * modal dialogs are closed). Awaiting it under a dialog therefore always costs the whole [cap] and
 * refreshes nothing. When [isModal] is true at the start, no refresh starts; when it turns true
 * mid-wait, the wait stops and the refresh finishes on its own once the dialog closes. Running the
 * refresh under the dialog's modality instead would change the project model beneath a dialog the
 * caller does not own.
 */
internal suspend fun awaitRefreshUnlessModal(
    isModal: suspend () -> Boolean,
    refresh: suspend () -> Unit,
    launchIn: CoroutineScope,
    cap: Duration = 30.seconds,
    poll: Duration = 200.milliseconds,
): RefreshWait {
    if (isModal()) return RefreshWait.SKIPPED_MODAL
    val job = launchIn.launch { refresh() }
    return withTimeoutOrNull(cap) {
        var outcome: RefreshWait? = null
        while (outcome == null) {
            outcome = when {
                withTimeoutOrNull(poll) { job.join() } != null -> RefreshWait.DONE
                isModal() -> RefreshWait.STOPPED_MODAL
                else -> null
            }
        }
        outcome
    } ?: RefreshWait.TIMED_OUT
}

/**
 * Thrown by [runBoundedByTimeout] when the block ignored cancellation past its [timeout] and was
 * left running detached, so the caller could return.
 */
internal class ScriptLeftRunningException(val timeout: Duration) :
    RuntimeException("the script did not stop within its $timeout timeout and was left running")

/**
 * Runs [block] under [timeout], and makes the caller return near the deadline even when the block
 * ignores cancellation.
 *
 * Coroutine cancellation cannot interrupt a thread-blocking call. The common case is a script
 * parked in a modal dialog's nested event loop (`Messages.show…` on the EDT), which returns only
 * when the dialog closes. So [block] runs in [detachIn] with the caller's context, and the caller
 * waits for it. If it has not stopped [grace] after the deadline, [onStuck] gets its job for
 * diagnostics, then [closeDialogsOpenedDuringRun] closes the dialogs opened during the run, which
 * releases such a script; each batch of closed titles goes to [onClosed]. A block that still does not stop is left
 * running and [ScriptLeftRunningException] is thrown. A cooperative block behaves exactly as under
 * a plain [withTimeout]. Cancelling the caller cancels the block.
 */
internal suspend fun <T> runBoundedByTimeout(
    timeout: Duration,
    grace: Duration,
    detachIn: CoroutineScope,
    closeDialogsOpenedDuringRun: suspend () -> List<String>,
    onClosed: (List<String>) -> Unit,
    onStuck: suspend (run: Job) -> Unit,
    block: suspend CoroutineScope.() -> T,
): T {
    // A supervisor per run, so a failing script never cancels the long-lived detachIn scope.
    val supervisor = SupervisorJob(detachIn.coroutineContext[Job])
    val run = detachIn.async(currentCoroutineContext().minusKey(Job) + supervisor) { withTimeout(timeout, block) }
    supervisor.complete()
    suspend fun stoppedWithin(wait: Duration) = withTimeoutOrNull(wait) { run.join() } != null
    try {
        if (!stoppedWithin(timeout + grace)) {
            onStuck(run)
            var rounds = 0
            while (rounds++ < MAX_DIALOG_CLOSE_ROUNDS) {
                val closed = closeDialogsOpenedDuringRun()
                if (closed.isEmpty()) break
                onClosed(closed)
                if (stoppedWithin(grace)) break
            }
            if (!run.isCompleted) throw ScriptLeftRunningException(timeout)
        }
        return run.await()
    } catch (e: CancellationException) {
        run.cancel(e)
        throw e
    }
}

private const val MAX_DIALOG_CLOSE_ROUNDS = 5

/** How [awaitHighlighting] ended. */
internal enum class HighlightingWait { COMPLETED, TIMED_OUT }

/**
 * Waits for the daemon to finish analyzing an editor.
 *
 * A file the daemon analyzed before reports no completion until the daemon runs for it again, so
 * a wait on an unchanged file would always time out. When [isCompleted] is still false after
 * [restartAfter], [restart] runs once to start a new pass.
 */
internal suspend fun awaitHighlighting(
    isCompleted: suspend () -> Boolean,
    restart: suspend () -> Unit,
    timeout: Duration,
    restartAfter: Duration = 1.seconds,
    poll: Duration = 50.milliseconds,
): HighlightingWait {
    val start = TimeSource.Monotonic.markNow()
    var restarted = false
    return withTimeoutOrNull(timeout) {
        while (!isCompleted()) {
            if (!restarted && start.elapsedNow() >= restartAfter) {
                restart()
                restarted = true
            }
            delay(poll)
        }
        HighlightingWait.COMPLETED
    } ?: HighlightingWait.TIMED_OUT
}

/** Names closed dialogs for a message: `dialog 'A'`, `dialogs 'A', 'B'`, or `no dialog`. */
internal fun List<String>.describeDialogs(): String = when {
    isEmpty() -> "no dialog"
    size == 1 -> "dialog '${single()}'"
    else -> "dialogs " + joinToString { "'$it'" }
}
