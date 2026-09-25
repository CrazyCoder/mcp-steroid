/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
@file:Suppress("UnstableApiUsage")

package com.jonnyzzz.mcpSteroid.split.backend

import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectIdOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.server.describeSelfBackend
import com.jonnyzzz.mcpSteroid.server.localProjectNameFor
import com.jonnyzzz.mcpSteroid.server.split.BridgedOutcome
import com.jonnyzzz.mcpSteroid.server.split.executeBridgedTool
import com.jonnyzzz.mcpSteroid.split.BridgeEvent
import com.jonnyzzz.mcpSteroid.split.BridgeToolRequest
import com.jonnyzzz.mcpSteroid.split.ProjectKeyEntry
import com.jonnyzzz.mcpSteroid.split.SteroidBridgeApi
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class SteroidBridgeApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<SteroidBridgeApi>()) { SteroidBridgeApiImpl() }
    }
}

/** Runs forwarded tool calls through this backend's own tool registry. */
internal class SteroidBridgeApiImpl : SteroidBridgeApi {
    override suspend fun callTool(request: BridgeToolRequest): Flow<BridgeEvent> {
        val server = SteroidsMcpServer.getInstance().also { it.ensureToolsRegistered() }
        val arguments = McpJson.decodeFromString(JsonObject.serializer(), request.argumentsJson)
        val params = ToolCallParams(
            name = request.name,
            arguments = arguments,
            rawArguments = buildJsonObject {
                put("name", request.name)
                put("arguments", arguments)
            },
            trustedArguments = McpJson.decodeFromString(JsonObject.serializer(), request.trustedArgumentsJson),
        )
        return executeBridgedTool(server.getServer(), params).map { outcome ->
            when (outcome) {
                is BridgedOutcome.Progress -> BridgeEvent.Progress(outcome.message)
                is BridgedOutcome.Result -> BridgeEvent.Result(McpJson.encodeToString(ToolCallResult.serializer(), outcome.result))
            }
        }
    }

    // The backend has no frontend bridge, so its keys are always the local ones.
    override suspend fun projectKeys(): List<ProjectKeyEntry> =
        ProjectManager.getInstance().openProjects.mapNotNull { project ->
            project.projectIdOrNull()?.let { ProjectKeyEntry(localProjectNameFor(project), it, project.basePath) }
        }

    override suspend fun backendSelfJson(): String =
        McpJson.encodeToString(BackendRef.serializer(), describeSelfBackend().selfBackendRef())
}
