/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    INSPECT("inspect"),
    SCROLL("scroll"),
    EXPECT("expect"),
    SETTINGS("settings"),
    GET("get"),
    SET("set"),
    TOOLWINDOW("toolwindow"),
    WINDOW("window"),
    MENU("menu"),
    WRITE("write"),
    PERF("perf"),
    CODE("code"),
    SCREENSHOT("screenshot"),
}

enum class UiWaitCondition(val wire: String) {
    VISIBLE("visible"),
    HIDDEN("hidden"),
    ENABLED("enabled"),
    WINDOW("window"),
    IDLE("idle"),
}

/** The state an expect step checks with "is". The row states need a "row" or an "index". */
enum class UiExpectState(val wire: String, val ofRow: Boolean = false) {
    VISIBLE("visible"),
    HIDDEN("hidden"),
    ENABLED("enabled"),
    DISABLED("disabled"),
    CHECKED("checked"),
    UNCHECKED("unchecked"),
    FOCUSED("focused"),
    EDITABLE("editable"),
    SELECTED("selected", ofRow = true),
    EXPANDED("expanded", ofRow = true),
    COLLAPSED("collapsed", ofRow = true),
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

/** One highlight of a screenshot: a control by its locator, one of its rows, or the Settings page's breadcrumb. */
data class UiHighlight(
    val target: UiTarget?,
    val breadcrumb: Boolean = false,
    val row: String? = null,
    val index: Int? = null,
    /** Text drawn beside the highlight's number. */
    val label: String? = null,
)

/** What a screenshot shows of its window: the Settings page, the area of its highlights, or one control. */
sealed interface UiCrop {
    object Page : UiCrop {
        override fun toString() = "page"
    }
    object Highlights : UiCrop {
        override fun toString() = "highlights"
    }
    data class Control(val target: UiTarget) : UiCrop
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
    /** For scroll: how many pages to scroll the target's scroll pane, down when positive. */
    val pages: Int? = null,
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
    /** What the step is for, in the words of the report it reproduces. Echoed in the step's report. */
    val intent: String? = null,
    /** On an expect or code step: the bug that is present when the step fails. */
    val bug: String? = null,
    /** On an expect step: a failure is reported and the run goes on. */
    val soft: Boolean = false,
    /** On an expect step: the check must not hold. */
    val negate: Boolean = false,
    val state: UiExpectState? = null,
    val value: String? = null,
    val contains: String? = null,
    val matches: String? = null,
    /** On an expect step: how many controls the target matches. */
    val expectCount: Int? = null,
    /** On an expect step with a file: the caret as `line:column`, both 1-based. */
    val caret: String? = null,
    val notification: String? = null,
    /** On an expect step: a banner above an open editor whose text contains this, such as "Module JDK is not defined". */
    val banner: String? = null,
    val error: String? = null,
    /** On an expect step: a file whose editor the IDE shows, by its path or name. */
    val editor: String? = null,
    /**
     * On an expect step: a text of an `idea.log` line written since the run started. On a get or a set: a log
     * category, such as `#com.jetbrains.rdserver.fileEditors`, whose level a set changes.
     */
    val log: String? = null,
    /** On a get step: the open editors, per side and per JetBrains Client session. */
    val editors: Boolean = false,
    /** On a get step: this side's memory, as the status bar's memory indicator shows it, and the GC's load. */
    val memory: Boolean = false,
    /** On an expect step: the memory figure it checks, one of [UiSteps.MEMORY_METRICS], against [below]. */
    val memoryMetric: String? = null,
    /** On an expect of memory: the figure must be under this, in MB for sizes. */
    val below: Long? = null,
    /** On a get step: the builds and syncs that finished, with the errors of each. */
    val builds: Boolean = false,
    /** On a get step: the notifications the IDE showed, as the Notifications tool window lists them. */
    val notifications: Boolean = false,
    /** On a get step: the problems the editor highlights in an open file by its path, or in every open file for "". */
    val problems: String? = null,
    /** On a get of problems: the least severe level to list, one of [UiSteps.SEVERITIES]; errors only without it. */
    val severity: String? = null,
    /** On a get step: the diff of the project files the run changed so far. */
    val changes: Boolean = false,
    /**
     * On a get or expect step: the output of the latest run by its name, as its Run or Debug tab shows it, such as
     * "App", or "" for the latest run. A get reads its last [lines] lines; an expect checks it with contains or matches.
     */
    val console: String? = null,
    /** On a get of a console: how many of its last lines to read. */
    val lines: Int? = null,
    /** On an expect step: exactly the project files the run changed, created, deleted or moved; empty for none. */
    val changed: List<String>? = null,
    /**
     * On an expect step: lines the run's diff has, one after another, each starting with `+` (added), `-` (removed) or a
     * space (unchanged), matched without their indentation; with a file, in that file's diff.
     */
    val diff: String? = null,
    /** On an expect step with a file: a file holding the whole text the file must have, relative to the project. */
    val golden: String? = null,
    val page: String? = null,
    val registry: String? = null,
    val advanced: String? = null,
    /** A get or set of an option as Search Everywhere lists it, such as "Show line numbers". */
    val option: String? = null,
    /** A get or set of an inspection in the project's current profile, by its short name. */
    val inspection: String? = null,
    /** A get or set of a persistent settings component by its state name, such as "EditorSettings". */
    val component: String? = null,
    /** The option of [component] a get reads or a set changes. */
    val field: String? = null,
    /** On a toolwindow step: the content tab to select. */
    val tab: String? = null,
    /** On a toolwindow step: hide the tool window instead of showing it. */
    val hide: Boolean = false,
    /** On a toolwindow or window step: the width in logical pixels, or "fit". */
    val width: String? = null,
    /** On a toolwindow or window step: the height in logical pixels, or "fit". */
    val height: String? = null,
    /** On a window step: true fills the screen, false gives the window back its size before. */
    val maximize: Boolean? = null,
    /** On an expect step: no control in the window, or under the target, lies past an edge. */
    val layout: Boolean = false,
    /**
     * On a menu step: the item or submenu, as `View > Appearance > Compact Mode`. On a check or uncheck step: a
     * checkable main menu item, which the step runs only when its state differs.
     */
    val path: String? = null,
    /** On a menu step: how the IDE shows its main menu, one of [UiSteps.MENU_MODES]. */
    val mode: String? = null,
    /** On a write step: delete the file instead of writing it. */
    val delete: Boolean = false,
    /** On a screenshot step: the picture's file name in the execution folder, without folder or extension. */
    val save: String? = null,
    /** On a screenshot step: the picture's path, a `.png`; relative to the scenario file's folder in a scenario. */
    val out: String? = null,
    /** On a screenshot step: the controls to outline, numbered in this order. */
    val highlight: List<UiHighlight>? = null,
    /** On a screenshot step: what part of the window the picture shows; the whole window without it. */
    val crop: UiCrop? = null,
    /** On a screenshot step with a crop: the padding around it, in pixels; [UiSteps.DEFAULT_MARGIN] without it. */
    val margin: Int? = null,
    /** On a scroll step: where the target goes in its view, one of [UiSteps.ALIGNS]. */
    val align: String? = null,
    /** On a menu step: open the path's menus and leave them open instead of running an item. */
    val show: Boolean = false,
    /** On a set step: the installed theme to switch to, by name or id, or [UiSteps.THEME_SYNC]. */
    val theme: String? = null,
    /** On a get step: the installed themes. */
    val themes: Boolean = false,
    /** On a window step: the key the IDE saves the window's size under, which the step writes too; a restore uses it. */
    val dimension: String? = null,
    /**
     * Split Mode: `backend` runs the step on the Remote Development backend, where the project, its files and the
     * windows the backend draws are; the call's own side otherwise. Ignored in a regular IDE.
     */
    val side: String? = null,
    val command: String? = null,
    val code: String? = null,
    val modal: String? = null,
    /** The step as it was written, which a recording rewrites into a portable step. */
    val source: JsonObject? = null,
)

/**
 * Parses the `steps` argument of steroid_ui: a JSON array of flat objects such as
 * `{"action":"click","name":"OK"}`. Unknown actions and fields fail with the step's number, so a typo
 * never runs as a different step.
 */
object UiSteps {
    const val DEFAULT_TIMEOUT_MS = 5_000L
    const val MAX_TIMEOUT_MS = 60_000L

