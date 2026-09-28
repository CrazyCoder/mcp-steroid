/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildProgressObservable
import com.intellij.build.BuildViewManager
import com.intellij.build.SyncViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.StartBuildEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap

/** One build, sync or build run that finished, as the IDE's Build and Sync tool windows show it. */
data class IdeBuild(
    val seq: Long,
    val atMs: Long,
    val project: String,
    val title: String,
    val failed: Boolean,
    val errors: List<String>,
    val errorCount: Int,
)

/**
 * Tells agents about builds that failed between their calls: the IDE's own build, a Maven or Gradle build, a Maven
 * or Gradle sync, and build tool run configurations, all of which report to the Build and Sync tool windows. A
 * notice gives the first errors with their files and lines, so an agent sees a broken build without reading the
 * tool window.
 */
@Service(Service.Level.APP)
class IdeBuilds {
    private val running = HashMap<Any, Running>()
    private val finished = ArrayDeque<IdeBuild>()
    private var nextSeq = 1L
    private val seen = WeakHashMap<Any, Long>()

    private class Running(val project: Project, var title: String) {
        val errors = LinkedHashSet<String>()
    }

    /**
     * Listens to the project's Build and Sync views, which every build and sync reports to. The platform's own
     * listener extension point for them is missing from the oldest supported IDE, so the views are asked directly.
     */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            val builds = getInstanceOrNull() ?: return
            for (view in listOf<BuildProgressObservable>(project.service<BuildViewManager>(), project.service<SyncViewManager>())) {
                view.addListener(BuildProgressListener { id, event -> builds.onEvent(project, id, event) }, project)
            }
        }
    }

    fun onEvent(project: Project, buildId: Any, event: BuildEvent) = synchronized(this) {
        when (event) {
            is StartBuildEvent -> running[buildId] = Running(project, event.buildDescriptor.title.ifBlank { event.message })
            is MessageEvent -> if (event.kind == MessageEvent.Kind.ERROR) {
                running.getOrPut(buildId) { Running(project, event.message) }.errors += errorLine(project, event)
            }
            is FinishBuildEvent -> {
                val build = running.remove(buildId) ?: Running(project, event.message)
                val result = event.result
                if (result is SkippedResult) return@synchronized
                (result as? FailureResult)?.failures.orEmpty().forEach { failure ->
                    failure.message?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { build.errors += clip(it) }
                }
                val failed = result is FailureResult || build.errors.isNotEmpty()
                add(project.name, build.title, failed, build.errors.toList(), event.eventTime)
            }
        }
    }

    fun add(project: String, title: String, failed: Boolean, errors: List<String>, atMs: Long = System.currentTimeMillis()) = synchronized(this) {
        finished.addLast(IdeBuild(nextSeq++, atMs, project, title, failed, errors.take(MAX_ERRORS), errors.size))
        while (finished.size > MAX_KEPT) finished.removeFirst()
    }

    /** The builds that finished, oldest first, the last [limit] of them. */
    fun recent(limit: Int = MAX_KEPT): List<IdeBuild> = synchronized(this) { finished.toList().takeLast(limit) }

    /**
     * The failed builds [session] has not been told about, as a notice, and marks every finished build told. A build
     * that a later build of the same project and title passed is left out: it is fixed already. A session's first call
     * hears about failures of the last [RECENT_MS].
     */
    fun noticeFor(session: Any, nowMs: Long = System.currentTimeMillis()): String? {
        val fresh = synchronized(this) {
            val after = seen.put(session, nextSeq)
            val all = finished.toList()
            all.filter { b ->
                b.failed && (if (after != null) b.seq >= after else nowMs - b.atMs < RECENT_MS) &&
                    all.none { later -> later.seq > b.seq && !later.failed && later.project == b.project && later.title == b.title }
            }
        }
        return if (fresh.isEmpty()) null else render(fresh, FreezeMonitor.sideOf(currentSplitRole()))
    }

    companion object {
        const val MAX_KEPT = 50
        const val MAX_LINES = 3
        const val MAX_ERRORS = 3
        const val RECENT_MS = 10 * 60_000L
        private const val MAX_MESSAGE = 160
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        fun getInstanceOrNull(): IdeBuilds? = ApplicationManager.getApplication()?.let { service<IdeBuilds>() }

        private fun clip(s: String) = if (s.length > MAX_MESSAGE) s.take(MAX_MESSAGE) + "…" else s

        /** An error as path:line: message, the path relative to the project where it is inside it. */
        private fun errorLine(project: Project, event: MessageEvent): String {
            val message = clip(event.message.lineSequence().firstOrNull()?.trim().orEmpty())
            val position = (event as? FileMessageEvent)?.filePosition ?: return message
            val file = position.file ?: return message
            return "${pathOf(project, file)}:${position.startLine + 1}: $message"
        }

        private fun pathOf(project: Project, file: File): String {
            val base = project.guessProjectDir()?.path ?: return file.path
            return FileUtil.getRelativePath(File(base), file)?.takeUnless { it.startsWith("..") }?.replace('\\', '/') ?: file.path
        }

        /** The builds a get lists, the newest first: each one's outcome and its first errors. */
        fun renderRecent(builds: List<IdeBuild>): String {
            if (builds.isEmpty()) return "no build or sync finished since the IDE started"
            return builds.sortedByDescending { it.seq }.joinToString("\n") { b ->
                buildString {
                    // The IDE's own build is titled with the project's name.
                    append("${TIME.format(Instant.ofEpochMilli(b.atMs))} ${b.title}")
                    if (b.title != b.project) append(" in ${b.project}")
                    append(": ")
                    append(if (!b.failed) "passed" else when (b.errorCount) { 0 -> "failed"; 1 -> "failed with 1 error"; else -> "failed with ${b.errorCount} errors" })
                    for (e in b.errors) append("\n  ").append(e)
                    if (b.errorCount > b.errors.size) append("\n  and ${b.errorCount - b.errors.size} more")
                }
            }
        }

        /** Each failed build on one line with its error count, then its first errors, the newest builds first. */
        fun render(builds: List<IdeBuild>, side: String? = null): String = buildString {
            val shown = builds.sortedByDescending { it.seq }
            append("BUILD FAILED${side?.let { " in $it" }.orEmpty()}: ")
            append(if (builds.size == 1) "a build failed" else "${builds.size} builds failed").append(" since your last call")
            append("; the Build tool window has the full output:")
            for (b in shown.take(MAX_LINES)) {
                append("\n- ${TIME.format(Instant.ofEpochMilli(b.atMs))} ${b.title}")
                if (builds.any { it.project != b.project }) append(" in ${b.project}")
                append(when (b.errorCount) { 0 -> ""; 1 -> ": 1 error"; else -> ": ${b.errorCount} errors" })
                for (e in b.errors) append("\n  ").append(e)
                if (b.errorCount > b.errors.size) append("\n  and ${b.errorCount - b.errors.size} more")
            }
            if (shown.size > MAX_LINES) append("\n- and ${shown.size - MAX_LINES} more failed builds")
            append('\n')
        }
    }
}
