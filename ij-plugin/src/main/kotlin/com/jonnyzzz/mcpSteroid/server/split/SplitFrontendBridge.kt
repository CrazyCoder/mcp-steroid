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

    /** Fetches the backend's `project_name` keys and paths; [backendKeyFor] and [backendPathFor] read the result. */
    suspend fun refreshProjectKeys()

    /** The backend's `project_name` for this frontend [project], or null when the backend does not know it. */
    fun backendKeyFor(project: Project): String?

    /**
     * The backend's base path for this frontend [project], or null when the backend does not know it. The client's
     * own `basePath` is a synthetic folder under its config directory.
     */
    fun backendPathFor(project: Project): String?

    /** The backend's `backends[]` entry, for frontend responses that list both sides. */
    suspend fun backendSelf(): BackendRef?
}

val SPLIT_FRONTEND_BRIDGE_EP: ExtensionPointName<SplitFrontendBridge> =
    ExtensionPointName("com.jonnyzzz.mcpSteroid.splitFrontendBridge")

/**
 * The bridge, only in a Split Mode frontend. The `mcp-steroid.frontend` module also loads in a monolith,
 * where its extension is registered but there is no separate backend to reach.
 */
fun activeSplitFrontendBridge(): SplitFrontendBridge? =
    if (currentSplitRole() == SplitRole.FRONTEND) SPLIT_FRONTEND_BRIDGE_EP.extensionList.firstOrNull() else null
