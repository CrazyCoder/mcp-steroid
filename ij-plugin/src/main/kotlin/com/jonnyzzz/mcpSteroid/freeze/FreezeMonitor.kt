/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.diagnostic.IdePerformanceListener
import com.intellij.diagnostic.PerformanceWatcher
import com.intellij.diagnostic.ThreadDump
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.execution.RunningExecutions
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.split.SplitRole
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.TestOnly
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One UI freeze, as the platform's freeze detector reports it, with who holds the lock. */
data class Freeze(
    val id: Int,
    /** When the platform reported the freeze, which it does once the UI has not responded for its threshold. */
    val detectedAtMs: Long,
    val thresholdMs: Long,
    val reportDir: Path?,
    val analysis: FreezeAnalysis?,
    /** The execution id of each script class seen holding the lock, kept after the execution ends. */
    val executions: Map<String, String> = emptyMap(),
    /** The executions Steroid cancelled because they held the lock, with the time it cancelled each. */
    val cancelled: Map<String, Long> = emptyMap(),
    /** Set once the UI responds again. */
    val durationMs: Long? = null,
) {
    val frozenSinceMs: Long get() = detectedAtMs - thresholdMs
}

/**
 * Tells agents when the IDE's UI stops responding, and why, so a call does not simply hang.
 *
 * The platform's detector reports a freeze once a UI event has run past its threshold (5 s by default)
 * and dumps threads while it lasts; [FreezeListener] forwards those reports here. Each dump is read for
 * who holds the lock. A steroid_execute_code script that holds a read lock the UI waits for is cancelled.
 * [guard] puts the freeze in front of every tool result, and answers a call that is still waiting once
 * the freeze has been known for [EARLY_ANSWER_MS]. It also carries the [IdeErrors] notice.
 */
@Service(Service.Level.APP)
class FreezeMonitor(private val scope: CoroutineScope) {
    private val log = thisLogger()
    private val ids = AtomicInteger()
    private val reported = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    @Volatile
    var active: Freeze? = null
        private set

    @Volatile
    private var latest: Freeze? = null

