/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.RefactorParams
import com.jonnyzzz.mcpSteroid.server.RefactorToolHandler
import kotlinx.serialization.json.put

class DevrigRefactorToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
) : RefactorToolHandler {
    override suspend fun handleRefactor(projectName: String, params: RefactorParams): ToolCallResult {
        val route = routing.requireProject(projectName)
        return bridge.callProjectTool(route, "steroid_refactor") {
            put("task_id", params.taskId)
            put("reason", params.reason)
            put("op", params.op.wire)
            params.file?.let { put("file", it) }
            params.line?.let { put("line", it) }
            params.column?.let { put("column", it) }
            params.symbol?.let { put("symbol", it) }
            put("nth", params.nth)
            params.newName?.let { put("new_name", it) }
            params.to?.let { put("to", it) }
            params.inspection?.let { put("inspection", it) }
            params.name?.let { put("name", it) }
            put("all", params.all)
            put("apply", params.apply)
        }
    }
}
