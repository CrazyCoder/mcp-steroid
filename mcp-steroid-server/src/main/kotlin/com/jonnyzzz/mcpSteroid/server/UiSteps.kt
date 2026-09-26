/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class UiAction(val wire: String) {
    CLICK("click"),
    HOVER("hover"),
    TYPE("type"),
    FILL("fill"),
    PRESS("press"),
    CHECK("check"),
    UNCHECK("uncheck"),
    SELECT("select"),
    CLOSE("close"),
    WAIT("wait"),
    SNAPSHOT("snapshot"),
    GOTO("goto"),
    RUN("run"),
}

enum class UiWaitCondition(val wire: String) {
    VISIBLE("visible"),
    HIDDEN("hidden"),
    ENABLED("enabled"),
    WINDOW("window"),
    IDLE("idle"),
}

/** What a step acts on. Every given field must match; [nth] picks one of several matches, from 0. */
data class UiTarget(
    val ref: String? = null,
    val name: String? = null,
    val text: String? = null,
    val cls: String? = null,
    val xpath: String? = null,
    val nth: Int? = null,
) {
    override fun toString(): String = listOfNotNull(
        ref?.let { "ref=$it" },
        name?.let { "name=\"$it\"" },
        text?.let { "text=\"$it\"" },
        cls?.let { "class=$it" },
        xpath?.let { "xpath=$it" },
        nth?.let { "nth=$it" },
    ).joinToString(" ")
}

data class UiStep(
    val action: UiAction,
    val target: UiTarget?,
    val button: String? = null,
    val count: Int = 1,
    val modifiers: String? = null,
    val offsetX: Int? = null,
    val offsetY: Int? = null,
    val text: String? = null,
    val keys: String? = null,
    val row: String? = null,
    val index: Int? = null,
    val condition: UiWaitCondition? = null,
    val title: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val symbol: String? = null,
    val id: String? = null,
    /** Which occurrence a goto symbol or text means, from 0; a target carries its own. */
    val nth: Int = 0,
    val timeoutMs: Long = UiSteps.DEFAULT_TIMEOUT_MS,
)

/**
 * Parses the `steps` argument of steroid_ui: a JSON array of flat objects such as
 * `{"action":"click","name":"OK"}`. Unknown actions and fields fail with the step's number, so a typo
 * never runs as a different step.
 */
object UiSteps {
    const val DEFAULT_TIMEOUT_MS = 5_000L
    const val MAX_TIMEOUT_MS = 60_000L

    private val TARGET_FIELDS = setOf("ref", "name", "text", "class", "xpath", "nth")
    private val FIELDS = TARGET_FIELDS + setOf(
        "action", "button", "count", "modifiers", "offset_x", "offset_y", "keys", "row", "index", "for", "title", "timeout_ms",
        "file", "line", "column", "symbol", "id",
    )
    private val BUTTONS = setOf("left", "right", "middle")
    /** Actions whose "text" is what they enter or look for in the editor, not a target. */
    private val TEXT_IS_INPUT = setOf(UiAction.TYPE, UiAction.FILL, UiAction.GOTO)
    private val NEEDS_TARGET = setOf(UiAction.CLICK, UiAction.HOVER, UiAction.FILL, UiAction.CHECK, UiAction.UNCHECK, UiAction.SELECT)

