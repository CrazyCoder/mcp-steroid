/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.ui.UiModel
import com.jonnyzzz.mcpSteroid.ui.UiRefs
import com.jonnyzzz.mcpSteroid.ui.UiSnapshotFormatter
import com.jonnyzzz.mcpSteroid.ui.UiWindowHeader
import com.jonnyzzz.mcpSteroid.ui.UiWindows
import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil
import com.jonnyzzz.mcpSteroid.vision.findComponentByWindowId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import javax.swing.SwingUtilities
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
        val steps = params.steps?.trim()
        if (!steps.isNullOrEmpty() && steps != "[]") {
            return builder
                .addTextContent("ERROR: this build of steroid_ui takes no steps yet. Call it without steps for a snapshot.")
                .markAsError()
                .build()
        }
        return try {
            val started = TimeSource.Monotonic.markNow()
            val withBounds = params.snapshot == UiSnapshotMode.FULL
            // ModalityState.any(): reading components is pure UI work, and it must also run while a modal
            // dialog is open, which is when an agent most needs the snapshot.
            val text = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                snapshotText(project, params.windowId, params.maxNodes, withBounds)
            }
            project.executionStorage.writeCodeExecutionData(executionId, "snapshot.txt", text)
            builder.addTextContent("execution_id: ${executionId.executionId} (${started.elapsedNow().inWholeMilliseconds} ms)\n$text").build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "steroid_ui failed: ${e.message}"
            project.executionStorage.writeCodeErrorEvent(executionId, message)
            builder.addTextContent("ERROR: $message").markAsError().build()
        }
    }

    private fun snapshotText(project: Project, windowId: String?, maxNodes: Int, withBounds: Boolean): String {
        val windows = if (windowId != null) {
            val component = findComponentByWindowId(windowId)
                ?: error("No IDE window found for window_id: $windowId")
            listOf(component as? Window ?: SwingUtilities.getWindowAncestor(component)
                ?: error("window_id $windowId is not in a window"))
        } else {
            val frame = WindowManager.getInstance().getFrame(project)
                ?: error("Project ${project.name} has no frame")
            UiWindows.projectWindows(frame)
        }
        val registry = service<UiRefs>().registry
        var budget = maxNodes
        return windows.joinToString("\n\n") { window ->
            val model = UiModel.build(window)
            val text = UiSnapshotFormatter.format(
                header(window, model.source, model.note),
                model.root,
                { registry.refFor(it.component) },
                budget.coerceAtLeast(1),
                withBounds,
            )
            budget -= text.listedCount
            text.text
        }
    }

    private fun header(window: Window, source: String, note: String?) = UiWindowHeader(
        windowId = WindowIdUtil.compute(window, window),
        title = (window as? Frame)?.title ?: (window as? Dialog)?.title,
        kind = when (window) {
            is Frame -> "frame"
            is Dialog -> "dialog"
            else -> "popup"
        },
        modal = (window as? Dialog)?.isModal == true,
        source = source,
        note = note,
    )
}
