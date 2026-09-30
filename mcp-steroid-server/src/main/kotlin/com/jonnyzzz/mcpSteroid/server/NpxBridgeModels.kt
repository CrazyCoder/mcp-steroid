/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NpxBridgeWindowsResponse(
    val windows: List<WindowInfo>,
    val backgroundTasks: List<ProgressTaskInfo>,
    val pid: Long,
    val mcpUrl: String,
    val instanceId: String,
    val seq: Long,
    val schemaVersion: String,
    val updatedAt: String
)

@Serializable
data class NpxBridgeToolCallRequest(
    val name: String,
    val arguments: JsonObject? = null,
    /**
     * The devrig session the call belongs to. Calls with the same key run in one IDE session, so what a tool
     * tells a session once, such as a freeze that ended or the errors the IDE logged since the last call, is not
     * repeated on each call. Absent from a devrig that predates it: each of its calls gets a session of its own.
     */
    val session: String? = null,
)
