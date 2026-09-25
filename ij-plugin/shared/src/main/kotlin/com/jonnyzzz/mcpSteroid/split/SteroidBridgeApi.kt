/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
@file:Suppress("UnstableApiUsage")

package com.jonnyzzz.mcpSteroid.split

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Split Mode frontend -> backend bridge. The frontend hosts the agent's MCP endpoint. */
@Rpc
interface SteroidBridgeApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): SteroidBridgeApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<SteroidBridgeApi>())
    }

    /** Runs one MCP tool call on the backend: zero or more [BridgeEvent.Progress], then one [BridgeEvent.Result]. */
    suspend fun callTool(request: BridgeToolRequest): Flow<BridgeEvent>

    /** The backend's `project_name` key for each open project, with the platform's shared [ProjectId]. */
    suspend fun projectKeys(): List<ProjectKeyEntry>

    /** The backend's `backends[]` entry as JSON (`BackendRef`, which lives in the main plugin module). */
    suspend fun backendSelfJson(): String
}

/** One MCP tool call. Arguments travel as JSON text because `JsonObject` is not an RPC payload type. */
@Serializable
data class BridgeToolRequest(val name: String, val argumentsJson: String, val trustedArgumentsJson: String)

@Serializable
sealed interface BridgeEvent {
    @Serializable
    data class Progress(val message: String) : BridgeEvent

    /** A serialized `ToolCallResult`. */
    @Serializable
    data class Result(val toolCallResultJson: String) : BridgeEvent
}

@Serializable
data class ProjectKeyEntry(val projectName: String, val projectId: ProjectId)
