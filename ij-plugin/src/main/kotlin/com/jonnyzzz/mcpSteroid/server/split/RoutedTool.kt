/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import kotlinx.coroutines.CancellationException

/** Runs [delegate] locally or forwards the call to the backend, per [routeTool]. */
class RoutedTool(
    private val delegate: McpTool,
    private val role: () -> SplitRole,
    private val bridge: () -> SplitFrontendBridge?,
) : McpTool by delegate {
    override suspend fun call(context: ToolCallContext): ToolCallResult {
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
            bridge.forward(context.params, context.mcpProgressReporter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolCallResult.errorResult("The backend is not connected or the call to it failed: ${e.message}")
        }
    }
}
