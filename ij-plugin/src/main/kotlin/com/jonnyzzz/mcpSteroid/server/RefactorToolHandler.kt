/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.refactor.RefactorEngine
import com.jonnyzzz.mcpSteroid.refactor.RefactorFailure
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class RefactorToolHandlerIJ : RefactorToolHandler {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handleRefactor(projectName: String, params: RefactorParams): ToolCallResult {
        val project = service<ProjectScopedToolHandler>().resolveProject(projectName)
        val storage = project.executionStorage
        val executionId = storage.writeToolCall(
            toolName = "steroid_refactor",
            arguments = json.encodeToJsonElement(params).jsonObject,
            taskId = params.taskId,
            executionBackend = params.executionBackend,
        )
        storage.writeCodeExecutionData(executionId, "reason.txt", params.reason)
        val builder = ToolCallResult.builder()
        val started = TimeSource.Monotonic.markNow()
        val header = "execution_id: ${executionId.executionId}"
        // Resolution and inspections are incomplete while the IDE warms up after a start or a sync: wait a little,
        // and say what still runs, so a short usage list is not taken for the whole truth.
        val busy = IdeBackgroundActivity.awaitIdle(project, BUSY_WAIT_MS)
        val note = busy.note()?.let { "\n$it" } ?: ""
        // inspect stops itself after RefactorEngine.SCOPE_TIMEOUT and returns what it found by then.
        val timeoutMs = if (params.op == RefactorOp.INSPECT) INSPECT_TIMEOUT_MS else TIMEOUT_MS
        return try {
            val text = withTimeout(timeoutMs.milliseconds) { RefactorEngine(project).run(params) }
            val result = "$header (${started.elapsedNow().inWholeMilliseconds} ms)$note\n$text"
            storage.writeCodeExecutionData(executionId, "refactor.txt", result)
            builder.addTextContent(result).build()
        } catch (e: RefactorFailure) {
            storage.writeCodeErrorEvent(executionId, e.message ?: "failed")
            builder.addTextContent("$header$note\nFAILED: ${e.message}").markAsError().build()
        } catch (e: TimeoutCancellationException) {
            val running = IdeBackgroundActivity.running(project)
            val message = "steroid_refactor did not finish within ${timeoutMs / 1000} s" +
                if (running.isEmpty()) "" else "; the IDE is busy with ${running.joinToString("; ")}"
            storage.writeCodeErrorEvent(executionId, message)
            builder.addTextContent("$header\nERROR: $message").markAsError().build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "steroid_refactor failed: ${e.javaClass.simpleName}: ${e.message}"
            storage.writeCodeErrorEvent(executionId, message)
            builder.addTextContent("$header\nERROR: $message").markAsError().build()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
        const val INSPECT_TIMEOUT_MS = 360_000L
        const val BUSY_WAIT_MS = 30_000L
    }
}
