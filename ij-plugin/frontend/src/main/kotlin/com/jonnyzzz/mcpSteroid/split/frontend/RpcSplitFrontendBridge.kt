/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
@file:Suppress("UnstableApiUsage")

package com.jonnyzzz.mcpSteroid.split.frontend

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.projectIdOrNull
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.split.BackendReachPolicy
import com.jonnyzzz.mcpSteroid.server.split.SplitFrontendBridge
import com.jonnyzzz.mcpSteroid.split.BridgeEvent
import com.jonnyzzz.mcpSteroid.split.BridgeToolRequest
import com.jonnyzzz.mcpSteroid.split.SteroidBridgeApi
import fleet.rpc.client.durable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Reaches the backend over [SteroidBridgeApi].
 *
 * `durable {}` retries a call with no at-most-once guarantee and keeps retrying while the backend
 * service is unresolved, for example when the backend lacks this plugin. So only the idempotent
 * calls use it, and always under a timeout from [reach]. [forward] never retries: a retried
 * `execute_code` would run the script twice.
 */
internal class RpcSplitFrontendBridge : SplitFrontendBridge {
    // Replaced whole, never cleared in place: parallel tool calls read it while another call refreshes it.
    @Volatile
    private var keys: Map<ProjectId, String> = emptyMap()
    private val reach = BackendReachPolicy(full = 15.seconds, short = 1.seconds, quietPeriod = 30.seconds)

    override suspend fun forward(params: ToolCallParams, progress: McpProgressReporter): ToolCallResult {
        // Proves the backend is reachable before a call that cannot be retried, and refreshes the keys.
        refreshProjectKeys()
        val request = BridgeToolRequest(
            name = params.name,
            argumentsJson = McpJson.encodeToString(JsonObject.serializer(), params.arguments),
            trustedArgumentsJson = McpJson.encodeToString(JsonObject.serializer(), params.trustedArguments),
        )
        var result: ToolCallResult? = null
        SteroidBridgeApi.getInstance().callTool(request).collect { event ->
            when (event) {
                is BridgeEvent.Progress -> progress.report(event.message)
                is BridgeEvent.Result -> result = McpJson.decodeFromString(ToolCallResult.serializer(), event.toolCallResultJson)
            }
        }
        return result ?: error("the backend closed the call without a result")
    }

    override suspend fun refreshProjectKeys() {
        keys = reachBackend { projectKeys() }.associate { it.projectId to it.projectName }
    }

    override fun backendKeyFor(project: Project): String? = project.projectIdOrNull()?.let { keys[it] }

    override suspend fun backendSelf(): BackendRef? = try {
        McpJson.decodeFromString(BackendRef.serializer(), reachBackend { backendSelfJson() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        thisLogger().warn("Could not describe the backend for backends[]", e)
        null
    }

    /**
     * An idempotent call under the [reach] timeout. A timeout becomes an [IllegalStateException], not
     * a `TimeoutCancellationException`, so callers do not mistake it for their own cancellation.
     */
    private suspend fun <T : Any> reachBackend(call: suspend SteroidBridgeApi.() -> T): T {
        val timeout = reach.timeout()
        val result = withTimeoutOrNull(timeout) { durable { SteroidBridgeApi.getInstance().call() } }
        if (result == null) {
            reach.onFailure()
            throw IllegalStateException(
                "the backend did not answer within $timeout. Either it is disconnected, " +
                    "or MCP Steroid Plus is not installed on the backend side."
            )
        }
        reach.onSuccess()
        return result
    }
}
