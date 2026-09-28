/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What puts the IDE back after a run: for each step that changed a setting, a menu toggle, a window or tool window
 * size, or a file, the steps that restore the state it found. A scenario runs them after its cleanup, last change
 * first, so it leaves the IDE as it was without cleanup written by hand.
 *
 * Each restore step sets a state outright, such as `check` rather than running a toggle again, so running one twice
 * does no harm, and only the first change of a state needs restoring: a later change of the same state is undone by
 * the first one's restore, which runs after it.
 */
object UiRestore {
    /**
     * The line of a steroid_ui response that carries a run's restore steps, as a JSON array: a JetBrains Client reads
     * it from the backend's report of a step it sent there, and a scenario run that stops before its last step ends
     * with it, for whoever resumes the run.
     */
    const val LINE = "undo: "

    /** The fields that hold the value a restore sets; the others name the state it sets. */
    private val VALUE_FIELDS = setOf("value", "width", "height", "maximize", "hide", "text", "delete", "tab", "mode")

    /**
     * The state [step] sets, such as one registry key, one tool window's size, or one file. `check` and `uncheck`
     * set the same state, and so do a write of a file's text and its delete: both set the one file.
     */
    fun key(step: JsonObject): String {
        val action = (step["action"] as? JsonPrimitive)?.content.let { if (it == "uncheck") "check" else it }
        val names = step.keys.filter { it != "action" && it !in VALUE_FIELDS }.sorted().joinToString(",") { "$it=${step[it]}" }
        val values = if (action == "write") "" else step.keys.filter { it in VALUE_FIELDS }.sorted().joinToString(",")
        return "$action|$names|$values"
    }

    /** The restore steps of a run, in the order the changes were made. */
    class Journal {
        private val groups = mutableListOf<List<JsonObject>>()
        private val keys = mutableSetOf<String>()

        /**
         * Adds the restores of one step: [steps] run in their order. A run of consecutive steps that set one state,
         * such as an inspection's severity and then its off state, counts as one restore, and a restore of a state an
         * earlier one already restores is left out.
         */
        fun add(steps: List<JsonObject>) {
            val kept = mutableListOf<JsonObject>()
            var i = 0
            while (i < steps.size) {
                val key = key(steps[i])
                var end = i + 1
                while (end < steps.size && key(steps[end]) == key) end++
                if (keys.add(key)) kept += steps.subList(i, end)
                i = end
            }
            if (kept.isNotEmpty()) groups += kept
        }

        /** The restore steps to run: the last change's first. */
        fun steps(): List<JsonObject> = groups.asReversed().flatten()
    }

    /** A step of [action] with [fields], each a string, a whole number or true or false. */
    fun step(action: String, vararg fields: Pair<String, Any>): JsonObject = JsonObject(
        mapOf("action" to JsonPrimitive(action)) + fields.associate { (k, v) ->
            k to when (v) {
                is Boolean -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }
    )

    /** [steps] with [side] added, so a restore runs on the side its step ran on. */
    fun onSide(steps: List<JsonObject>, side: String?): List<JsonObject> =
        if (side == null) steps else steps.map { if ("side" in it) it else JsonObject(it + ("side" to JsonPrimitive(side))) }

    fun line(steps: List<JsonObject>): String = LINE + Json.encodeToString(JsonArray.serializer(), JsonArray(steps))

    /** The steps of a [LINE], or none when [line] is not one. */
    fun parse(line: String): List<JsonObject> {
        if (!line.startsWith(LINE)) return emptyList()
        return runCatching { (Json.parseToJsonElement(line.removePrefix(LINE)) as JsonArray).map { it.jsonObject } }.getOrDefault(emptyList())
    }
}
