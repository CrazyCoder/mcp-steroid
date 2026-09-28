/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ToolOutputContractTest {
    private val fixture: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/output-contract/output-contract-1.json")!!.readText()
    ).jsonObject

    private fun section(name: String): Map<String, String> = fixture[name]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun typeOf(e: JsonElement): String = when {
        e is JsonArray -> "array"
        e is JsonObject -> "object"
        e is JsonNull -> "null"
        e is JsonPrimitive && e.isString -> "string"
        e is JsonPrimitive && e.booleanOrNull != null -> "boolean"
        e is JsonPrimitive && e.doubleOrNull != null -> "number"
        else -> "unknown"
    }

    /** Every field of [section] in [obj] with its released type; with [allOptionalPresent] the optional ones too. */
    private fun assertSection(section: String, obj: JsonObject, allOptionalPresent: Boolean = true) {
        for ((field, type) in section(section)) {
            val optional = type.endsWith("?")
            val value = obj[field]
            if (value == null) {
                assertTrue(optional && !allOptionalPresent, "$section.$field is missing from $obj")
                continue
            }
            val want = type.removeSuffix("?")
            if (want != "any") assertEquals(want, typeOf(value), "$section.$field has the wrong type in $obj")
        }
    }

    private val notices = listOf(
        "IDE FREEZE in the backend (ended): the IDE's UI did not respond for 7 s.",
        "IDE ERRORS in the JetBrains Client: the IDE logged 1 error since your last call.",
        "EDITOR BANNERS: 1 banner above open editors",
        "LOW MEMORY in the backend: the IDE's garbage collector was overloaded 5 times",
        "EDITOR STATE: the JetBrains Client and the backend disagree about the open editors:",
    )

    private val execute = ToolOutputContract.executeCode(
        "eid_1", ok = true, stdout = listOf("""{"files":3}"""), messages = listOf("NOTE: busy"),
        errors = listOf(ToolOutputContract.ExecError("exception", "boom", "at A.b"), ToolOutputContract.ExecError("failed", "it failed")),
        images = listOf("image/png" to "screenshot.png"),
    )

    @Test
    fun `every envelope has the released fields of its version, with their types`() {
        assertEquals(ToolOutputContract.VERSION, fixture["contract"]!!.jsonPrimitive.content.toInt())
        val withNotices = ToolOutputContract.envelopeOf(ToolOutputContract.withNotices("steroid_execute_code", ToolOutputContract.result(execute), notices))!!
        assertSection("envelope", withNotices)
        assertSection("steroid_execute_code", withNotices)
        val errors = withNotices["errors"]!!.jsonArray.map { it.jsonObject }
        assertSection("execute_code_error", errors[0])
        assertSection("execute_code_error", errors[1], allOptionalPresent = false)
        assertSection("execute_code_image", withNotices["images"]!!.jsonArray[0].jsonObject)
        for (n in withNotices["notices"]!!.jsonArray) assertSection("notice", n.jsonObject, allOptionalPresent = false)

        val verdict = UiVerdict.Verdict(UiVerdict.Kind.REPRODUCED, "REPRODUCED at step 2: lost")
        assertSection("envelope", ToolOutputContract.ui("eid_2", true, verdict, "step 1: ok"))
        assertSection("steroid_ui", ToolOutputContract.ui("eid_2", true, verdict, "step 1: ok"))
        assertSection("steroid_ui", ToolOutputContract.ui("eid_2", true, null, "step 1: ok"), allOptionalPresent = false)
        val batch = ToolOutputContract.uiBatch(listOf("a.scenario.json" to "PASSED: all 1 step(s)"), "replayed 1")
        assertSection("steroid_ui_batch", batch)
        assertSection("steroid_ui_batch_file", batch["files"]!!.jsonArray[0].jsonObject)

        val wrapped = ToolOutputContract.envelopeOf(ToolOutputContract.wrap("steroid_ui", ToolCallResult(listOf(ContentItem.Text("ERROR: no project")), isError = true)))!!
        assertSection("envelope", wrapped)
        assertSection("wrapped", wrapped)
        for (how in ToolOutputContract.Interruption.entries) {
            val e = ToolOutputContract.interrupted("steroid_ui", how, "why", notices.take(1))
            assertSection("envelope", e)
            assertSection(how.field, e)
        }
    }

    @Test
    fun `each released notice kind is read from its notice, with the side it names`() {
        val read = notices.map(ToolOutputContract::noticeOf)
        assertEquals(fixture["notice_kinds"]!!.jsonArray.map { it.jsonPrimitive.content }, read.map { it.kind })
        assertEquals(listOf("backend", "frontend", null, "backend", null), read.map { it.side })
        assertEquals("NOTICE", ToolOutputContract.noticeOf("something else").kind)
    }

    @Test
    fun `the result is exactly one text item, and isError follows ok`() {
        val ok = ToolOutputContract.result(execute)
        assertEquals(1, ok.content.size)
        assertFalse(ok.isError)
        assertTrue(ToolOutputContract.result(ToolOutputContract.ui("e", false, null, "")).isError)
    }

    @Test
    fun `stdout is only the script's output, and result is that output parsed when it is one JSON document`() {
        assertEquals("""{"files":3}""", execute["stdout"]!!.jsonPrimitive.content)
        assertEquals(3, execute["result"]!!.jsonObject["files"]!!.jsonPrimitive.content.toInt())
        val text = ToolOutputContract.executeCode("e", true, listOf("hello", "world"), emptyList(), emptyList(), emptyList())
        assertEquals("hello\nworld", text["stdout"]!!.jsonPrimitive.content)
        assertNull(text["result"])
        assertNull(ToolOutputContract.parsedOutput("{not json"))
        // printJson pretty-prints with the OS separator; stdout is the same on every OS.
        val windows = ToolOutputContract.executeCode("e", true, listOf("{\r\n  \"a\" : 1\r\n}", "done"), emptyList(), emptyList(), emptyList())
        assertEquals("{\n  \"a\" : 1\n}\ndone", windows["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wrap keeps an envelope, and wraps anything else with its text and error state`() {
        val envelope = ToolOutputContract.result(execute)
        assertEquals(envelope, ToolOutputContract.wrap("steroid_execute_code", envelope))
        val wrapped = ToolOutputContract.envelopeOf(ToolOutputContract.wrap("steroid_ui", ToolCallResult(listOf(ContentItem.Text("a"), ContentItem.Text("b")), isError = true)))!!
        assertEquals("a\nb", wrapped["text"]!!.jsonPrimitive.content)
        assertEquals("false", wrapped["ok"]!!.jsonPrimitive.content)
    }

    @Test
    fun `notices from both sides add up in one envelope`() {
        val backend = ToolOutputContract.withNotices("steroid_execute_code", ToolOutputContract.result(execute), notices.take(1))
        val both = ToolOutputContract.envelopeOf(ToolOutputContract.withNotices("steroid_execute_code", backend, notices.drop(1).take(1)))!!
        assertEquals(listOf("IDE_FREEZE", "IDE_ERRORS"), both["notices"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content })
    }

    @Test
    fun `wantsJson reads the output argument`() {
        assertTrue(ToolOutputContract.wantsJson(buildJsonObject { put("output", "json") }))
        assertFalse(ToolOutputContract.wantsJson(buildJsonObject { put("output", "text") }))
        assertFalse(ToolOutputContract.wantsJson(null))
    }

    @Test
    fun `the article documents every released field and notice kind`() {
        val article = Files.readString(Path.of("..", "prompts", "src", "main", "prompts", "skill", "output-contract.md"))
        val names = fixture.entries.filter { it.value is JsonObject }.flatMap { it.value.jsonObject.keys } +
            fixture["notice_kinds"]!!.jsonArray.map { it.jsonPrimitive.content }
        val missing = names.toSet().filterNot { article.contains("`$it`") }
        assertTrue(missing.isEmpty(), "output-contract.md does not name: $missing")
    }
}