    /** Steps that compile or run a playback script take longer than a UI step. */
    const val LONG_DEFAULT_TIMEOUT_MS = 60_000L
    const val LONG_MAX_TIMEOUT_MS = 600_000L
    private val LONG_ACTIONS = setOf(UiAction.PERF, UiAction.CODE)

    val MODALS = setOf("smart_non_modal", "non_modal", "unleashed", "dialog")

    private val TARGET_FIELDS = setOf("ref", "name", "text", "class", "xpath", "nth")
    internal val FIELDS = TARGET_FIELDS + setOf(
        "action", "button", "count", "modifiers", "offset_x", "offset_y", "keys", "row", "index", "for", "title", "timeout_ms",
        "file", "line", "column", "symbol", "id", "pages",
        "intent", "bug", "soft", "not", "is", "value", "contains", "matches", "caret", "notification", "banner", "error",
        "page", "registry", "advanced", "command", "code", "modal", "option", "inspection", "component", "field", "tab", "hide", "save", "side",
        "editor", "editors", "log", "memory", "below", "width", "height", "maximize", "layout", "path", "mode", "delete",
        "builds", "changes", "console", "lines", "changed", "diff", "golden", "notifications", "problems", "severity",
        "out", "highlight", "crop", "margin", "align", "show", "theme", "themes", "dimension",
    )
    private val HIGHLIGHT_FIELDS = TARGET_FIELDS + setOf("row", "index", "label")
    /** The highlight of the Settings page's breadcrumb. */
    const val BREADCRUMB = "breadcrumb"
    /** The padding around a crop without a margin, and the margins a step takes, in pixels. */
    const val DEFAULT_MARGIN = 16
    val MARGINS = 0..200
    /** Where a scroll with align puts its target: at the top of its view, or in the middle. */
    val ALIGNS = setOf("top", "center")
    /** The extensions a screenshot's out takes; a path without one is a PNG. */
    val PICTURE_EXTENSIONS = setOf("png", "jpg", "jpeg")

