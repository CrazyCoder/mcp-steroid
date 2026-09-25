/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.platform.ide.productMode.IdeProductMode
import com.intellij.platform.runtime.product.ProductMode
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Which part of a Split Mode IDE this process is. A monolith is both parts in one process. */
enum class SplitRole(val wire: String) { MONOLITH("monolith"), FRONTEND("frontend"), BACKEND("backend") }

/** `ProductMode` is a class, not an enum, on some supported builds, so it is compared by identity. */
fun classifySplitRole(mode: ProductMode): SplitRole = when (mode) {
    ProductMode.FRONTEND -> SplitRole.FRONTEND
    ProductMode.BACKEND -> SplitRole.BACKEND
    else -> SplitRole.MONOLITH
}

fun currentSplitRole(): SplitRole =
    runCatching { classifySplitRole(IdeProductMode.getInstance().currentMode) }.getOrDefault(SplitRole.MONOLITH)

enum class ToolSide { LOCAL, BACKEND }

const val SIDE_ARGUMENT = "side"

internal enum class Home { BACKEND, FRONTEND }

/** Where each tool runs in Split Mode. Every registered tool must be listed. */
internal val ROUTED_TOOLS: Map<String, Home> = mapOf(
    "steroid_list_projects" to Home.BACKEND,
    "steroid_open_project" to Home.BACKEND,
    "steroid_execute_code" to Home.BACKEND,
    "steroid_execute_feedback" to Home.BACKEND,
    "steroid_list_windows" to Home.FRONTEND,
    "steroid_take_screenshot" to Home.FRONTEND,
    "steroid_input" to Home.FRONTEND,
    "steroid_fetch_resource" to Home.FRONTEND,
)

fun routeTool(role: SplitRole, toolName: String, arguments: JsonObject): ToolSide {
    val home = ROUTED_TOOLS[toolName] ?: error("no routing for tool $toolName")
    val requested = arguments[SIDE_ARGUMENT]?.jsonPrimitive?.contentOrNull
    val wanted = when (requested) {
        null -> home
        "backend" -> Home.BACKEND
        "frontend" -> Home.FRONTEND
        else -> throw ToolCallErrorException("Unsupported side '$requested'. Use 'frontend' or 'backend'.")
    }
    return when (role) {
        SplitRole.MONOLITH -> ToolSide.LOCAL
        SplitRole.BACKEND -> {
            if (wanted == Home.FRONTEND && requested != null) throw ToolCallErrorException(
                "side=frontend was requested, but this endpoint is a Remote Development backend and no frontend is attached " +
                    "to it. When a JetBrains Client is connected, call the client's MCP endpoint instead."
            )
            ToolSide.LOCAL
        }
        SplitRole.FRONTEND -> if (wanted == Home.BACKEND) ToolSide.BACKEND else ToolSide.LOCAL
    }
}
