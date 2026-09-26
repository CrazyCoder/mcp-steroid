/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.ui.UiSession
import com.jonnyzzz.mcpSteroid.ui.UiTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class UiToolHandlerIJ : UiToolHandler {
    private val json = Json { ignoreUnknownKeys = true }

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
        val steps = try {
            params.steps?.trim()?.takeIf { it.isNotEmpty() }?.let(UiSteps::parse).orEmpty()
        } catch (e: IllegalArgumentException) {
            return builder.addTextContent("ERROR: ${e.message}").markAsError().build()
        }
        // Without steps there is nothing to diff against, so a diff asked for then is the snapshot itself.
        val mode = when {
            steps.isEmpty() && (params.snapshot == null || params.snapshot == UiSnapshotMode.DIFF) -> UiSnapshotMode.TREE
            else -> params.snapshot ?: UiSnapshotMode.DIFF
        }
        val trace = if (params.trace) UiTrace(project.executionStorage.resolveExecutionDir(executionId).resolve("trace")) else null
        val session = UiSession(project, params.windowId, params.maxNodes, trace)
        // The steps' own waits bound the call, plus an allowance for delivery and settling per step.
        val budgetMs = steps.sumOf { it.timeoutMs + STEP_ALLOWANCE_MS } + BASE_ALLOWANCE_MS
        return try {
            val started = TimeSource.Monotonic.markNow()
            val result = withTimeout(budgetMs.milliseconds) { session.run(steps, mode) }
            val text = buildString {
                append("execution_id: ").append(executionId.executionId)
                append(" (").append(started.elapsedNow().inWholeMilliseconds).append(" ms)")
                result.reports.forEach { append('\n').append(it.line) }
                result.failure?.let { append('\n').append("FAILED ").append(it) }
                trace?.let { append('\n').append("trace: ").append(it.folder.resolve("trace.md")) }
                if (result.snapshot.isNotEmpty()) {
                    val title = if (result.failure == null && mode == UiSnapshotMode.DIFF) "changes" else "snapshot"
                    append("\n\n").append(title).append(":\n").append(result.snapshot)
                }
            }
            project.executionStorage.writeCodeExecutionData(executionId, "ui.txt", text)
            builder.addTextContent(text)
            if (result.failure != null) builder.markAsError()
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

    companion object {
        // Delivery, settling, and up to 10 s for the dialog of an action named with an ellipsis.
        private const val STEP_ALLOWANCE_MS = 15_000L
        private const val BASE_ALLOWANCE_MS = 30_000L
    }
}
