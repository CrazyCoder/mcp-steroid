/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.UiParams
import com.jonnyzzz.mcpSteroid.server.UiToolHandler
import kotlinx.serialization.json.put

class DevrigUiToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
) : UiToolHandler {
    override suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult {
        val route = routing.requireProject(projectName)
        return bridge.callProjectTool(route, "steroid_ui") {
            put("task_id", params.taskId)
            put("reason", params.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            params.windowId?.let { put("window_id", it) }
            params.steps?.let { put("steps", it) }
            params.snapshot?.let { put("snapshot", it.wire) }
            put("max_nodes", params.maxNodes)
            put("trace", params.trace)
            params.side?.let { put("side", it) }
        }
    }
}
