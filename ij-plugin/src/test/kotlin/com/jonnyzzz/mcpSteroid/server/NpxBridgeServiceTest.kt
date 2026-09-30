/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpSession
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

class NpxBridgeServiceTest : BasePlatformTestCase() {
    fun testBridgeAddsAuthoritativeProvenanceWhenOldDevrigSendsNone() {
        assertAuthoritativeBridgeProvenance(buildJsonObject { })
    }

    fun testBridgeOverridesSpoofedProvenanceArguments() {
        assertAuthoritativeBridgeProvenance(buildJsonObject {
            put(EXECUTION_BACKEND_KIND_ARGUMENT, "s")
            put(EXECUTION_BACKEND_NAME_ARGUMENT, "spoofed-backend")
        })
    }

    fun testCallsOfOneDevrigSessionShareAnIdeSession() = timeoutRunBlocking(30.seconds) {
        val sessions = mutableListOf<McpSession>()
        val core = core { sessions += it.session; ToolCallResult.successTextResult("x") }
        repeat(2) { stream(core, session = "devrig-a") }
        stream(core, session = "devrig-b")
        assertSame("devrig-a's calls share a session", sessions[0], sessions[1])
        assertNotSame("devrig-b has another", sessions[0], sessions[2])
    }

    fun testCallsWithoutADevrigSessionEachGetOneAndLeaveNoneBehind() = timeoutRunBlocking(30.seconds) {
        val sessions = mutableListOf<McpSession>()
        val core = core { sessions += it.session; ToolCallResult.successTextResult("x") }
        repeat(2) { stream(core, session = null) }
        assertNotSame(sessions[0], sessions[1])
        assertEquals(0, core.sessionManager.getAllSessions().size)
    }

    fun testProgressArrivesBeforeTheResultInASharedSession() = timeoutRunBlocking(30.seconds) {
        val core = core { it.mcpProgressReporter.report("one"); it.mcpProgressReporter.report("two"); ToolCallResult.successTextResult("x") }
        val events = stream(core, session = "devrig-a")
        val types = events.map { it["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("progress", "progress", "progress", "result"), types)
        assertEquals(listOf("one", "two"), events.drop(1).dropLast(1).map { it["message"]?.jsonPrimitive?.content })
    }

    private fun core(body: suspend (ToolCallContext) -> ToolCallResult) = McpServerCore(
        serverInfo = ServerInfo(name = "t", version = "0"),
        capabilities = ServerCapabilities(),
    ).also {
        it.toolRegistry.registerTool(object : McpTool {
            override val name = "t"
            override val description = "t"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext) = body(context)
        })
    }

    private suspend fun stream(core: McpServerCore, session: String?): List<JsonObject> {
        val events = mutableListOf<JsonObject>()
        NpxBridgeService().streamToolCall(core, NpxBridgeToolCallRequest(name = "t", session = session)) { events += it }
        return events.filter { it["type"]?.jsonPrimitive?.content != "heartbeat" }
    }

    private fun assertAuthoritativeBridgeProvenance(arguments: kotlinx.serialization.json.JsonObject) {
        val params = NpxBridgeService.getInstance().buildToolCallParams(
            request = NpxBridgeToolCallRequest(
                name = "steroid_execute_code",
                arguments = arguments,
            ),
            progressToken = "test-progress",
        )
        val context = ToolCallContext(
            params = params,
            session = McpSession(),
            mcpProgressReporter = NoOpProgressReporter,
        )
        val expectedBackendName = backendNameForMarker(
            ProcessHandle.current().pid(),
            ApplicationInfo.getInstance().build.asString(),
        )

        assertEquals(
            ExecutionBackendProvenance(kind = 'd', name = expectedBackendName),
            context.executionBackendProvenance(),
        )
    }
}