    /** The extension of the file [path] names, lower case, or null when its name has none. */
    fun pictureExtension(path: String): String? =
        path.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }

    /** The theme a set takes to follow the OS's light or dark mode again, which a restore uses. */
    const val THEME_SYNC = "sync"
    /** The levels a get of problems takes as its severity, from the most severe. */
    val SEVERITIES = listOf("error", "warning", "weak_warning", "info")
    /** How many console lines a get reads without [UiStep.lines], and at most. */
    const val DEFAULT_CONSOLE_LINES = 40
    const val MAX_CONSOLE_LINES = 2_000
    val SIDES = setOf("frontend", "backend")
    /** How a menu step shows the main menu: under the Main Menu button, merged into the main toolbar, or in a bar of its own. */
    val MENU_MODES = setOf("hamburger", "merged", "toolbar")
    /**
     * The figures an expect of memory checks: the heap in use right after a full GC, which the check runs first, and
     * the heap in use now, both in MB; the thread count; and the overloaded-GC signals of the last 15 minutes.
     */
    val MEMORY_METRICS = setOf("heap_after_gc", "heap", "threads", "gc_signals")
    /** The levels a set of a log category takes; default puts the category back to the IDE's configuration. */
    val LOG_LEVELS = setOf("trace", "debug", "all", "default")
    private val EDITOR_STATES = setOf(UiExpectState.VISIBLE, UiExpectState.FOCUSED, UiExpectState.HIDDEN)
    private val SAVE_NAME = Regex("[A-Za-z0-9._-]{1,80}")
    const val FIT = "fit"
    /** The sizes a toolwindow or window step takes, in logical pixels. */
    val SIZES = 50..20_000
    internal val BUTTONS = setOf("left", "right", "middle")
    /** Actions whose "text" is what they enter, look for in the editor or write, not a target. */
    private val TEXT_IS_INPUT = setOf(UiAction.TYPE, UiAction.FILL, UiAction.GOTO, UiAction.WRITE)
    private val NEEDS_TARGET = setOf(
        UiAction.CLICK, UiAction.HOVER, UiAction.FILL, UiAction.SELECT, UiAction.INSPECT, UiAction.SCROLL,
    )
    /** Actions that take a row of a list, tree, table or tabbed pane: "row", "index" or a row ref. */
    private val ROW_ACTIONS = setOf(UiAction.SELECT, UiAction.INSPECT, UiAction.CLICK, UiAction.HOVER, UiAction.SCROLL, UiAction.EXPECT, UiAction.FILL)
    private val PATH_ACTIONS = setOf(UiAction.MENU, UiAction.CHECK, UiAction.UNCHECK)
    private val ROW_REF = Regex("""(e\d+)#(\d+)""")
    private val CARET = Regex("""(\d+):(\d+)""")

    /**
     * [step] with a row ref such as `e12#3` split into its control's ref and the row index, as a snapshot lists row #3
     * under `[ref=e12]` and a marked screenshot labels it.
     */
    fun withRowRef(step: UiStep): UiStep {
        val ref = step.target?.ref ?: return step
        val m = ROW_REF.matchEntire(ref) ?: return step
        require(step.row == null && step.index == null) { "the row ref $ref already names row #${m.groupValues[2]}; drop row and index" }
        require(step.action in ROW_ACTIONS) {
            "a row ref such as $ref works with ${ROW_ACTIONS.joinToString { it.wire }}; ${step.action.wire} acts on a whole control"
        }
        val index = m.groupValues[2].toIntOrNull() ?: throw IllegalArgumentException("row index in $ref is too large")
        return step.copy(target = step.target.copy(ref = m.groupValues[1]), index = index)
    }

