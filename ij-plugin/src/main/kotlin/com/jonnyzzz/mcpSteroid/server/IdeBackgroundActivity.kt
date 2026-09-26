/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.execution.EXECUTION_TASK_TITLE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * The background tasks a person sees in the project's status bar, such as indexing, a Cargo or Gradle sync, or the
 * Rust plugin's "Preparing data for name resolution". After a start or a project open the IDE runs many of them, and
 * resolution, inspections and the build model are incomplete until they finish. Smart mode covers indexing only.
 */
internal object IdeBackgroundActivity {

    /** The project's running background tasks, one line each, such as "Preparing data for name resolution 40%". */
    suspend fun running(project: Project): List<String> = try {
        val name = projectNameFor(project)
        service<IdeWindowsCollector>().collect().backgroundTasks
            // Not the calls of MCP Steroid itself, this one included.
            .filter { (it.projectName == null || it.projectName == name) && it.title != EXECUTION_TASK_TITLE }
            .map { describe(it) }.filter { it.isNotBlank() }.distinct()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    fun describe(task: ProgressTaskInfo): String =
        listOf(task.title, task.text).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(": ") +
            (task.fraction?.let { " ${(it * 100).toInt()}%" } ?: "")

    /**
     * Waits up to [maxMs] for the project's background tasks to finish, calling [onWait] with the running ones while it
     * waits. Returns what ran when the wait started and what still runs at its end.
     */
    suspend fun awaitIdle(project: Project, maxMs: Long, onWait: (List<String>) -> Unit = {}): Wait {
        val first = running(project)
        if (first.isEmpty()) return Wait(emptyList(), emptyList(), 0)
        val started = TimeSource.Monotonic.markNow()
        var now = first
        while (now.isNotEmpty() && started.elapsedNow().inWholeMilliseconds < maxMs) {
            onWait(now)
            delay(POLL_MS)
            now = running(project)
        }
        return Wait(first, now, started.elapsedNow().inWholeMilliseconds)
    }

    /** A wait for background tasks: the ones seen at its start, the ones still running at its end, and how long. */
    class Wait(val seen: List<String>, val remaining: List<String>, val waitedMs: Long) {
        /** One line for the agent, or null when the IDE was idle. */
        fun note(): String? = when {
            seen.isEmpty() -> null
            remaining.isEmpty() -> "note: waited ${waitedMs / 1000} s for the IDE's background tasks: ${seen.joinToString("; ")}"
            else -> "note: the IDE is still busy with ${remaining.joinToString("; ")}; references and problems can be " +
                "incomplete until it finishes, so repeat the call then (steroid_list_windows lists backgroundTasks)"
        }
    }

    private const val POLL_MS = 500L
}
