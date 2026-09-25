/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pins the three branches of [McpToolRegistry.callTool]'s catch chain:
 *
 *  - [ToolCallErrorException] → clean `ToolCallResult.errorResult(message)`
 *    (single text content "ERROR: <message>", no stacktrace leaked).
 *  - any other [Exception] → `ToolCallResult` with `isError=true` AND a
 *    second `Stacktrace: …` content item.
 *  - [CancellationException] → rethrown when the caller is cancelled; an error result when the
 *    caller is still active, because then the cancellation came from inside the tool.
 */
class ToolCallErrorExceptionHandlingTest {

    @Test
    fun `ToolCallErrorException becomes a clean error result without a stacktrace`() = runBlocking {
        val registry = registryWithFailingTool { throw ToolCallErrorException("project is closed") }

        val result = registry.callTool(failParams(), McpSession())

        assertTrue(result.isError, "isError must be true")
        val texts = result.content.filterIsInstance<ContentItem.Text>().map { it.text }
        assertEquals(listOf("ERROR: project is closed"), texts, "expected single clean error text")
    }

    @Test
    fun `generic Exception surfaces as isError with a merged Stacktrace text`() = runBlocking {
        val registry = registryWithFailingTool { error("boom") }

        val result = registry.callTool(failParams(), McpSession())

        // ToolCallBuilder.build() merges every Text content into one newline-joined Text;
        // we expect a single content item carrying both halves of the error envelope.
        assertTrue(result.isError, "isError must be true")
        val merged = result.content.filterIsInstance<ContentItem.Text>().single().text
        assertTrue("Tool execution error: boom" in merged, "message half missing in: $merged")
        assertTrue("\nStacktrace:" in merged, "stacktrace half missing in: $merged")
        assertTrue("IllegalStateException" in merged, "exception class missing in: $merged")
    }

    @Test
    fun `cancellation of the caller is rethrown not converted`() {
        assertThrows<CancellationException> {
            runBlocking {
                val registry = registryWithFailingTool {
                    coroutineContext.cancel()
                    throw CancellationException("cancelled")
                }
                registry.callTool(failParams(), McpSession())
            }
        }
    }

    // The IDE throws ProcessCanceledException for its own reasons, for example a disposed project.
    // When the caller is still active, that is a failed call, not a cancelled one.
    @Test
    fun `a CancellationException while the caller is active becomes an error result`() = runBlocking {
        val registry = registryWithFailingTool { throw CancellationException("Container 'ProjectImpl services' was disposed") }

        val result = registry.callTool(failParams(), McpSession())

        assertTrue(result.isError)
        val text = (result.content.first() as ContentItem.Text).text
        assertTrue("Container 'ProjectImpl services' was disposed" in text, text)
    }

    private fun registryWithFailingTool(body: () -> Nothing): McpToolRegistry = McpToolRegistry().apply {
        registerTool(object : McpTool {
            override val name = "fail"
            override val description = "always throws"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult = body()
        })
    }

    private fun failParams(): ToolCallParams = ToolCallParams(
        name = "fail",
        arguments = buildJsonObject {},
        rawArguments = buildJsonObject { put("name", "fail") },
    )
}
