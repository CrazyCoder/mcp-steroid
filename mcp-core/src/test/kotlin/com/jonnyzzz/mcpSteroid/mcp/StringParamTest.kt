/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StringParamTest {
    private val steps = InputSchemaElement.param("steps").string()

    private fun parse(value: JsonElement?): String? = ToolCallContext(
        params = ToolCallParams(name = "t", arguments = buildJsonObject { value?.let { put("steps", it) } }),
        session = McpSession(),
        mcpProgressReporter = NoOpProgressReporter,
    )[steps]

    @Test
    fun `a string parameter reads a string, and a JSON array or object as its JSON text`() {
        val text = """[{"action":"close"}]"""
        assertEquals(text, parse(JsonPrimitive(text)))
        val array = buildJsonObject { putJsonArray("a") { add(buildJsonObject { put("action", "close") }) } }["a"]!!
        assertEquals(text, parse(array))
        val obj = buildJsonObject { putJsonObject("o") { put("k", 1) } }["o"]!!
        assertEquals("""{"k":1}""", parse(obj))
        assertNull(parse(null))
    }
}
