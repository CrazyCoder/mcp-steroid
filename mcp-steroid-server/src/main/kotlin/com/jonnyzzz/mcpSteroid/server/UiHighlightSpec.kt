/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

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
    /** An arrow from a callout, which holds the badge and label, to the highlight. */
    val arrow: UiArrow? = null,
    /** False draws the arrow without the outline around the target. */
    val outline: Boolean = true,
    /** False leaves this step without a badge and a number; the other steps count on without it. */
    val number: Boolean = true,
    /** This highlight's color and line width, over the step's style. */
    val style: UiStyle? = null,
)

/** The side of its target an arrow's callout sits on, as a direction from the target: [dx] and [dy] are -1, 0 or 1. */
enum class UiArrowSide(val wire: String, val dx: Int, val dy: Int) {
    AUTO("auto", 0, 0), LEFT("left", -1, 0), RIGHT("right", 1, 0), ABOVE("above", 0, -1), BELOW("below", 0, 1),
    ABOVE_LEFT("above-left", -1, -1), ABOVE_RIGHT("above-right", 1, -1), BELOW_LEFT("below-left", -1, 1), BELOW_RIGHT("below-right", 1, 1);

    /** The side across the target; [AUTO] has none. */
    val opposite: UiArrowSide get() = entries.first { it != AUTO && it.dx == -dx && it.dy == -dy }

    companion object {
        /** The order `auto` tries: the four sides, then the diagonals. */
        val AUTO_ORDER = listOf(RIGHT, LEFT, BELOW, ABOVE, BELOW_RIGHT, ABOVE_RIGHT, BELOW_LEFT, ABOVE_LEFT)

        fun of(wire: String): UiArrowSide? = entries.firstOrNull { it.wire == wire }
    }
}

/** How an arrow ends at its target: a filled triangle, a V of two strokes, or a plain line. */
enum class UiArrowHead(val wire: String) { FILLED("filled"), OPEN("open"), NONE("none") }

/** An arrow from a callout to its highlight: the side the callout sits on, its length in logical pixels, and its head. */
data class UiArrow(val from: UiArrowSide = UiArrowSide.AUTO, val length: Int = DEFAULT_LENGTH, val head: UiArrowHead = UiArrowHead.FILLED) {
    companion object {
        const val DEFAULT_LENGTH = 60
        val LENGTHS = 20..400
    }
}

/** The color, as 0xRRGGBB, and line width of marks; a field left null takes the step's, then the default. */
data class UiStyle(val color: Int? = null, val width: Double? = null) {
    /** This style over [base]: each field this one sets wins. */
    fun over(base: UiStyle?): UiStyle = UiStyle(color ?: base?.color, width ?: base?.width)

    companion object {
        val COLORS = linkedMapOf(
            "red" to 0xE52B50, "orange" to 0xFF9F1C, "yellow" to 0xFFD60A, "green" to 0x2A9D5B,
            "blue" to 0x2F6FEB, "purple" to 0x8E44AD, "black" to 0x202020,
        )
        const val DEFAULT_COLOR = 0xE52B50
        const val DEFAULT_WIDTH = 2.5
        val WIDTHS = 1.0..8.0
    }
}

