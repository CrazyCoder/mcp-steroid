/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Keeps the scenario schema in step with the parser, which owns the format: every field, action and value the
 * parser takes, the schema names, and the frozen fixture of every released field is valid against it.
 */
class UiScenarioSchemaTest {
    private val schema = Json.parseToJsonElement(UiScenarioSchema.text()).jsonObject
    private val defs = schema["\$defs"]!!.jsonObject
    private val step = defs["step"]!!.jsonObject["properties"]!!.jsonObject

    private fun enumOf(e: JsonElement): Set<String> = e.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    @Test
    fun `the schema names every field and value the parser takes`() {
        assertEquals(UiSteps.FIELDS, step.keys)
        assertEquals(UiAction.entries.map { it.wire }.toSet(), enumOf(step["action"]!!))
        assertEquals(UiWaitCondition.entries.map { it.wire }.toSet(), enumOf(step["for"]!!))
        assertEquals(UiExpectState.entries.map { it.wire }.toSet(), enumOf(step["is"]!!))
        assertEquals(UiSteps.BUTTONS, enumOf(step["button"]!!))
        assertEquals(UiSteps.MODALS, enumOf(step["modal"]!!))
        assertEquals(UiSteps.SIDES, enumOf(defs["side"]!!))
        assertEquals(UiSteps.MENU_MODES, enumOf(defs["menuMode"]!!))
        assertEquals(UiSteps.MEMORY_METRICS, enumOf(step["memory"]!!.jsonObject["anyOf"]!!.jsonArray[1]))
        assertEquals(UiSteps.SEVERITIES.toSet(), enumOf(step["severity"]!!))
        assertEquals(UiSteps.ALIGNS, enumOf(step["align"]!!))
        assertEquals(UiSteps.BREADCRUMB, defs["highlight"]!!.jsonObject["anyOf"]!!.jsonArray[0].jsonObject["const"]!!.jsonPrimitive.content)
        assertEquals(UiSteps.MARGINS.last, step["margin"]!!.jsonObject["maximum"]!!.jsonPrimitive.content.toInt())
        assertEquals(UiScenario.FIELDS, schema["properties"]!!.jsonObject.keys)
        val setup = defs["setup"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(UiScenario.SETUP_FIELDS, setup.keys)
        assertEquals(UiScenario.LAYOUT_MODES, enumOf(setup["layout"]!!))
        val requires = defs["requires"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(UiScenarioRequires.FIELDS, requires.keys)
        assertEquals(UiScenarioRequires.MODES, enumOf(requires["mode"]!!))
        assertEquals(UiScenarioRequires.OSES, enumOf(requires["os"]!!.jsonObject["items"]!!))
        assertEquals(UiScenarioSchema.URI, schema["\$id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the fixture of every released field is valid`() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/ui-scenarios/format-1.scenario.json")!!.readText())
        assertEquals(emptyList<String>(), errors(fixture))
    }

    @Test
    fun `the schema rejects what the parser rejects by shape`() {
        fun scenario(step: String) = Json.parseToJsonElement("""{"scenario":1,"title":"t","steps":[$step]}""")
        val cases = mapOf(
            """{"action":"tap"}""" to "action",
            """{"action":"close","shadow":1}""" to "shadow",
            """{"action":"press"}""" to "keys",
            """{"action":"click"}""" to "anyOf",
            """{"action":"toolwindow","id":"Project","width":10}""" to "width",
            """{"action":"menu","mode":"sideways"}""" to "mode",
            """{"action":"write","file":"a.txt"}""" to "anyOf",
            """{"action":"screenshot","save":".hidden"}""" to "save",
            """{"action":"screenshot"}""" to "anyOf",
            """{"action":"screenshot","out":"a.gif"}""" to "out",
            """{"action":"screenshot","save":"a","highlight":[{"label":"x"}]}""" to "highlight",
            """{"action":"screenshot","save":"a","crop":"left"}""" to "crop",
            """{"action":"scroll","name":"a","align":"bottom"}""" to "align",
        )
        for ((json, where) in cases) {
            val found = errors(scenario(json))
            assertTrue(found.any { where in it }) { "$json: expected an error at $where, got $found" }
        }
        assertTrue(errors(Json.parseToJsonElement("""{"scenario":1,"title":"t","steps":[{"action":"close"}],"requires":{"os":["dos"]}}""")).isNotEmpty())
        assertTrue(errors(Json.parseToJsonElement("""{"scenario":2,"title":"t","steps":[{"action":"close"}]}""")).isNotEmpty())
    }

    // A validator of the keywords the schema uses, so the test needs no library.

    private fun errors(value: JsonElement): List<String> = mutableListOf<String>().also { check(value, schema, "$", it) }

    private fun resolve(s: JsonObject): JsonObject =
        s["\$ref"]?.jsonPrimitive?.content?.let { ref -> resolve(defs[ref.removePrefix("#/\$defs/")]!!.jsonObject) } ?: s

    private fun type(value: JsonElement): Set<String> = when {
        value is JsonObject -> setOf("object")
        value is JsonArray -> setOf("array")
        value is JsonNull -> setOf("null")
        value is JsonPrimitive && value.isString -> setOf("string")
        value is JsonPrimitive && value.booleanOrNull != null -> setOf("boolean")
        value is JsonPrimitive && value.longOrNull != null -> setOf("integer", "number")
        else -> setOf("number")
    }

    private fun valid(value: JsonElement, s: JsonObject, path: String) = errors(value, s, path).isEmpty()
    private fun errors(value: JsonElement, s: JsonObject, path: String) = mutableListOf<String>().also { check(value, s, path, it) }

    private fun check(value: JsonElement, raw: JsonObject, path: String, out: MutableList<String>) {
        val s = resolve(raw)
        s["type"]?.let { t ->
            val wanted = (t as? JsonArray)?.map { it.jsonPrimitive.content } ?: listOf(t.jsonPrimitive.content)
            if (wanted.none { it in type(value) }) out += "$path: type is not ${wanted.joinToString("|")}"
        }
        s["const"]?.let { if (it != value) out += "$path: is not $it" }
        s["enum"]?.let { if (value !in it.jsonArray) out += "$path: $value is not one of ${it.jsonArray}" }
        (value as? JsonPrimitive)?.let { p ->
            if (p.isString) {
                s["minLength"]?.let { if (p.content.length < it.jsonPrimitive.content.toInt()) out += "$path: too short" }
                s["pattern"]?.let { if (!Regex(it.jsonPrimitive.content).containsMatchIn(p.content)) out += "$path: does not match ${it.jsonPrimitive.content}" }
            } else p.doubleOrNull?.let { n ->
                s["minimum"]?.let { if (n < it.jsonPrimitive.content.toDouble()) out += "$path: below ${it.jsonPrimitive.content}" }
                s["maximum"]?.let { if (n > it.jsonPrimitive.content.toDouble()) out += "$path: above ${it.jsonPrimitive.content}" }
            }
        }
        (value as? JsonObject)?.let { obj ->
            s["required"]?.jsonArray?.forEach { r -> if (r.jsonPrimitive.content !in obj) out += "$path: no ${r.jsonPrimitive.content}" }
            val props = s["properties"]?.jsonObject.orEmpty()
            for ((k, v) in obj) {
                val sub = props[k]?.jsonObject
                when {
                    sub != null -> check(v, sub, "$path.$k", out)
                    s["additionalProperties"] is JsonObject -> check(v, s["additionalProperties"]!!.jsonObject, "$path.$k", out)
                    s["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false -> out += "$path: unknown field $k"
                }
            }
        }
        (value as? JsonArray)?.let { array ->
            s["minItems"]?.let { if (array.size < it.jsonPrimitive.content.toInt()) out += "$path: too few items" }
            s["items"]?.jsonObject?.let { items -> array.forEachIndexed { i, e -> check(e, items, "$path[$i]", out) } }
        }
        s["anyOf"]?.jsonArray?.let { options -> if (options.none { valid(value, it.jsonObject, path) }) out += "$path: matches no anyOf option" }
        s["allOf"]?.jsonArray?.forEach { part ->
            val p = part.jsonObject
            val condition = p["if"]?.jsonObject
            if (condition == null) check(value, p, path, out)
            else if (valid(value, condition, path)) p["then"]?.jsonObject?.let { check(value, it, path, out) }
        }
    }
}
