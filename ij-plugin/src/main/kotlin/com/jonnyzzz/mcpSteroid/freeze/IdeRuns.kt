/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap

/**
 * The output of the processes the IDE runs from run configurations, applications, tests, Maven and Gradle tasks, kept
 * after their consoles close, so an agent reads a run's output without the Run tool window. Tells agents about a run
 * that exited with an error between their calls, with its last lines.
 */
@Service(Service.Level.APP)
class IdeRuns {
    /** One process run: its name as the Run tool window's tab shows it, and the tail of its output. */
    class Run(val seq: Long, val project: String, val name: String, val executor: String, val startedMs: Long) {
        internal val lines = ArrayDeque<String>()
        internal var dropped = 0
        private val partial = HashMap<String, StringBuilder>()
        @Volatile var exitCode: Int? = null
        @Volatile var endedMs: Long? = null
        /** Set when the user or the IDE stopped the process, whose exit code then tells nothing about the program. */
        @Volatile var stopped = false
        @Volatile var notStarted: String? = null

        @Synchronized
        internal fun append(text: String, stream: String) {
            val buffer = partial.getOrPut(stream) { StringBuilder() }
            buffer.append(text)
            while (true) {
                val end = buffer.indexOf('\n')
                if (end < 0) break
                add(buffer.substring(0, end).trimEnd('\r'), stream)
                buffer.delete(0, end + 1)
            }
        }

        @Synchronized
        internal fun flush() {
            for ((stream, buffer) in partial) if (buffer.isNotEmpty()) add(buffer.toString(), stream)
            partial.clear()
        }

        private fun add(line: String, stream: String) {
            lines.addLast(if (stream == STDERR) "$ERR_MARK$line" else line)
            while (lines.size > MAX_LINES) { lines.removeFirst(); dropped++ }
        }

        @Synchronized
        fun text(): List<String> = lines.toList()

        val failed get() = !stopped && (notStarted != null || (exitCode ?: 0) != 0)
    }

    private val runs = ArrayDeque<Run>()
    private var nextSeq = 1L
    private val seen = WeakHashMap<Any, Long>()

    /** Records the runs of one project, as the platform calls it for each project. */
    class Listener(private val project: Project) : ExecutionListener {
        private val runs = HashMap<ProcessHandler, Run>()

        override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
            val run = getInstanceOrNull()?.start(project.name, env.runProfile.name, executorId) ?: return
            synchronized(runs) { runs[handler] = run }
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val stream = when {
                        ProcessOutputType.isStderr(outputType) -> STDERR
                        ProcessOutputType.isStdout(outputType) -> "stdout"
                        else -> "system"
                    }
                    run.append(event.text, stream)
                }

