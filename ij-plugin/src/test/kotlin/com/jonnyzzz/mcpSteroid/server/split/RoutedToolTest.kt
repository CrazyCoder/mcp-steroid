/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.freeze.FreezeMonitor
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpSession
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.server.ToolOutputContract
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class RoutedToolTest {
    private class LocalTool(override val name: String) : McpTool {
        var calls = 0
        override val description = "local"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override suspend fun call(context: ToolCallContext): ToolCallResult {
            calls++
            return ToolCallResult.successTextResult("local")
        }
    }

    private class FakeBridge(private val fail: Boolean = false, private val failRefresh: Boolean = false) : SplitFrontendBridge {
        val forwarded = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        var refreshes = 0
        override suspend fun forward(params: ToolCallParams, progress: McpProgressReporter, sessionId: String): ToolCallResult {
            if (fail) throw IllegalStateException("connection lost")
            forwarded += params.name
            sessions += sessionId
            progress.report("from backend")
            return ToolCallResult.successTextResult("remote")
        }
        override suspend fun refreshProjectKeys() {
            refreshes++
            if (failRefresh) throw IllegalStateException("connection lost")
        }
        override fun backendKeyFor(project: Project): String? = null
        override fun backendPathFor(project: Project): String? = null
        override suspend fun backendSelf(): BackendRef? = null
    }

    private fun context(name: String, reporter: McpProgressReporter = NoOpProgressReporter) =
        ToolCallContext(ToolCallParams(name = name), McpSession(), reporter)

    private fun text(r: ToolCallResult) = (r.content.single() as ContentItem.Text).text

    // Errors other tests logged, or editors they left open, in the shared test application would lead every result here.
    private val monitor = FreezeMonitor.getInstanceOrNull()
    private val ideErrors = monitor?.ideErrors
    private val ideBanners = monitor?.ideBanners
    private val ideMemory = monitor?.ideMemory
    private val ideNotifications = monitor?.ideNotifications
    private val editorProblems = monitor?.editorProblems

    @Before
    fun hideIdeErrors() {
        monitor?.ideErrors = { null }
        monitor?.ideBanners = { null }
        monitor?.ideMemory = { null }
        monitor?.ideNotifications = { null }
        monitor?.editorProblems = { null }
    }

    @After
    fun restoreIdeErrors() {
        if (ideErrors != null) monitor?.ideErrors = ideErrors
        if (ideBanners != null) monitor?.ideBanners = ideBanners
        if (ideMemory != null) monitor?.ideMemory = ideMemory
        if (ideNotifications != null) monitor?.ideNotifications = ideNotifications
        if (editorProblems != null) monitor?.editorProblems = editorProblems
    }

    @Test
    fun `split frontend forwards a backend tool and relays progress`() = runBlocking {
        val local = LocalTool("steroid_execute_code")
        val bridge = FakeBridge()
        val seen = mutableListOf<String>()
        val reporter = object : McpProgressReporter { override fun report(message: String) { seen += message } }
        val context = context("steroid_execute_code", reporter)
        val result = RoutedTool(local, { SplitRole.FRONTEND }, { bridge }).call(context)
        assertEquals("remote", text(result))
        assertEquals(0, local.calls)
        assertEquals(listOf("steroid_execute_code"), bridge.forwarded)
        assertEquals("the agent's session goes with the call", listOf(context.session.id), bridge.sessions)
        assertEquals(listOf("from backend"), seen)
    }

    @Test
    fun `with output json an error thrown before the tool answers is an envelope, and without it the error propagates`() = runBlocking {
        val failing = object : McpTool {
            override val name = "steroid_execute_code"
            override val description = "fails"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult = throw ToolCallErrorException("Project not found: \"nope\"")
        }
        val json = ToolCallContext(ToolCallParams(name = failing.name, arguments = buildJsonObject { put("output", "json") }), McpSession(), NoOpProgressReporter)
        val result = RoutedTool(failing, { SplitRole.MONOLITH }, { null }).call(json)
        val envelope = ToolOutputContract.envelopeOf(result)!!
        assertTrue(result.isError)
        assertEquals("false", envelope["ok"]!!.jsonPrimitive.content)
        assertTrue(envelope["text"]!!.jsonPrimitive.content.contains("Project not found"))
        val thrown = runCatching { RoutedTool(failing, { SplitRole.MONOLITH }, { null }).call(context(failing.name)) }.exceptionOrNull()
        assertTrue("text mode leaves the error to the registry", thrown is ToolCallErrorException)
    }

    @Test
    fun `only a local steroid_execute_code run counts as reporting its own IDE errors`() {
        val none = buildJsonObject { }
        assertTrue(RoutedTool.reportsOwnIdeErrors(SplitRole.MONOLITH, "steroid_execute_code", none))
        assertTrue(RoutedTool.reportsOwnIdeErrors(SplitRole.BACKEND, "steroid_execute_code", none))
        assertFalse("forwarded to the backend", RoutedTool.reportsOwnIdeErrors(SplitRole.FRONTEND, "steroid_execute_code", none))
        assertTrue(RoutedTool.reportsOwnIdeErrors(SplitRole.FRONTEND, "steroid_execute_code", buildJsonObject { put("side", "frontend") }))
        assertFalse(RoutedTool.reportsOwnIdeErrors(SplitRole.MONOLITH, "steroid_list_projects", none))
    }

    @Test
    fun `split frontend runs a UI tool locally after refreshing project keys`() = runBlocking {
        val local = LocalTool("steroid_input")
        val bridge = FakeBridge()
        assertEquals("local", text(RoutedTool(local, { SplitRole.FRONTEND }, { bridge }).call(context("steroid_input"))))
        assertEquals(1, local.calls)
        assertEquals(1, bridge.refreshes)
        assertTrue(bridge.forwarded.isEmpty())
    }

    @Test
    fun `a UI tool still runs locally when the backend keys cannot be fetched`() = runBlocking {
        val local = LocalTool("steroid_take_screenshot")
        val result = RoutedTool(local, { SplitRole.FRONTEND }, { FakeBridge(failRefresh = true) })
            .call(context("steroid_take_screenshot"))
        assertEquals("local", text(result))
        assertEquals(1, local.calls)
    }

    @Test
    fun `monolith never touches the bridge`() = runBlocking {
        val local = LocalTool("steroid_execute_code")
        val bridge = FakeBridge()
        RoutedTool(local, { SplitRole.MONOLITH }, { bridge }).call(context("steroid_execute_code"))
        assertEquals(1, local.calls)
        assertTrue(bridge.forwarded.isEmpty())
        assertEquals(0, bridge.refreshes)
    }

    @Test
    fun `a lost backend becomes a tool error naming the connection`() = runBlocking {
        val result = RoutedTool(LocalTool("steroid_execute_code"), { SplitRole.FRONTEND }, { FakeBridge(fail = true) })
            .call(context("steroid_execute_code"))
        assertTrue(result.isError)
        assertTrue(text(result), text(result).contains("backend is not connected"))
    }

    @Test
    fun `split frontend without a bridge still runs a UI tool locally`() = runBlocking {
        val local = LocalTool("steroid_take_screenshot")
        assertEquals("local", text(RoutedTool(local, { SplitRole.FRONTEND }, { null }).call(context("steroid_take_screenshot"))))
        assertEquals(1, local.calls)
    }

    @Test
    fun `split frontend without a bridge is an error, not a local run`() = runBlocking {
        val local = LocalTool("steroid_execute_code")
        val result = RoutedTool(local, { SplitRole.FRONTEND }, { null }).call(context("steroid_execute_code"))
        assertTrue(result.isError)
        assertEquals(0, local.calls)
    }
}
