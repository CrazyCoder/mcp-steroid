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

/**
 * The IDE's session for each agent session that reaches it through a bridge, by a key the bridge names it with: a
 * JetBrains Client's session id, or `devrig:<id>` for a devrig session. The most recently used [MAX_BRIDGE_SESSIONS]
 * are kept. The session manager keeps a session until it is removed, so an evicted one is removed there too; its
 * agent, should it come back, hears the recent notices again.
 */
internal class BridgeSessions(private val core: McpServerCore) {
    private val sessions = object : LinkedHashMap<String, McpSession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, McpSession>): Boolean {
            if (size <= MAX_BRIDGE_SESSIONS) return false
            core.sessionManager.removeSession(eldest.value.id)
            return true
        }
    }

    fun forClient(clientSessionId: String): McpSession = synchronized(sessions) {
        sessions.getOrPut(clientSessionId) { core.sessionManager.createSession() }
    }
}

internal const val MAX_BRIDGE_SESSIONS = 32

private val bridgeSessions = Collections.synchronizedMap(WeakHashMap<McpServerCore, BridgeSessions>())

/** The session of [core] for the bridged agent session [key], created on its first call. */
internal fun bridgeSession(core: McpServerCore, key: String): McpSession =
    bridgeSessions.getOrPut(core) { BridgeSessions(core) }.forClient(key)

/**
 * Runs one tool call for a Split Mode frontend: its progress lines, then its result.
 * The flow is buffered without limit so that a progress burst never drops a `trySend`.
 *
 * Each agent session of the client, [clientSessionId], has a backend session of its own. What a tool tells a session
 * once, such as a freeze that ended or the errors the IDE logged since the last call, is then told to every agent once,
 * and not repeated on each of its forwarded calls.
 */
fun executeBridgedTool(core: McpServerCore, params: ToolCallParams, clientSessionId: String): Flow<BridgedOutcome> = channelFlow {
    val session = bridgeSession(core, clientSessionId)
    val progress = object : McpProgressReporter {
        override fun report(message: String) {
            trySend(BridgedOutcome.Progress(message))
        }
    }
    send(BridgedOutcome.Result(core.toolRegistry.callTool(params, session, progress)))
}.buffer(Channel.UNLIMITED)
