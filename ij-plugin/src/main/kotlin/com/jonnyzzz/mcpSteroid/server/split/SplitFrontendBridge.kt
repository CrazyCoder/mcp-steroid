/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter

/**
 * Reaches the backend from a Split Mode frontend. Core cannot see content-module classes, so the
 * `mcp-steroid.frontend` module implements this over platform RPC and registers it as an extension.
 */
interface SplitFrontendBridge {
    suspend fun forward(params: ToolCallParams, progress: McpProgressReporter): ToolCallResult

    /** Fetches the backend's `project_name` keys; [backendKeyFor] reads the result. */
    suspend fun refreshProjectKeys()

    /** The backend's `project_name` for this frontend [project], or null when the backend does not know it. */
    fun backendKeyFor(project: Project): String?

    /** The backend's `backends[]` entry, for frontend responses that list both sides. */
    suspend fun backendSelf(): BackendRef?
}

val SPLIT_FRONTEND_BRIDGE_EP: ExtensionPointName<SplitFrontendBridge> =
    ExtensionPointName("com.jonnyzzz.mcpSteroid.splitFrontendBridge")

fun activeSplitFrontendBridge(): SplitFrontendBridge? = SPLIT_FRONTEND_BRIDGE_EP.extensionList.firstOrNull()