    fun parse(json: String): List<UiStep> {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("steps is not valid JSON: ${e.message}", e)
        }
        val array = root as? JsonArray ?: throw IllegalArgumentException("steps must be a JSON array of step objects")
        return parse(array)
    }

    fun parse(array: JsonArray): List<UiStep> = array.mapIndexed { i, element ->
        val obj = element as? JsonObject ?: throw IllegalArgumentException("step ${i + 1} must be an object")
        try {
            parseStep(obj)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("step ${i + 1}: ${e.message}", e)
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
            nth = obj.int("nth").takeIf { action != UiAction.GOTO },
        ).takeIf { it.ref != null || it.name != null || it.text != null || it.cls != null || it.xpath != null }
        val long = action in LONG_ACTIONS
        val step = UiStep(
            action = action,
            target = target,
            button = obj.string("button"),
            count = obj.int("count").takeIf { action != UiAction.EXPECT } ?: 1,
            modifiers = obj.string("modifiers"),
            offsetX = obj.int("offset_x"),
            offsetY = obj.int("offset_y"),
            text = obj.string("text").takeIf { action in TEXT_IS_INPUT },
            keys = obj.string("keys"),
            row = obj.string("row"),
            index = obj.int("index"),
            pages = obj.int("pages"),
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
            nth = obj.int("nth").takeIf { action == UiAction.GOTO } ?: 0,
            timeoutMs = (obj.long("timeout_ms") ?: if (long) LONG_DEFAULT_TIMEOUT_MS else DEFAULT_TIMEOUT_MS)
                .coerceIn(0, if (long) LONG_MAX_TIMEOUT_MS else MAX_TIMEOUT_MS),
            intent = obj.string("intent"),
            bug = obj.string("bug"),
            soft = obj.boolean("soft") ?: false,
            negate = obj.boolean("not") ?: false,
            state = obj.string("is")?.let { wanted ->
                UiExpectState.entries.firstOrNull { it.wire == wanted }
                    ?: throw IllegalArgumentException("unknown state '$wanted'; use one of ${UiExpectState.entries.joinToString { it.wire }}")
            },
            value = obj.string("value"),
            contains = obj.string("contains"),
            matches = obj.string("matches"),
            expectCount = obj.int("count").takeIf { action == UiAction.EXPECT },
            caret = obj.string("caret"),
            notification = obj.string("notification"),
            banner = obj.string("banner"),
            // true reads as any error, as "" does: a flag is the natural spelling, and no error summary is "true".
            editor = obj.string("editor"),
            log = obj.string("log"),
            editors = obj.boolean("editors") ?: false,
            // true on a get; the name of a figure on an expect.
            memory = (obj["memory"] as? JsonPrimitive)?.takeIf { !it.isString }?.let { obj.boolean("memory") } ?: false,
            memoryMetric = (obj["memory"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            below = obj.long("below"),
            builds = obj.boolean("builds") ?: false,
            notifications = obj.boolean("notifications") ?: false,
            // true for every open file, which the step holds as ""; a path for one file.
            problems = (obj["problems"] as? JsonPrimitive)?.let { p ->
                if (p.isString) p.content.ifBlank { throw IllegalArgumentException("problems is true, for every open file, or a file's path") }
                else if (p.booleanOrNull == true) ""
                else throw IllegalArgumentException("problems is true, for every open file, or a file's path")
            },
            severity = obj.string("severity"),
            changes = obj.boolean("changes") ?: false,
            console = obj.string("console"),
            lines = obj.int("lines"),
            changed = obj["changed"]?.let { v ->
                (v as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("changed lists file paths as strings") }
                    ?: throw IllegalArgumentException("changed is a JSON array of file paths, [] for none")
            },
            diff = obj.string("diff"),
            golden = obj.string("golden"),
            error = (obj["error"] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == true }?.let { "" } ?: obj.string("error"),
            page = obj.string("page"),
            registry = obj.string("registry"),
            advanced = obj.string("advanced"),
            option = obj.string("option"),
            inspection = obj.string("inspection"),
            component = obj.string("component"),
            field = obj.string("field"),
            tab = obj.string("tab"),
            hide = obj.boolean("hide") ?: false,
            width = obj.string("width"),
            height = obj.string("height"),
            maximize = obj.boolean("maximize"),
            layout = obj.boolean("layout") ?: false,
            path = obj.string("path"),
            mode = obj.string("mode"),
            delete = obj.boolean("delete") ?: false,
            save = obj.string("save"),
            out = obj.string("out"),
            highlight = obj["highlight"]?.let { h ->
                val items = h as? JsonArray ?: throw IllegalArgumentException("highlight is a JSON array of highlights")
                require(items.isNotEmpty()) { "highlight lists at least one control, or leave it out" }
                items.map(::parseHighlight)
            },
            crop = obj["crop"]?.let(::parseCrop),
            margin = obj.int("margin"),
            align = obj.string("align"),
            show = obj.boolean("show") ?: false,
            theme = obj.string("theme"),
            themes = obj.boolean("themes") ?: false,
            dimension = obj.string("dimension"),
            side = obj.string("side"),
            command = obj.string("command"),
            code = obj.string("code"),
            modal = obj.string("modal"),
            source = obj,
        )
        val split = withRowRef(step)
        validate(split)
        return split
    }

    private fun locator(obj: JsonObject): UiTarget? = UiTarget(
        ref = obj.string("ref"), name = obj.string("name"), text = obj.string("text"),
        cls = obj.string("class"), xpath = obj.string("xpath"), nth = obj.int("nth"),
    ).takeIf { it.ref != null || it.name != null || it.text != null || it.cls != null || it.xpath != null }

    private fun parseHighlight(e: JsonElement): UiHighlight = when {
        e is JsonPrimitive && e.isString && e.content == BREADCRUMB -> UiHighlight(null, breadcrumb = true)
        e is JsonObject -> {
            val unknown = e.keys - HIGHLIGHT_FIELDS
            require(unknown.isEmpty()) { "a highlight has unknown field(s) ${unknown.joinToString()}; it takes ${HIGHLIGHT_FIELDS.sorted().joinToString()}" }
            val target = locator(e) ?: throw IllegalArgumentException("a highlight needs a locator: ref, name, text, class or xpath, or is \"$BREADCRUMB\"")
            UiHighlight(target, row = e.string("row"), index = e.int("index"), label = e.string("label"))
        }
        else -> throw IllegalArgumentException("a highlight is \"$BREADCRUMB\" or an object with a locator")
    }

    private fun parseCrop(e: JsonElement): UiCrop = when {
        e is JsonPrimitive && e.isString && e.content == "page" -> UiCrop.Page
        e is JsonPrimitive && e.isString && e.content == "highlights" -> UiCrop.Highlights
        e is JsonObject -> {
            val unknown = e.keys - TARGET_FIELDS
            require(unknown.isEmpty()) { "crop takes a locator: ${TARGET_FIELDS.sorted().joinToString()}" }
            UiCrop.Control(locator(e) ?: throw IllegalArgumentException("crop needs a locator: ref, name, text, class or xpath"))
        }
        else -> throw IllegalArgumentException("crop is \"page\", \"highlights\" or a locator object")
    }

    private fun validate(step: UiStep) {
        val action = step.action.wire
        val captureFields = listOfNotNull(step.out?.let { "out" }, step.highlight?.let { "highlight" }, step.crop?.let { "crop" }, step.margin?.let { "margin" })
        if (step.action != UiAction.SCREENSHOT) require(captureFields.isEmpty()) { "${captureFields.joinToString()} go(es) with screenshot, not $action" }
        step.out?.let {
            require(it.isNotBlank()) { "out is the picture's path, such as \"C:/docs/appearance.png\"" }
            val ext = pictureExtension(it)
            require(ext == null || ext in PICTURE_EXTENSIONS) { "out is a .png, the default when the path has no extension, or a .jpg; was \"$it\"" }
        }
        step.margin?.let { require(it in MARGINS) { "margin is from ${MARGINS.first} to ${MARGINS.last} pixels, was $it" } }
        if (step.crop == UiCrop.Highlights) require(!step.highlight.isNullOrEmpty()) { "crop \"highlights\" needs highlight" }
        step.highlight?.forEach { require(it.row == null || it.index == null) { "a highlight takes row or index, not both" } }
        step.align?.let {
            require(step.action == UiAction.SCROLL) { "align goes with scroll, not $action" }
            require(it in ALIGNS) { "align is top or center, was $it" }
            require(step.pages == null) { "align places the target; drop pages" }
        }
        if (step.show) {
            require(step.action == UiAction.MENU) { "show goes with menu, not $action" }
            require(!step.path.isNullOrBlank()) { "menu with show needs a path, such as \"View > Appearance\"" }
        }
        if (step.theme != null) require(step.action == UiAction.SET) { "theme goes with set, not $action" }
        if (step.themes) require(step.action == UiAction.GET) { "themes goes with get, not $action" }
        step.dimension?.let {
            require(step.action == UiAction.WINDOW) { "dimension goes with window, not $action" }
            require(it.isNotBlank() && step.width?.toIntOrNull() != null && step.height?.toIntOrNull() != null) {
                "window with dimension needs width and height in pixels"
            }
        }
        if (step.action in NEEDS_TARGET) require(step.target != null) { "$action needs a target: ref, name, text, class or xpath" }
        step.button?.let { require(it in BUTTONS) { "unknown button '$it'; use left, right or middle" } }
        require(step.count in 1..2) { "count must be 1 or 2, was ${step.count}" }
        if (step.row != null || step.index != null) {
            require(step.action in ROW_ACTIONS) { "row and index go with ${ROW_ACTIONS.joinToString { it.wire }}, not $action" }
            require(step.row == null || step.index == null) { "pass row or index, not both" }
        }
        step.index?.let { require(it >= 0) { "index is 0-based, was $it" } }
        if (step.pages != null) {
            require(step.action == UiAction.SCROLL) { "pages goes with scroll, not $action" }
            require(step.row == null && step.index == null) { "scroll takes pages or a row, not both" }
        }
        if (step.bug != null) {
            require(step.action == UiAction.EXPECT || step.action == UiAction.CODE) { "bug goes with expect and code, not $action" }
            require(step.bug.isNotBlank()) { "bug names the bug a failure shows, such as \"the old name stays in the import\"" }
        }
        if (step.action != UiAction.EXPECT) {
            require(!step.soft) { "soft goes with expect, not $action" }
            require(!step.negate) { "not goes with expect, not $action" }
            val expectOnly = listOfNotNull(
                step.state?.let { "is" }, step.contains?.let { "contains" }, step.matches?.let { "matches" },
                step.caret?.let { "caret" }, step.notification?.let { "notification" }, step.banner?.let { "banner" }, step.error?.let { "error" },
                step.editor?.let { "editor" }, step.changed?.let { "changed" }, step.diff?.let { "diff" }, step.golden?.let { "golden" },
            )
            require(expectOnly.isEmpty()) { "${expectOnly.joinToString()} go(es) with expect, not $action" }
            if (step.action != UiAction.SET) require(step.value == null) { "value goes with expect and set, not $action" }
        }
        if (step.modal != null) require(step.action == UiAction.CODE) { "modal goes with code, not $action" }
        step.side?.let { require(it in SIDES) { "unknown side '$it'; use frontend or backend" } }
        if (step.tab != null || step.hide) require(step.action == UiAction.TOOLWINDOW) { "tab and hide go with toolwindow, not $action" }
        for ((field, size) in listOf("width" to step.width, "height" to step.height)) {
            if (size == null) continue
            require(step.action == UiAction.TOOLWINDOW || step.action == UiAction.WINDOW) { "$field goes with toolwindow and window, not $action" }
            require(size == FIT || size.toIntOrNull()?.let { it in SIZES } == true) {
                "$field is \"fit\" or a size in logical pixels from ${SIZES.first} to ${SIZES.last}, was $size"
            }
        }
        if (step.maximize != null) {
            require(step.action == UiAction.WINDOW) { "maximize goes with window, not $action" }
            require(step.width == null && step.height == null) { "maximize fills the screen; drop width and height" }
        }
        if (step.layout) require(step.action == UiAction.EXPECT) { "layout goes with expect, not $action" }
        if (step.path != null) require(step.action in PATH_ACTIONS) { "path goes with menu, check and uncheck, not $action" }
        step.mode?.let {
            require(step.action == UiAction.MENU) { "mode goes with menu, not $action" }
            require(it in MENU_MODES) { "unknown menu mode '$it'; use one of ${MENU_MODES.joinToString()}" }
            require(step.path == null) { "a menu step runs a path or sets the mode, not both" }
        }
        if (step.delete) require(step.action == UiAction.WRITE) { "delete goes with write, not $action" }
        step.save?.let {
            require(step.action == UiAction.SCREENSHOT) { "save goes with screenshot, not $action" }
            require(SAVE_NAME.matches(it) && !it.startsWith(".")) { "save is a plain file name of letters, digits, '.', '_' and '-', such as \"settings-appearance\"" }
        }
        if (step.action != UiAction.GET && step.action != UiAction.SET) {
            val config = listOfNotNull(
                step.registry?.let { "registry" }, step.advanced?.let { "advanced" }, step.option?.let { "option" },
                step.inspection?.let { "inspection" }, step.component?.let { "component" }, step.field?.let { "field" },
            )
            require(config.isEmpty()) { "${config.joinToString()} go(es) with get and set, not $action" }
            require(!step.editors) { "editors goes with get, not $action" }
            if (step.action != UiAction.EXPECT) require(!step.memory && step.memoryMetric == null) { "memory goes with get and expect, not $action" }
            if (step.action != UiAction.EXPECT) require(step.log == null) { "log goes with expect, get and set, not $action" }
            require(!step.builds && !step.changes) { "builds and changes go with get, not $action" }
            require(!step.notifications && step.problems == null && step.severity == null) { "notifications, problems and severity go with get, not $action" }
            if (step.action != UiAction.EXPECT) require(step.console == null) { "console goes with get and expect, not $action" }
        }
        if (step.lines != null) {
            require(step.action == UiAction.GET && step.console != null) { "lines goes with a get of a console" }
            require(step.lines in 1..MAX_CONSOLE_LINES) { "lines is from 1 to $MAX_CONSOLE_LINES, was ${step.lines}" }
        }
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
            UiAction.EXPECT -> validateExpect(step)
            UiAction.SETTINGS -> require(!step.page.isNullOrBlank()) {
                "settings needs a page: its id, its name as the Settings tree shows it, or a path such as \"Editor > General\""
            }
            UiAction.GET, UiAction.SET -> {
                require(step.memoryMetric == null) { "get takes \"memory\":true; a figure such as \"heap_after_gc\" goes with expect" }
                val kinds = listOfNotNull(
                    step.registry, step.advanced, step.option, step.inspection, step.component, step.log,
                    step.file.takeIf { step.action == UiAction.GET }, "editors".takeIf { step.editors }, "memory".takeIf { step.memory },
                    "builds".takeIf { step.builds }, "changes".takeIf { step.changes }, step.console,
                    "notifications".takeIf { step.notifications }, step.problems, step.theme, "themes".takeIf { step.themes },
                )
                require(kinds.size == 1) {
                    if (step.action == UiAction.GET) "get needs exactly one of registry, advanced, option, inspection, component, log, file, editors, memory, builds, changes, console, notifications, problems or themes"
                    else "set needs exactly one of registry, advanced, option, inspection, component, log or theme"
                }
                if (step.action == UiAction.SET) require(
                    !step.editors && !step.memory && step.file == null && !step.builds && !step.changes && step.console == null &&
                        !step.notifications && step.problems == null && step.severity == null,
                ) {
                    "editors, memory, file, builds, changes, console, notifications, problems and severity go with get, not set"
                }
                step.severity?.let {
                    require(step.problems != null) { "severity goes with a get of problems" }
                    require(it in SEVERITIES) { "severity is one of ${SEVERITIES.joinToString()}, was $it" }
                }
                if (step.action == UiAction.SET && step.log != null) require(step.value?.lowercase() in LOG_LEVELS) {
                    "a log category's level is one of ${LOG_LEVELS.joinToString()}"
                }
                if (step.field != null) require(step.component != null) { "field goes with component" }
                if (step.action == UiAction.SET) {
                    if (step.theme != null) require(step.value == null) { "set of a theme takes the theme's name in theme, not a value" }
                    else require(step.value != null) { "set needs a value" }
                    if (step.component != null) require(!step.field.isNullOrBlank()) { "set on a component needs the field to change" }
                }
            }
            UiAction.TOOLWINDOW -> {
                require(!step.id.isNullOrBlank()) { "toolwindow needs the tool window's id, such as \"Project\" or \"Problems View\"" }
                require(!(step.hide && step.tab != null)) { "hide a tool window or select its tab, not both" }
                require(!(step.hide && (step.width != null || step.height != null))) { "hide a tool window or size it, not both" }
            }
            UiAction.WINDOW -> require(step.target == null || step.title == null) { "window takes a target or a title, not both" }
            UiAction.WRITE -> {
                require(!step.file.isNullOrBlank()) { "write needs a file" }
                if (step.delete) require(step.text == null) { "a write that deletes the file takes no text" }
                else require(step.text != null) { "write needs text, the whole new content of the file, or \"delete\":true" }
            }
            UiAction.CHECK, UiAction.UNCHECK -> require((step.target != null) != (step.path != null)) {
                "$action needs a target, a checkbox or toggle, or a path, a checkable main menu item, and not both"
            }
            UiAction.PERF -> require(!step.command.isNullOrBlank()) { "perf needs a command, such as \"%openFile src/A.kt\"" }
            // A fixed name lines the pictures of two replays up with each other.
            UiAction.SCREENSHOT -> {
                require(step.save != null || step.out != null) { "screenshot needs save or out: the picture's name, such as \"settings-appearance\", or its path" }
                require(step.save == null || step.out == null) { "pass save or out, not both" }
            }
            UiAction.CODE -> {
                require(!step.code.isNullOrBlank()) { "code needs code, the Kotlin body steroid_execute_code runs" }
                step.modal?.let { require(it in MODALS) { "unknown modal '$it'; use one of ${MODALS.joinToString()}" } }
            }
            else -> Unit
        }
    }

    /**
     * An expect step checks one subject: a control (a target), a window ("title"), a file's text or caret ("file"), a
     * notification or an IDE error. Each subject takes its own checks.
     */
    private fun validateExpect(step: UiStep) {
        require(!step.memory) { "expect takes a memory figure: one of ${MEMORY_METRICS.joinToString()}" }
        // A layout check takes a target as its scope, not as a second subject.
        step.golden?.let {
            require(step.file != null && it.isNotBlank()) { "golden goes with a file: the path of a file holding its whole expected text" }
            require(step.line == null && step.caret == null) { "golden checks the whole file, not a line or the caret" }
        }
        // A diff with a file checks that file's diff; alone, the whole run's.
        val subjects = listOfNotNull(
            step.target?.takeIf { !step.layout }?.let { "a target" }, "layout".takeIf { step.layout }, step.title?.let { "title" }, step.file?.let { "file" },
            step.notification?.let { "notification" }, step.banner?.let { "banner" }, step.error?.let { "error" },
            step.editor?.let { "editor" }, step.log?.let { "log" }, step.memoryMetric?.let { "memory" },
            step.console?.let { "console" }, step.changed?.let { "changed" }, step.diff?.takeIf { step.file == null }?.let { "diff" },
        )
        require(subjects.size == 1) {
            if (subjects.isEmpty()) "expect needs one subject: a target, title, file, editor, notification, banner, error, log, memory, layout, console, changed or diff"
            else "expect checks one subject, not ${subjects.joinToString(" and ")}"
        }
        if (step.memoryMetric == null) require(step.below == null) { "below goes with memory" }
        require(listOfNotNull(step.value, step.contains, step.matches, step.golden, step.diff).size <= 1) { "pass one of value, contains, matches, golden or diff" }
        step.diff?.let { d ->
            require(d.lines().any { it.isNotBlank() }) { "diff needs lines, each starting with +, - or a space" }
            require(d.lines().filter { it.isNotBlank() }.all { it[0] in "+- " }) { "each line of diff starts with + (added), - (removed) or a space (unchanged)" }
            require(step.line == null && step.caret == null) { "diff checks the file's changes, not a line or the caret" }
        }
        step.matches?.let {
            try {
                Regex(it)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("matches is not a valid regular expression: ${e.message}")
            }
        }
        require(!(step.soft && step.bug != null)) { "a bug check stops the run when it fails, so it cannot be soft" }
        val textCheck = step.value != null || step.contains != null || step.matches != null
        when {
            step.layout -> require(step.state == null && !textCheck && step.caret == null && step.line == null && step.row == null && step.index == null) {
                "layout takes a target as its scope, and nothing else"
            }
            step.target != null -> {
                require(step.caret == null && step.line == null) { "caret and line go with a file" }
                val rowNamed = step.row != null || step.index != null
                step.state?.let {
                    if (it.ofRow) require(rowNamed) { "is=${it.wire} checks a row: add row or index" }
                    else require(!rowNamed) { "is=${it.wire} checks the control; the row states are selected, expanded and collapsed" }
                }
                step.expectCount?.let {
                    require(it >= 0) { "count is 0 or more, was $it" }
                    require(step.state == null && !textCheck && !rowNamed) { "count checks how many controls match; pass it alone" }
                    require(step.target.nth == null && step.target.ref == null) { "count counts the matches of a name, text, class or xpath, without nth or ref" }
                }
                require(!(rowNamed && textCheck && step.state != null)) { "value, contains and matches check a table row's cells; drop is" }
            }
            step.title != null -> {
                require(step.state == null || step.state == UiExpectState.VISIBLE || step.state == UiExpectState.HIDDEN) {
                    "a window is visible or hidden"
                }
                require(!textCheck && step.caret == null && step.line == null) { "a window takes is=visible or is=hidden only" }
            }
            step.console != null -> require(step.state == null && step.value == null && step.caret == null && step.line == null && (step.contains != null || step.matches != null)) {
                "expect on a console needs contains or matches, which the output of its latest run is checked with"
            }
            step.changed != null -> require(step.state == null && !textCheck && step.caret == null && step.line == null) { "changed takes the list of files alone" }
            step.diff != null && step.file == null -> require(step.state == null && step.caret == null && step.line == null) { "diff takes its lines, and a file to narrow it" }
            step.file != null -> {
                require(step.state == null) { "a file takes value, contains, matches, golden, diff or caret, not is" }
                require(textCheck || step.caret != null || step.golden != null || step.diff != null) { "expect on a file needs value, contains, matches, golden, diff or caret" }
                require(!(textCheck && step.caret != null)) { "check the text or the caret, not both" }
                step.caret?.let { require(CARET.matches(it)) { "caret is line:column, both 1-based, such as \"3:14\"" } }
                step.line?.let { require(it >= 1) { "line is 1-based, was $it" } }
            }
            step.memoryMetric != null -> {
                require(step.memoryMetric in MEMORY_METRICS) { "memory is one of ${MEMORY_METRICS.joinToString()}, was ${step.memoryMetric}" }
                require(step.below != null && step.below >= 0) { "expect on memory needs below: a limit, in MB for heap_after_gc and heap" }
                require(step.state == null && !textCheck && step.caret == null && step.line == null) { "a memory figure takes below only" }
            }
            step.editor != null -> {
                require(step.state == null || step.state in EDITOR_STATES) { "an editor is visible, focused or hidden" }
                require(!textCheck && step.caret == null && step.line == null) { "an editor takes is=visible, focused or hidden only; check its text with file" }
            }
            else -> require(step.state == null && !textCheck && step.caret == null && step.line == null) {
                "a notification, a banner, an error or a log line is matched by its text alone; add \"not\":true to expect none"
            }
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

    private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.let {
        it.booleanOrNull ?: throw IllegalArgumentException("$key must be true or false, was ${it.content}")
    }
}
