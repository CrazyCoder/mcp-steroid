/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.freeze.FreezeMonitor
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.server.ToolOutputContract
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Runs [delegate] locally or forwards the call to the backend, per [routeTool]. [FreezeMonitor.guard]
 * reports a UI freeze and the errors the IDE logged in the result, and answers a call that a freeze holds up.
 * A call with "output":"json" is answered with the [ToolOutputContract] envelope whatever happens.
 */
class RoutedTool(
    private val delegate: McpTool,
    private val role: () -> SplitRole,
    private val bridge: () -> SplitFrontendBridge?,
) : McpTool by delegate {
    override suspend fun call(context: ToolCallContext): ToolCallResult {
        // With "output":"json", every answer, an error too, is the ToolOutputContract envelope.
        val json = ToolOutputContract.wantsJson(context.params.arguments)
        if (!json) return guarded(context, json)
        return try {
            ToolOutputContract.wrap(delegate.name, guarded(context, json))
        } catch (e: ToolCallErrorException) {
            // Thrown before the tool answers, as for an unknown project; the registry would answer it as text.
            ToolOutputContract.wrap(delegate.name, e.toolCallResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutputContract.wrap(delegate.name, ToolCallResult.errorResult("${delegate.name} failed: ${e.message}"))
        }
    }

    private suspend fun guarded(context: ToolCallContext, json: Boolean): ToolCallResult {
        val monitor = FreezeMonitor.getInstanceOrNull() ?: return route(context)
        val reports = reportsOwnIdeErrors(role(), delegate.name, context.params.arguments)
        return monitor.guard(context.session, reportsIdeErrors = reports, jsonOutput = json, tool = delegate.name) { route(context) }
    }

    private suspend fun route(context: ToolCallContext): ToolCallResult {
        val currentRole = role()
        val side = routeTool(currentRole, delegate.name, context.params.arguments)
        if (currentRole != SplitRole.FRONTEND) return delegate.call(context)
        val bridge = bridge()
        if (side == ToolSide.LOCAL) {
            // A UI tool still works without the backend: project keys fall back to the local ones.
            try {
                bridge?.refreshProjectKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Could not refresh backend project keys; using local keys", e)
            }
            return delegate.call(context)
        }
        if (bridge == null) return ToolCallResult.errorResult(
            "This JetBrains Client has no MCP Steroid frontend module loaded, so it cannot reach the backend."
        )
        return try {
            bridge.forward(context.params, context.mcpProgressReporter, context.session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolCallResult.errorResult("The backend is not connected or the call to it failed: ${e.message}")
        }
    }

    internal companion object {
        private const val EXECUTE_CODE = "steroid_execute_code"

        /**
         * Whether the call's result lists the errors this process logged while it ran: steroid_execute_code
         * does for its own process, so not when a split frontend forwards it to the backend.
         */
        fun reportsOwnIdeErrors(role: SplitRole, toolName: String, arguments: JsonObject): Boolean =
            toolName == EXECUTE_CODE && (role != SplitRole.FRONTEND || routeTool(role, toolName, arguments) == ToolSide.LOCAL)
    }
}
