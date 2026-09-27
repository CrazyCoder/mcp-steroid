/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `notifications/cancelled` stops the tool call it names (MCP §Cancellation). */
class McpCancellationTest {
    private val started = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<String?>()
    private val release = CompletableDeferred<Unit>()

    private val server = McpServerCore(ServerInfo("test-server", "1.0.0"), ServerCapabilities(tools = ToolsCapability(listChanged = false))).apply {
        toolRegistry.registerTool(object : McpTool {
            override val name = "wait"
            override val description = "waits until cancelled or released"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult {
                started.complete(Unit)
                try {
                    release.await()
                    return ToolCallResult(listOf(ContentItem.Text("released")))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    stopped.complete(e.message)
                    throw e
                }
            }
        })
    }
    private val session = server.sessionManager.createSession()

    private fun call(id: String) = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"wait","arguments":{}}}"""
    private fun cancel(id: String) = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":$id,"reason":"user stopped it"}}"""

    @Test
    fun `a cancelled call stops and gets no response`() = runBlocking {
        val response = async { server.handleMessage(call("7"), session) }
        withTimeout(5_000) { started.await() }

        assertNull(server.handleMessage(cancel("7"), session))

        assertNull(withTimeout(5_000) { response.await() })
        val reason = withTimeout(5_000) { stopped.await() }
        assertTrue(reason?.contains("user stopped it") == true, "reason: $reason")
    }

    @Test
    fun `a string request id and a number name the same request`() = runBlocking {
        val response = async { server.handleMessage(call("\"7\""), session) }
        withTimeout(5_000) { started.await() }
        server.handleMessage(cancel("7"), session)
        assertNull(withTimeout(5_000) { response.await() })
    }

    @Test
    fun `a cancellation of another request leaves the call running`() = runBlocking {
        val response = async { server.handleMessage(call("7"), session) }
        withTimeout(5_000) { started.await() }

        server.handleMessage(cancel("8"), session)
        assertNull(withTimeoutOrNull(300) { stopped.await() }, "the call must keep running")

        release.complete(Unit)
        val text = withTimeout(5_000) { response.await() }
        assertTrue(text?.contains("released") == true, "response: $text")
        assertEquals(false, stopped.isCompleted)
    }
}
