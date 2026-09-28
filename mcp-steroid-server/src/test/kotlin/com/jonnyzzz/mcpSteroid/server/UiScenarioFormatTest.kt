/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Holds the scenario format's compatibility contract: format 1 only grows. The fixture uses every step, field and
 * value format 1 has released, so removing or renaming one, or making a released combination invalid, fails the
 * parse. Adding one fails the coverage checks until the fixture uses it, which freezes it from then on.
 *
 * The fixture takes new lines only. Editing or deleting a line to make this test pass is a breaking change.
 */
class UiScenarioFormatTest {
    private val text = javaClass.getResource("/ui-scenarios/format-1.scenario.json")!!.readText()
    private val root = Json.parseToJsonElement(text).jsonObject
    private val steps = (Json.parseToJsonElement(text).jsonObject.let { it["steps"]!!.jsonArray + it["cleanup"]!!.jsonArray })
        .map { it.jsonObject }

    private fun values(field: String): Set<String> = steps.mapNotNull { (it[field] as? JsonPrimitive)?.content }.toSet()

    private fun assertCovers(what: String, known: Iterable<String>, used: Set<String>) {
        val missing = known.toSet() - used
        assertTrue(missing.isEmpty()) { "format-1.scenario.json does not use the $what ${missing.sorted()}; add a line for each" }
    }

    @Test
    fun `every scenario format 1 has released still parses`() {
        val scenario = UiScenario.parse(text)
        assertEquals(steps.size - scenario.cleanup.size, scenario.steps.size)
    }

    @Test
    fun `the fixture uses every action, field and value`() {
        assertEquals(1, UiScenario.FORMAT_VERSION, "a new format version needs its own fixture, and format 1 must still parse")
        assertCovers("actions", UiAction.entries.map { it.wire }, values("action"))
        assertCovers("fields", UiSteps.FIELDS, steps.flatMap(JsonObject::keys).toSet())
        assertCovers("wait conditions", UiWaitCondition.entries.map { it.wire }, values("for"))
        assertCovers("expect states", UiExpectState.entries.map { it.wire }, values("is"))
        assertCovers("buttons", UiSteps.BUTTONS, values("button"))
        assertCovers("modal values", UiSteps.MODALS, values("modal"))
        assertCovers("sides", UiSteps.SIDES, values("side"))
        assertCovers("menu modes", UiSteps.MENU_MODES, values("mode"))
        assertCovers("scenario fields", UiScenario.FIELDS, root.keys)
        assertCovers("setup fields", UiScenario.SETUP_FIELDS, root["setup"]!!.jsonObject.keys)
        assertCovers("requires fields", UiScenarioRequires.FIELDS, root["requires"]!!.jsonObject.keys)
    }

    @Test
    fun `the setup block runs as steps, in its order`() {
        val setup = UiScenario.parse(text).setup
        assertEquals(listOf(UiAction.SET, UiAction.SET, UiAction.MENU, UiAction.WINDOW, UiAction.TOOLWINDOW, UiAction.TOOLWINDOW, UiAction.TOOLWINDOW, UiAction.GOTO),
            setup.map { it.action })
        assertEquals(UiScenario.IDE_FRAME_CLASS, setup[3].target?.cls)
        assertEquals("auto", UiScenario.parse(text).layout)
    }
}