                // A process that ends by itself passes here too, after it exited; one the user or the IDE stops passes
                // while it still runs.
                override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                    if ((event.processHandler as? BaseProcessHandler<*>)?.process?.isAlive == true) run.stopped = true
                }
            })
        }

        override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
            val runs = getInstanceOrNull() ?: return
            val run = runs.start(project.name, env.runProfile.name, executorId)
            run.notStarted = cause?.message?.lineSequence()?.firstOrNull() ?: "it did not start"
            run.endedMs = System.currentTimeMillis()
            runs.ended(run)
        }

        override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
            val run = synchronized(runs) { runs.remove(handler) } ?: return
            run.flush()
            run.exitCode = exitCode
            run.endedMs = System.currentTimeMillis()
            getInstanceOrNull()?.ended(run)
        }
    }

    fun start(project: String, name: String, executor: String): Run = synchronized(this) {
        Run(nextSeq++, project, name, executor, System.currentTimeMillis()).also {
            runs.addLast(it)
            while (runs.size > MAX_RUNS) runs.removeFirst()
        }
    }

    /** Marks [run] ended; a run keeps its place, so a notice reads runs by the order they ended. */
    fun ended(run: Run) = synchronized(this) { endedSeq[run] = nextEnded++ }

    private val endedSeq = WeakHashMap<Run, Long>()
    private var nextEnded = 1L

    /** The latest run of [project] named [name], or containing it, or the latest of all for "". */
    fun find(project: String, name: String): Run? = synchronized(this) {
        val mine = runs.filter { it.project == project }
        if (name.isBlank()) return mine.lastOrNull()
        mine.lastOrNull { it.name == name } ?: mine.lastOrNull { it.name.contains(name, ignoreCase = true) }
    }

    /** The runs of [project] a get names when it finds none, the latest first. */
    private fun names(project: String): List<String> = synchronized(this) { runs.filter { it.project == project }.map { it.name }.distinct().reversed() }

    /** A run's state and its last [count] lines, as a get of a console gives it. */
    fun report(project: String, name: String, count: Int): String {
        val run = find(project, name) ?: return names(project).let { known ->
            "no run${if (name.isBlank()) "" else " named \"$name\""} since the IDE started" +
                if (known.isEmpty()) "" else "; runs: ${known.take(10).joinToString { "\"$it\"" }}"
        }
        val lines = run.text()
        return buildString {
            append(header(run)).append(": ").append(lines.size + run.dropped).append(" lines")
            if (lines.size > count || run.dropped > 0) append(", the last ${minOf(count, lines.size)}")
            append("; stderr lines start with ").append(ERR_MARK.trim())
            // A command line with its class path runs to thousands of characters.
            for (line in lines.takeLast(count)) append('\n').append(if (line.length > MAX_LINE) line.take(MAX_LINE) + "…" else line)
        }
    }

    /** The failed runs [session] has not been told about, as a notice. A session's first call hears the recent ones. */
    fun noticeFor(session: Any, nowMs: Long = System.currentTimeMillis()): String? {
        val fresh = synchronized(this) {
            val after = seen.put(session, nextEnded)
            runs.filter { r ->
                val ended = endedSeq[r] ?: return@filter false
                r.failed && (if (after != null) ended >= after else nowMs - (r.endedMs ?: 0) < RECENT_MS)
            }
        }
        return if (fresh.isEmpty()) null else render(fresh, FreezeMonitor.sideOf(currentSplitRole()))
    }

    companion object {
        const val MAX_RUNS = 20
        const val MAX_LINES = 2_000
        const val RECENT_MS = 10 * 60_000L
        private const val NOTICE_RUNS = 3
        private const val NOTICE_LINES = 5
        private const val MAX_LINE = 500
        private const val STDERR = "stderr"
        private const val ERR_MARK = "! "
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        fun getInstanceOrNull(): IdeRuns? = ApplicationManager.getApplication()?.let { service<IdeRuns>() }

        private fun header(run: Run): String = buildString {
            append("'").append(run.name).append("' (").append(run.executor).append(')')
            append(" started ").append(TIME.format(Instant.ofEpochMilli(run.startedMs)))
            when {
                run.notStarted != null -> append(", did not start: ").append(run.notStarted)
                run.endedMs == null -> append(", still running")
                run.stopped -> append(", stopped at ").append(TIME.format(Instant.ofEpochMilli(run.endedMs!!)))
                else -> append(", exited with code ").append(run.exitCode).append(" at ").append(TIME.format(Instant.ofEpochMilli(run.endedMs!!)))
            }
        }

        /** Each failed run with its last lines, stderr first where it has any, the latest first. */
        fun render(runs: List<Run>, side: String? = null): String = buildString {
            append("RUN FAILED${side?.let { " in $it" }.orEmpty()}: ")
            append(if (runs.size == 1) "a run" else "${runs.size} runs").append(" failed since your last call; ")
            append("{\"action\":\"get\",\"console\":\"<name>\"} reads a run's output:")
            for (run in runs.reversed().take(NOTICE_RUNS)) {
                append("\n- ").append(header(run))
                val lines = run.text()
                val errors = lines.filter { it.startsWith(ERR_MARK) }
                for (line in (errors.ifEmpty { lines }).takeLast(NOTICE_LINES)) append("\n  ").append(line.take(200))
            }
            if (runs.size > NOTICE_RUNS) append("\n- and ${runs.size - NOTICE_RUNS} more failed runs")
            append('\n')
        }
    }
}
