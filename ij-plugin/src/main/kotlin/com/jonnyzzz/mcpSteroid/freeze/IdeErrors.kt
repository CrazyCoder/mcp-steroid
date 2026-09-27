/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.execution.CapturedIdeException
import com.jonnyzzz.mcpSteroid.execution.ExceptionCaptureService
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One error the IDE logged, reduced to what an agent needs to decide whether to open the log. */
data class IdeError(val seq: Long, val atMs: Long, val summary: String, val pluginId: String?)

/**
 * Tells agents about errors the IDE logged between their calls, such as the ones behind the IDE's red
 * error balloon, in a few lines that point to the log for the full traces.
 *
 * steroid_execute_code already reports the errors logged while it runs, so those are not repeated.
 */
@Service(Service.Level.APP)
class IdeErrors(private val scope: CoroutineScope) {
    private val started = AtomicBoolean()
    private val errors = ArrayDeque<IdeError>()
    private var nextSeq = 1L
    private val seen = WeakHashMap<Any, Long>()
    /** The time spans of each session's recent calls whose results listed the errors logged in them. */
    private val reported = WeakHashMap<Any, MutableList<LongRange>>()

    /** Starts collecting; errors logged before this are not known. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { service<ExceptionCaptureService>().exceptions.collect(::add) }
    }

    fun add(e: CapturedIdeException) {
        if (isRemoteCopy(e.throwable)) return
        add(summaryOf(e.throwable), e.timestamp.toEpochMilli(), e.pluginId)
    }

    fun add(summary: String, atMs: Long, pluginId: String?) = synchronized(errors) {
        errors.addLast(IdeError(nextSeq++, atMs, summary, pluginId))
        while (errors.size > MAX_KEPT) errors.removeFirst()
    }

    /**
     * Records that a call of [session] listed the errors logged from [fromMs] to [toMs] in its own result.
     * Errors are matched by the time they were logged: one logged just before the call returns can reach
     * this service after the call's notice was built.
     */
    fun reportedBy(session: Any, fromMs: Long, toMs: Long) = synchronized(errors) {
        val spans = reported.getOrPut(session) { mutableListOf() }
        spans.removeAll { toMs - it.last > RECENT_MS }
        spans += fromMs..toMs
    }

    /**
     * The errors to tell [session] about, as a notice, and marks them told. A session's first call hears
     * about errors of the last [RECENT_MS].
     */
    fun noticeFor(session: Any, nowMs: Long = System.currentTimeMillis()): String? {
        val fresh = synchronized(errors) {
            val after = seen.put(session, nextSeq)
            val spans = reported[session].orEmpty()
            errors.filter { e ->
                (if (after != null) e.seq >= after else nowMs - e.atMs < RECENT_MS) && spans.none { e.atMs in it }
            }
        }
        return if (fresh.isEmpty()) null else render(fresh, logFile(), FreezeMonitor.sideOf(currentSplitRole()))
    }

    companion object {
        const val MAX_KEPT = 200
        const val MAX_LINES = 3
        const val RECENT_MS = 10 * 60_000L
        private const val MAX_MESSAGE = 160
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        /** The service, collecting from this call on. */
        fun getInstanceOrNull(): IdeErrors? = ApplicationManager.getApplication()?.let { service<IdeErrors>().also(IdeErrors::start) }

        private fun logFile(): Path? = runCatching { Path.of(PathManager.getLogPath(), "idea.log") }.getOrNull()

        /**
         * A split frontend logs a copy of each error the backend logs. The backend tells about its own
         * errors in the results of forwarded calls, so the copies would repeat them.
         */
        fun isRemoteCopy(t: Throwable): Boolean = t.javaClass.name == "com.intellij.diagnostic.RemoteSerializedThrowable"

        /** The exception class, the first line of its message, and where it was thrown. */
        fun summaryOf(t: Throwable): String {
            val message = t.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            val clipped = if (message.length > MAX_MESSAGE) message.take(MAX_MESSAGE) + "…" else message
            val frame = t.stackTrace.firstOrNull()?.let {
                if (it.className.startsWith(FreezeAnalysis.SCRIPT_CLASS_PREFIX)) " at a steroid_execute_code script, ${it.fileName}:${it.lineNumber}"
                else " at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})"
            }
            return t.javaClass.simpleName + (if (clipped.isNotEmpty()) ": $clipped" else "") + frame.orEmpty()
        }

        /**
         * Same errors on one line with a count, the newest groups first, at most [MAX_LINES] lines. [side] names
         * the process in Split Mode, as [FreezeMonitor.sideOf] gives it.
         */
        fun render(errors: List<IdeError>, logFile: Path?, side: String? = null): String = buildString {
            val groups = errors.groupBy { it.summary to it.pluginId }.values.sortedByDescending { g -> g.maxOf { it.seq } }
            val count = if (errors.size == 1) "1 error" else "${errors.size} errors"
            append("IDE ERRORS${side?.let { " in $it" }.orEmpty()}: the IDE logged $count since your last call")
            append(if (logFile != null) "; full stack traces are in $logFile." else ".")
            for (group in groups.take(MAX_LINES)) {
                val last = group.maxBy { it.seq }
                append("\n- ${TIME.format(Instant.ofEpochMilli(last.atMs))} ${last.summary}")
                last.pluginId?.let { append(" [plugin $it]") }
                if (group.size > 1) append(" (${group.size} times)")
            }
            if (groups.size > MAX_LINES) append("\n- and ${groups.size - MAX_LINES} more kinds of error")
            append('\n')
        }
    }
}
