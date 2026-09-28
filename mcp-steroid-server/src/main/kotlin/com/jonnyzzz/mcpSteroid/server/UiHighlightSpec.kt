/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One highlight of a screenshot: a control by its locator, one of its rows, the Settings page's breadcrumb, lines or a
 * symbol of code, the point of the call's last click, an inspection's row, or the console lines of a run.
 */
data class UiHighlight(
    val target: UiTarget?,
    val breadcrumb: Boolean = false,
    val row: String? = null,
    val index: Int? = null,
    /** Text drawn beside the highlight. */
    val label: String? = null,
    /** Lines of code, `"20-27"` or `"20"`, 1-based, in the editor of [file] or the selected one. */
    val lines: String? = null,
    /** A name in the editor of [file] or the selected one, as goto finds it; [nth] picks another occurrence. */
    val symbol: String? = null,
    val file: String? = null,
    /** Which occurrence of [symbol], or of [contains] in a console counted from the last, from 0. */
    val nth: Int? = null,
    /** The point of the call's last click, drawn as a mouse pointer. */
    val click: Boolean = false,
    /** An inspection's row on the Inspections page, by its short name. */
    val inspection: String? = null,
    /** The console of a run by its name, whose lines holding [contains] are outlined. */
    val console: String? = null,
    val contains: String? = null,
)

/** Parses one entry of a screenshot step's `highlight`. */
object UiHighlightSpec {
    val FIELDS = UiSteps.TARGET_FIELDS + setOf("row", "index", "label", "lines", "symbol", "file", "click", "inspection", "console", "contains")

    fun parse(e: JsonElement): UiHighlight = when {
        e is JsonPrimitive && e.isString && e.content == UiSteps.BREADCRUMB -> UiHighlight(null, breadcrumb = true)
        e is JsonObject -> {
            val unknown = e.keys - FIELDS
            require(unknown.isEmpty()) { "a highlight has unknown field(s) ${unknown.joinToString()}; it takes ${FIELDS.sorted().joinToString()}" }
            val lines = e.string("lines")?.also { UiSteps.parseLines(it) }
            val symbol = e.string("symbol")
            val click = e.boolean("click") ?: false
            val inspection = e.string("inspection")
            val console = e.string("console")
            // A symbol's nth counts its occurrences; a locator's picks one of several matches.
            val code = lines != null || symbol != null || console != null
            val target = UiSteps.locator(e)
            val kinds = listOfNotNull(target?.let { "a locator" }, lines?.let { "lines" }, symbol?.let { "symbol" }, "click".takeIf { click },
                inspection?.let { "inspection" }, console?.let { "console" })
            require(kinds.size == 1) {
                if (kinds.isEmpty()) "a highlight needs a locator (ref, name, text, class or xpath), lines, symbol, click, inspection or console, or is \"${UiSteps.BREADCRUMB}\""
                else "a highlight takes one of a locator, lines, symbol, click, inspection or console, not ${kinds.joinToString(" and ")}"
            }
            val file = e.string("file")
            require(file == null || lines != null || symbol != null) { "file goes with a highlight of lines or a symbol" }
            val contains = e.string("contains")
            require((console != null) == (contains != null)) { "a console highlight needs contains, the text of the lines to outline, and contains goes with console" }
            require(e.string("row") == null && e.int("index") == null || target != null) { "row and index go with a highlight of a control" }
            UiHighlight(
                target, row = e.string("row"), index = e.int("index"), label = e.string("label"),
                lines = lines, symbol = symbol, file = file, nth = if (code) e.int("nth") else null,
                click = click, inspection = inspection, console = console, contains = contains,
            )
        }
        else -> throw IllegalArgumentException("a highlight is \"${UiSteps.BREADCRUMB}\" or an object")
    }
}