    fun parse(json: String): List<UiStep> {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("steps is not valid JSON: ${e.message}", e)
        }
        val array = root as? JsonArray ?: throw IllegalArgumentException("steps must be a JSON array of step objects")
        return array.mapIndexed { i, element ->
            val obj = element as? JsonObject ?: throw IllegalArgumentException("step ${i + 1} must be an object")
            try {
                parseStep(obj)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("step ${i + 1}: ${e.message}", e)
            }
        }
    }

    private fun parseStep(obj: JsonObject): UiStep {
        val unknown = obj.keys - FIELDS
        require(unknown.isEmpty()) { "unknown field(s) ${unknown.joinToString()}; known fields: ${FIELDS.sorted().joinToString()}" }
        val actionName = obj.string("action") ?: throw IllegalArgumentException("has no action")
        val action = UiAction.entries.firstOrNull { it.wire == actionName }
            ?: throw IllegalArgumentException("unknown action '$actionName'; use one of ${UiAction.entries.joinToString { it.wire }}")
        val target = UiTarget(
            ref = obj.string("ref"),
            name = obj.string("name"),
            text = obj.string("text").takeIf { action !in TEXT_IS_INPUT },
            cls = obj.string("class"),
            xpath = obj.string("xpath"),
            nth = obj.int("nth"),
        ).takeIf { it.ref != null || it.name != null || it.text != null || it.cls != null || it.xpath != null }
        val step = UiStep(
            action = action,
            target = target,
            button = obj.string("button"),
            count = obj.int("count") ?: 1,
            modifiers = obj.string("modifiers"),
            offsetX = obj.int("offset_x"),
            offsetY = obj.int("offset_y"),
            text = obj.string("text").takeIf { action in TEXT_IS_INPUT },
            keys = obj.string("keys"),
            row = obj.string("row"),
            index = obj.int("index"),
            condition = obj.string("for")?.let { wanted ->
                UiWaitCondition.entries.firstOrNull { it.wire == wanted }
                    ?: throw IllegalArgumentException("unknown wait condition '$wanted'; use one of ${UiWaitCondition.entries.joinToString { it.wire }}")
            },
            title = obj.string("title"),
            file = obj.string("file"),
            line = obj.int("line"),
            column = obj.int("column"),
            symbol = obj.string("symbol"),
            id = obj.string("id"),
            nth = obj.int("nth") ?: 0,
            timeoutMs = (obj.long("timeout_ms") ?: DEFAULT_TIMEOUT_MS).coerceIn(0, MAX_TIMEOUT_MS),
        )
        validate(step)
        return step
    }

    private fun validate(step: UiStep) {
        val action = step.action.wire
        if (step.action in NEEDS_TARGET) require(step.target != null) { "$action needs a target: ref, name, text, class or xpath" }
        step.button?.let { require(it in BUTTONS) { "unknown button '$it'; use left, right or middle" } }
        require(step.count in 1..2) { "count must be 1 or 2, was ${step.count}" }
        when (step.action) {
            UiAction.FILL, UiAction.TYPE -> require(step.text != null) { "$action needs text" }
            UiAction.PRESS -> require(!step.keys.isNullOrBlank()) { "press needs keys, such as \"ENTER\" or \"ctrl+shift+A\"" }
            UiAction.SELECT -> require(step.row != null || step.index != null) { "select needs a row (its text) or an index" }
            UiAction.WAIT -> when (step.condition) {
                null -> throw IllegalArgumentException("wait needs \"for\": ${UiWaitCondition.entries.joinToString { it.wire }}")
                UiWaitCondition.VISIBLE, UiWaitCondition.HIDDEN, UiWaitCondition.ENABLED ->
                    require(step.target != null) { "wait for=${step.condition.wire} needs a target" }
                UiWaitCondition.WINDOW -> require(!step.title.isNullOrBlank()) { "wait for=window needs a title" }
                UiWaitCondition.IDLE -> Unit
            }
            UiAction.GOTO -> {
                require(!step.file.isNullOrBlank()) { "goto needs a file" }
                require(listOfNotNull(step.line, step.symbol, step.text).size == 1) { "goto needs exactly one of line, symbol or text" }
                step.line?.let { require(it >= 1) { "line is 1-based, was $it" } }
                step.column?.let { require(it >= 1) { "column is 1-based, was $it" } }
            }
            UiAction.RUN -> require(!step.id.isNullOrBlank()) { "run needs an action id, such as \"RenameElement\"" }
            else -> Unit
        }
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? {
        val value = get(key) ?: return null
        return value as? JsonPrimitive ?: throw IllegalArgumentException("$key must be a string or a number")
    }

    private fun JsonObject.string(key: String): String? = primitive(key)?.content

    private fun JsonObject.int(key: String): Int? = primitive(key)?.let {
        it.intOrNull ?: throw IllegalArgumentException("$key must be a whole number, was ${it.content}")
    }

    private fun JsonObject.long(key: String): Long? = primitive(key)?.let {
        it.longOrNull ?: throw IllegalArgumentException("$key must be a whole number, was ${it.content}")
    }
}