    fun started(reportDir: Path) {
        val threshold = runCatching { PerformanceWatcher.getInstance().unresponsiveInterval.toLong() }.getOrDefault(DEFAULT_THRESHOLD_MS)
        val threads = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false).map(ThreadSample::of)
        publish(Freeze(ids.incrementAndGet(), System.currentTimeMillis(), threshold, reportDir, FreezeAnalysis.of(threads)))
    }

    fun dumped(dump: ThreadDump) {
        val freeze = active ?: return
        publish(freeze.copy(analysis = FreezeAnalysis.of(dump.threadInfos.map(ThreadSample::of))))
    }

    fun finished(durationMs: Long, reportDir: Path?) {
        val freeze = active ?: return
        val ended = freeze.copy(durationMs = durationMs, reportDir = reportDir ?: freeze.reportDir)
        latest = ended
        active = null
        log.info("UI freeze ended after $durationMs ms: " + render(ended, System.currentTimeMillis(), RunningExecutions::forScriptClass))
    }

    private fun publish(freeze: Freeze) {
        val executions = freeze.executions.toMutableMap()
        val cancelled = freeze.cancelled.toMutableMap()
        for (scriptClass in freeze.analysis?.scriptClasses.orEmpty()) {
            val executionId = executions[scriptClass] ?: RunningExecutions.forScriptClass(scriptClass) ?: continue
            executions[scriptClass] = executionId
            if (executionId in cancelled) continue
            if (RunningExecutions.cancel(executionId, "the IDE's UI froze while this execution held a read lock")) {
                cancelled[executionId] = System.currentTimeMillis()
                log.warn("Cancelled execution $executionId: it holds a read lock during a UI freeze")
            }
        }
        // A script that blocks the UI thread itself is named, not cancelled: its code on that thread has already
        // left the coroutine that cancellation reaches.
        freeze.analysis?.uiScriptClass?.let { cls ->
            if (cls !in executions) RunningExecutions.forScriptClass(cls)?.let { executions[cls] = it }
        }
        val updated = freeze.copy(executions = executions, cancelled = cancelled)
        latest = updated
        active = updated
    }

    /** The freeze to show [session] now: the current one, or one that ended since it was last told. */
    fun noticeFor(session: Any): String? {
        val now = System.currentTimeMillis()
        val freeze = active ?: latest?.takeIf { now - it.detectedAtMs < RECENT_MS && reported[session] != it.id } ?: return null
        if (freeze.durationMs != null) reported[session] = freeze.id
        return render(freeze, now, RunningExecutions::forScriptClass, side = sideOf(currentSplitRole()))
    }

    @TestOnly
    internal var ideErrors: () -> IdeErrors? = IdeErrors::getInstanceOrNull

    /**
     * Runs [call] and puts any freeze, and the errors the IDE logged since [session]'s last call, in front
     * of its result. A call still running once a freeze has been known for [EARLY_ANSWER_MS] is answered
     * with the freeze instead, and keeps running in the IDE. [reportsIdeErrors] tells that the call's own
     * result lists the errors logged while it ran, as steroid_execute_code does.
     */
    suspend fun guard(session: Any, reportsIdeErrors: Boolean = false, call: suspend () -> ToolCallResult): ToolCallResult {
        val errors = ideErrors()
        val startedAtMs = System.currentTimeMillis()
        fun notices() = listOfNotNull(noticeFor(session), errors?.noticeFor(session)).joinToString("").ifEmpty { null }

        val run = scope.async(currentCoroutineContext().minusKey(Job)) { call() }
        try {
            while (withTimeoutOrNull(POLL_MS) { run.join() } == null) {
                val freeze = active ?: continue
                if (System.currentTimeMillis() - freeze.detectedAtMs < EARLY_ANSWER_MS) continue
                val notice = notices() ?: continue
                return ToolCallResult(listOf(ContentItem.Text(notice + STILL_RUNNING)), isError = true)
            }
            val result = run.await()
            if (reportsIdeErrors) errors?.reportedBy(session, startedAtMs, System.currentTimeMillis())
            val notice = notices() ?: return result
            return result.copy(content = listOf(ContentItem.Text(notice)) + result.content)
        } catch (e: CancellationException) {
            run.cancel(e)
            // The caller's own cancellation propagates; a call cancelled inside the IDE, as a freeze
            // cancels the execution that holds its lock, is answered.
            currentCoroutineContext().ensureActive()
            val text = (notices() ?: "") + "The call was cancelled inside the IDE: ${e.message}"
            return ToolCallResult(listOf(ContentItem.Text(text)), isError = true)
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 5_000L
        const val EARLY_ANSWER_MS = 2_000L
        private const val POLL_MS = 250L
        private const val RECENT_MS = 10 * 60_000L
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        const val STILL_RUNNING =
            "This call has not finished and keeps running in the IDE; its result is lost. Call again once the freeze ends."

        fun getInstanceOrNull(): FreezeMonitor? = ApplicationManager.getApplication()?.let { service<FreezeMonitor>() }

        /** Which process of a Split Mode pair this is, for the notices; `null` in a regular IDE. */
        fun sideOf(role: SplitRole): String? = when (role) {
            SplitRole.BACKEND -> "the backend"
            SplitRole.FRONTEND -> "the JetBrains Client"
            SplitRole.MONOLITH -> null
        }

        /**
         * The freeze as agents read it. [executionFor] maps a script class in a dump to its execution id, and
         * [isRunning] tells whether that execution's call is still running. [side] names the process in
         * Split Mode, as [sideOf] gives it.
         */
        fun render(
            freeze: Freeze,
            nowMs: Long,
            executionFor: (String) -> String? = RunningExecutions::forScriptClass,
            isRunning: (String) -> Boolean = RunningExecutions::isRunning,
            side: String? = null,
        ): String = buildString {
            val since = TIME.format(Instant.ofEpochMilli(freeze.frozenSinceMs))
            val threshold = freeze.thresholdMs / 1000
            val where = side?.let { " in $it" }.orEmpty()
            if (freeze.durationMs == null) {
                append("IDE FREEZE$where: the IDE's UI has not responded for ${(nowMs - freeze.frozenSinceMs) / 1000} s, since $since ")
                append("(the IDE reports a freeze after $threshold s). Calls that need the UI wait until it ends.")
            } else {
                append("IDE FREEZE$where (ended): the IDE's UI did not respond for ${freeze.durationMs / 1000} s, from $since.")
            }
            val analysis = freeze.analysis
            val uiScript = analysis?.uiScriptClass
            when {
                analysis == null -> Unit
                analysis.uiWaitsForLock && analysis.writer != null ->
                    append(" The UI thread waits for a write action on thread \"${analysis.writer}\", which waits for these threads to release their read locks:")
                analysis.uiWaitsForLock -> append(" The UI thread waits for a lock held by:")
                uiScript != null -> {
                    val executionId = freeze.executions[uiScript] ?: executionFor(uiScript)
                    val script = if (executionId != null) "steroid_execute_code execution $executionId" else "a steroid_execute_code script"
                    append(" The UI thread runs code of $script at ${analysis.uiScriptLine}, such as a block it passed to invokeLater.")
                }
                analysis.uiDoing.isNotEmpty() -> append(" The UI thread is busy, not waiting for a lock: ${analysis.uiDoing.joinToString(" <- ")}.")
            }
            for (holder in analysis?.holders.orEmpty()) {
                append("\n- ")
                val executionId = holder.scriptClass?.let { freeze.executions[it] ?: executionFor(it) }
                val cancelledAt = executionId?.let { freeze.cancelled[it] }
                when {
                    executionId != null -> {
                        append("steroid_execute_code execution $executionId at ${holder.scriptLine}")
                        if (cancelledAt != null) append(", cancelled by Steroid at ${TIME.format(Instant.ofEpochMilli(cancelledAt))}")
                        if (freeze.durationMs == null && (cancelledAt != null || !isRunning(executionId))) {
                            append("; its call has ended, but the code on this thread does not check for cancellation and still runs")
                        }
                    }
                    holder.scriptClass != null -> append("a steroid_execute_code script at ${holder.scriptLine}")
                    else -> append("thread \"${holder.thread}\"" + if (holder.running) " (running)" else "")
                }
                if (holder.doing.isNotEmpty()) append(": ${holder.doing.joinToString(" <- ")}")
            }
            freeze.reportDir?.let { append("\nThe IDE's thread dumps of this freeze: $it") }
            append('\n')
        }
    }
}

/** Forwards the platform's freeze reports to [FreezeMonitor]. */
class FreezeListener : IdePerformanceListener {
    override fun uiFreezeStarted(reportDir: Path) = service<FreezeMonitor>().started(reportDir)
    override fun dumpedThreads(toFile: Path, dump: ThreadDump) = service<FreezeMonitor>().dumped(dump)
    override fun uiFreezeFinished(durationMs: Long, reportDir: Path?) = service<FreezeMonitor>().finished(durationMs, reportDir)
}
