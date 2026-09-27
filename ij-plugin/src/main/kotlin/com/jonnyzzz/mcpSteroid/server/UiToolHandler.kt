/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.ui.UiSession
import com.jonnyzzz.mcpSteroid.ui.UiSessionResult
import com.jonnyzzz.mcpSteroid.ui.UiTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class UiToolHandlerIJ : UiToolHandler {
    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }

    override suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult {
        val project = service<ProjectScopedToolHandler>().resolveProject(projectName)
        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_ui",
            arguments = json.encodeToJsonElement(params).jsonObject,
            taskId = params.taskId,
            executionBackend = params.executionBackend,
        )
        project.executionStorage.writeCodeExecutionData(executionId, "reason.txt", params.reason)
        val builder = ToolCallResult.builder()
        val scenario: UiScenario?
        val allSteps: List<UiStep>
        try {
            require(params.scenario == null || params.steps.isNullOrBlank()) { "pass steps or scenario, not both" }
            scenario = params.scenario?.let { loadScenario(project, it) }
            allSteps = scenario?.steps ?: params.steps?.trim()?.takeIf { it.isNotEmpty() }?.let(UiSteps::parse).orEmpty()
        } catch (e: IllegalArgumentException) {
            return builder.addTextContent("ERROR: ${e.message}").markAsError().build()
        }
        val from = params.fromStep ?: 1
        val to = params.toStep ?: allSteps.size
        if (allSteps.isNotEmpty() && (from < 1 || to > allSteps.size || from > to)) {
            return builder.addTextContent("ERROR: from_step and to_step must pick steps 1 to ${allSteps.size}, from_step first").markAsError().build()
        }
        val steps = if (allSteps.isEmpty()) allSteps else allSteps.subList(from - 1, to)
        val cleanup = scenario?.cleanup.orEmpty().takeIf { to == allSteps.size }.orEmpty()
        // Without steps there is nothing to diff against, so a diff asked for then is the snapshot itself. A scenario
        // reports its steps; the windows are shown only when a step fails, unless asked for.
        val mode = when {
            steps.isEmpty() && (params.snapshot == null || params.snapshot == UiSnapshotMode.DIFF) -> UiSnapshotMode.TREE
            scenario != null -> params.snapshot ?: UiSnapshotMode.NONE
            else -> params.snapshot ?: UiSnapshotMode.DIFF
        }
        val trace = if (params.trace) UiTrace(project.executionStorage.resolveExecutionDir(executionId).resolve("trace")) else null
        val session = UiSession(project, params.windowId, params.maxNodes, trace, params.taskId,
            artifacts = project.executionStorage.resolveExecutionDir(executionId))
        // The steps' own waits bound the call, plus an allowance for delivery and settling per step.
        val budgetMs = (steps + cleanup).sumOf { it.timeoutMs + STEP_ALLOWANCE_MS } + BASE_ALLOWANCE_MS
        return try {
            val started = TimeSource.Monotonic.markNow()
            val (result, cleanupResult) = withTimeout(budgetMs.milliseconds) {
                val main = session.run(steps, mode, firstIndex = from)
                // Cleanup puts the IDE back whether the steps passed or failed; a partial run leaves its state for the next.
                // Every cleanup step runs: one that finds nothing to undo, such as a close with no dialog open, stops none.
                val after = if (cleanup.isEmpty()) null
                else session.run(cleanup.map { it.copy(soft = true) }, UiSnapshotMode.NONE, labelPrefix = "cleanup step")
                main to after
            }
            val planned = allSteps.drop(from - 1)
            val judged = scenario != null || planned.any { it.bug != null }
            val verdict = if (judged) UiVerdict.of(planned, result.outcomes) else null
            val recording = if (scenario == null) record(project, executionId, params.taskId, result) else null
            val text = buildString {
                append("execution_id: ").append(executionId.executionId)
                append(" (").append(started.elapsedNow().inWholeMilliseconds).append(" ms)")
                scenario?.let { append('\n').append(header(it)) }
                result.reports.forEach { append('\n').append(it.line) }
                result.failure?.let { append('\n').append("FAILED ").append(it) }
                cleanupResult?.let { c ->
                    c.reports.forEach { append('\n').append(it.line) }
                    c.failure?.let { append('\n').append("CLEANUP FAILED ").append(it) }
                }
                verdict?.let { append('\n').append(it.line) }
                recording?.let { append('\n').append(it) }
                trace?.let { append('\n').append("trace: ").append(it.folder.resolve("trace.md")) }
                if (result.snapshot.isNotEmpty()) {
                    val title = if (result.failure == null && mode == UiSnapshotMode.DIFF) "changes" else "snapshot"
                    append("\n\n").append(title).append(":\n").append(result.snapshot)
                }
            }
            project.executionStorage.writeCodeExecutionData(executionId, "ui.txt", text)
            builder.addTextContent(text)
            val failed = when (verdict?.kind) {
                null -> result.failure != null
                UiVerdict.Kind.BROKEN, UiVerdict.Kind.FAILED -> true
                else -> false
            }
            if (failed) builder.markAsError()
            builder.build()
        } catch (e: TimeoutCancellationException) {
            val message = "steroid_ui did not finish within $budgetMs ms"
            project.executionStorage.writeCodeErrorEvent(executionId, message)
            builder.addTextContent("ERROR: $message").markAsError().build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "steroid_ui failed: ${e.message}"
            project.executionStorage.writeCodeErrorEvent(executionId, message)
            builder.addTextContent("ERROR: $message").markAsError().build()
        }
    }

    private suspend fun loadScenario(project: Project, path: String): UiScenario {
        val file = Path.of(path).let { p -> if (p.isAbsolute) p else project.basePath?.let { Path.of(it).resolve(p) } ?: p }
        val text = withContext(Dispatchers.IO) {
            require(Files.isRegularFile(file)) { "no scenario file at $file" }
            Files.readString(file)
        }
        return try {
            UiScenario.parse(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("scenario $file: ${e.message}", e)
        }
    }

    /** Names the scenario and warns when it was recorded on another IDE build, where steps may need repair. */
    private fun header(s: UiScenario): String {
        val here = ApplicationInfo.getInstance().build.asString()
        return buildString {
            append("scenario: ").append(s.title)
            s.issue?.let { append(" (").append(it).append(')') }
            append("; this IDE: ").append(here)
            if (s.ide != null && s.ide != here) {
                append(", recorded on ").append(s.ide).append(": a step that fails may need repair, by what its intent says")
            }
        }
    }

    /**
     * Appends the portable form of the steps that ran to the task's recording, one JSON step per line, and says where
     * it is. The steps of every call of the task add up to the steps of a scenario.
     */
    private suspend fun record(project: Project, executionId: com.jonnyzzz.mcpSteroid.storage.ExecutionId, taskId: String, result: UiSessionResult): String? {
        if (result.recorded.isEmpty()) return null
        val dir = project.executionStorage.resolveExecutionDir(executionId)
        val file = dir.parent.resolveSibling("ui-recordings").resolve(taskId.replace(UNSAFE, "_").take(120).ifEmpty { "task" } + ".jsonl")
        val total = withContext(Dispatchers.IO) {
            Files.writeString(dir.resolve("recorded.json"), pretty.encodeToString(JsonArray.serializer(), JsonArray(result.recorded)))
            Files.createDirectories(file.parent)
            Files.writeString(file, result.recorded.joinToString("") { json.encodeToString(JsonObject.serializer(), it) + "\n" },
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Files.readAllLines(file).count { it.isNotBlank() }
        }
        return "recorded: ${result.recorded.size} step(s) to $file ($total in this task), with refs replaced by names; the lines are the steps of a scenario"
    }

    companion object {
        // Delivery, settling, and up to 10 s for the dialog of an action named with an ellipsis.
        private const val STEP_ALLOWANCE_MS = 15_000L
        private const val BASE_ALLOWANCE_MS = 30_000L
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