/** Parses one entry of a screenshot step's `highlight`, and a screenshot's `style`. */
object UiHighlightSpec {
    val FIELDS = UiSteps.TARGET_FIELDS + setOf(
        "row", "index", "label", "lines", "symbol", "file", "click", "inspection", "console", "contains",
        "breadcrumb", "arrow", "outline", "number", "style",
    )
    private val ARROW_FIELDS = setOf("from", "length", "head")
    private val STYLE_FIELDS = setOf("color", "width")
    private val HEX = Regex("#([0-9A-Fa-f]{6})")

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
            val breadcrumb = e.boolean("breadcrumb") ?: false
            // A symbol's nth counts its occurrences; a locator's picks one of several matches.
            val code = lines != null || symbol != null || console != null
            val target = UiSteps.locator(e)
            val kinds = listOfNotNull(target?.let { "a locator" }, lines?.let { "lines" }, symbol?.let { "symbol" }, "click".takeIf { click },
                inspection?.let { "inspection" }, console?.let { "console" }, "breadcrumb".takeIf { breadcrumb })
            require(kinds.size == 1) {
                if (kinds.isEmpty()) "a highlight needs a locator (ref, name, text, class or xpath), lines, symbol, click, inspection, console or breadcrumb, or is \"${UiSteps.BREADCRUMB}\""
                else "a highlight takes one of a locator, lines, symbol, click, inspection, console or breadcrumb, not ${kinds.joinToString(" and ")}"
            }
            val file = e.string("file")
            require(file == null || lines != null || symbol != null) { "file goes with a highlight of lines or a symbol" }
            val contains = e.string("contains")
            require((console != null) == (contains != null)) { "a console highlight needs contains, the text of the lines to outline, and contains goes with console" }
            require(e.string("row") == null && e.int("index") == null || target != null) { "row and index go with a highlight of a control" }
            val arrow = e["arrow"]?.let(::parseArrow)
            val outline = e.boolean("outline") ?: true
            require(outline || arrow != null) { "outline false needs arrow: without both, the highlight draws nothing" }
            require(e.boolean("number") != true) { "number takes only false; highlights are numbered by default when the picture is" }
            UiHighlight(
                target, breadcrumb = breadcrumb, row = e.string("row"), index = e.int("index"), label = e.string("label"),
                lines = lines, symbol = symbol, file = file, nth = if (code) e.int("nth") else null,
                click = click, inspection = inspection, console = console, contains = contains,
                arrow = arrow, outline = outline, number = e.boolean("number") ?: true, style = e["style"]?.let(::parseStyle),
            )
        }
        else -> throw IllegalArgumentException("a highlight is \"${UiSteps.BREADCRUMB}\" or an object")
    }

    private fun parseArrow(e: JsonElement): UiArrow = when {
        e is JsonPrimitive && e.booleanOrNull == true -> UiArrow()
        e is JsonObject -> {
            val unknown = e.keys - ARROW_FIELDS
            require(unknown.isEmpty()) { "an arrow takes from, length, head; not ${unknown.joinToString()}" }
            val from = e.string("from")?.let { w ->
                UiArrowSide.of(w) ?: throw IllegalArgumentException("arrow from is one of ${UiArrowSide.entries.joinToString { it.wire }}; was \"$w\"")
            } ?: UiArrowSide.AUTO
            val length = e.int("length") ?: UiArrow.DEFAULT_LENGTH
            require(length in UiArrow.LENGTHS) { "arrow length is 20 to 400 logical pixels; was $length" }
            val head = e.string("head")?.let { w ->
                UiArrowHead.entries.firstOrNull { it.wire == w } ?: throw IllegalArgumentException("arrow head is filled, open, none; was \"$w\"")
            } ?: UiArrowHead.FILLED
            UiArrow(from, length, head)
        }
        else -> throw IllegalArgumentException("arrow is true or an object with from, length, head")
    }

    fun parseStyle(e: JsonElement): UiStyle {
        require(e is JsonObject) { "style is an object with color and width" }
        val unknown = e.keys - STYLE_FIELDS
        require(unknown.isEmpty()) { "style takes color and width; not ${unknown.joinToString()}" }
        val color = e.string("color")?.let { c ->
            UiStyle.COLORS[c] ?: HEX.matchEntire(c)?.groupValues?.get(1)?.toInt(16)
                ?: throw IllegalArgumentException("style color is one of ${UiStyle.COLORS.keys.joinToString()}, or #RRGGBB; was \"$c\"")
        }
        val width = e.primitive("width")?.let { p -> p.doubleOrNull ?: throw IllegalArgumentException("style width is a number of logical pixels, 1 to 8") }
        require(width == null || width in UiStyle.WIDTHS) { "style width is 1 to 8 logical pixels; was $width" }
        return UiStyle(color, width)
    }
}
