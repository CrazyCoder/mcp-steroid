/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpSession
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import java.util.Collections
import java.util.WeakHashMap

sealed interface BridgedOutcome {
    data class Progress(val message: String) : BridgedOutcome
    data class Result(val result: ToolCallResult) : BridgedOutcome
}

private val bridgeSessions = Collections.synchronizedMap(WeakHashMap<McpServerCore, McpSession>())

/**
 * Runs one tool call for a Split Mode frontend: its progress lines, then its result.
 * The flow is buffered without limit so that a progress burst never drops a `trySend`.
 *
 * All bridged calls share one session, so what a tool tells a session once, such as a freeze that
 * ended or the errors the IDE logged since the last call, is not repeated on every forwarded call.
 */
fun executeBridgedTool(core: McpServerCore, params: ToolCallParams): Flow<BridgedOutcome> = channelFlow {
    val session = synchronized(bridgeSessions) { bridgeSessions.getOrPut(core) { core.sessionManager.createSession() } }
    val progress = object : McpProgressReporter {
        override fun report(message: String) {
            trySend(BridgedOutcome.Progress(message))
        }
    }
    send(BridgedOutcome.Result(core.toolRegistry.callTool(params, session, progress)))
}.buffer(Channel.UNLIMITED)
