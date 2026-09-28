/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgedToolExecutorTest {
    private fun core(tool: McpTool) = McpServerCore(
        serverInfo = ServerInfo(name = "t", version = "0"),
        capabilities = ServerCapabilities(),
    ).also { it.toolRegistry.registerTool(tool) }

    private fun tool(body: suspend (ToolCallContext) -> ToolCallResult) = object : McpTool {
        override val name = "t"
        override val description = "t"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override suspend fun call(context: ToolCallContext) = body(context)
    }

    @Test
    fun `progress lines arrive in order and the result comes last`() = runBlocking {
        val events = executeBridgedTool(core(tool { c ->
            c.mcpProgressReporter.report("one")
            c.mcpProgressReporter.report("two")
            ToolCallResult.successTextResult("done")
        }), ToolCallParams(name = "t"), "agent").toList()
        assertEquals(listOf("one", "two"), events.filterIsInstance<BridgedOutcome.Progress>().map { it.message })
        assertEquals("done", ((events.last() as BridgedOutcome.Result).result.content.single() as ContentItem.Text).text)
    }

    @Test
    fun `a handler exception comes back as an error result`() = runBlocking {
        val last = executeBridgedTool(core(tool { error("boom") }), ToolCallParams(name = "t"), "agent").toList().last()
        val result = (last as BridgedOutcome.Result).result
        assertTrue(result.isError)
        assertTrue((result.content.first() as ContentItem.Text).text.contains("boom"))
    }

    @Test
    fun `each agent session of the client has one backend session of its own`() = runBlocking {
        val sessions = mutableListOf<Any>()
        val c = core(tool { sessions += it.session; ToolCallResult.successTextResult("x") })
        repeat(3) { executeBridgedTool(c, ToolCallParams(name = "t"), "agent-a").toList() }
        executeBridgedTool(c, ToolCallParams(name = "t"), "agent-b").toList()
        assertEquals("agent-a's calls share a session", 1, sessions.take(3).toSet().size)
        assertTrue("agent-b has another", sessions[3] !== sessions[0])
        assertEquals(2, c.sessionManager.getAllSessions().size)
    }

    @Test
    fun `past the limit the least recently used backend session is dropped from the session manager too`() = runBlocking {
        val sessions = mutableListOf<Any>()
        val c = core(tool { sessions += it.session; ToolCallResult.successTextResult("x") })
        executeBridgedTool(c, ToolCallParams(name = "t"), "first").toList()
        repeat(MAX_BRIDGE_SESSIONS) { executeBridgedTool(c, ToolCallParams(name = "t"), "agent-$it").toList() }
        assertEquals(MAX_BRIDGE_SESSIONS, c.sessionManager.getAllSessions().size)
        executeBridgedTool(c, ToolCallParams(name = "t"), "first").toList()
        assertTrue("an evicted agent gets a new session", sessions.last() !== sessions.first())
    }
}
