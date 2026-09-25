/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

sealed interface BridgedOutcome {
    data class Progress(val message: String) : BridgedOutcome
    data class Result(val result: ToolCallResult) : BridgedOutcome
}

/**
 * Runs one tool call for a Split Mode frontend: its progress lines, then its result.
 * The flow is buffered without limit so that a progress burst never drops a `trySend`.
 */
fun executeBridgedTool(core: McpServerCore, params: ToolCallParams): Flow<BridgedOutcome> = channelFlow {
    val session = core.sessionManager.createSession()
    try {
        val progress = object : McpProgressReporter {
            override fun report(message: String) {
                trySend(BridgedOutcome.Progress(message))
            }
        }
        send(BridgedOutcome.Result(core.toolRegistry.callTool(params, session, progress)))
    } finally {
        core.sessionManager.removeSession(session.id)
    }
}.buffer(Channel.UNLIMITED)
