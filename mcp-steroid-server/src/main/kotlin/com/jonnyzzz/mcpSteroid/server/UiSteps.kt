/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    val error: String? = null,
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
    /** On a screenshot step: the picture's file name, without folder or extension. Required there. */
    val save: String? = null,
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
    private val FIELDS = TARGET_FIELDS + setOf(
        "action", "button", "count", "modifiers", "offset_x", "offset_y", "keys", "row", "index", "for", "title", "timeout_ms",
        "file", "line", "column", "symbol", "id", "pages",
        "intent", "bug", "soft", "not", "is", "value", "contains", "matches", "caret", "notification", "error",
        "page", "registry", "advanced", "command", "code", "modal", "option", "inspection", "component", "field", "tab", "hide", "save", "side",
    )
    val SIDES = setOf("frontend", "backend")
    private val SAVE_NAME = Regex("[A-Za-z0-9._-]{1,80}")
    private val BUTTONS = setOf("left", "right", "middle")
    /** Actions whose "text" is what they enter, look for in the editor or write, not a target. */
    private val TEXT_IS_INPUT = setOf(UiAction.TYPE, UiAction.FILL, UiAction.GOTO, UiAction.WRITE)
    private val NEEDS_TARGET = setOf(
        UiAction.CLICK, UiAction.HOVER, UiAction.FILL, UiAction.CHECK, UiAction.UNCHECK, UiAction.SELECT, UiAction.INSPECT, UiAction.SCROLL,
    )
    /** Actions that take a row of a list, tree, table or tabbed pane: "row", "index" or a row ref. */
    private val ROW_ACTIONS = setOf(UiAction.SELECT, UiAction.INSPECT, UiAction.CLICK, UiAction.HOVER, UiAction.SCROLL, UiAction.EXPECT)
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
            error = obj.string("error"),
            page = obj.string("page"),
            registry = obj.string("registry"),
            advanced = obj.string("advanced"),
            option = obj.string("option"),
            inspection = obj.string("inspection"),
            component = obj.string("component"),
            field = obj.string("field"),
            tab = obj.string("tab"),
            hide = obj.boolean("hide") ?: false,
            save = obj.string("save"),
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

    private fun validate(step: UiStep) {
        val action = step.action.wire
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
                step.caret?.let { "caret" }, step.notification?.let { "notification" }, step.error?.let { "error" },
            )
            require(expectOnly.isEmpty()) { "${expectOnly.joinToString()} go(es) with expect, not $action" }
            if (step.action != UiAction.SET) require(step.value == null) { "value goes with expect and set, not $action" }
        }
        if (step.modal != null) require(step.action == UiAction.CODE) { "modal goes with code, not $action" }
        step.side?.let { require(it in SIDES) { "unknown side '$it'; use frontend or backend" } }
        if (step.tab != null || step.hide) require(step.action == UiAction.TOOLWINDOW) { "tab and hide go with toolwindow, not $action" }
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
                val kinds = listOfNotNull(step.registry, step.advanced, step.option, step.inspection, step.component)
                require(kinds.size == 1) { "$action needs exactly one of registry, advanced, option, inspection or component" }
                if (step.field != null) require(step.component != null) { "field goes with component" }
                if (step.action == UiAction.SET) {
                    require(step.value != null) { "set needs a value" }
                    if (step.component != null) require(!step.field.isNullOrBlank()) { "set on a component needs the field to change" }
                }
            }
            UiAction.TOOLWINDOW -> {
                require(!step.id.isNullOrBlank()) { "toolwindow needs the tool window's id, such as \"Project\" or \"Problems View\"" }
                require(!(step.hide && step.tab != null)) { "hide a tool window or select its tab, not both" }
            }
            UiAction.WRITE -> {
                require(!step.file.isNullOrBlank()) { "write needs a file" }
                require(step.text != null) { "write needs text, the whole new content of the file" }
            }
            UiAction.PERF -> require(!step.command.isNullOrBlank()) { "perf needs a command, such as \"%openFile src/A.kt\"" }
            // A fixed name lines the pictures of two replays up with each other.
            UiAction.SCREENSHOT -> require(step.save != null) { "screenshot needs save, the picture's name, such as \"settings-appearance\"" }
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
        val subjects = listOfNotNull(
            step.target?.let { "a target" }, step.title?.let { "title" }, step.file?.let { "file" },
            step.notification?.let { "notification" }, step.error?.let { "error" },
        )
        require(subjects.size == 1) {
            if (subjects.isEmpty()) "expect needs one subject: a target, title, file, notification or error"
            else "expect checks one subject, not ${subjects.joinToString(" and ")}"
        }
        require(listOfNotNull(step.value, step.contains, step.matches).size <= 1) { "pass one of value, contains or matches" }
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
                require(!(rowNamed && textCheck)) { "a row is checked by its text in row; drop value, contains and matches" }
            }
            step.title != null -> {
                require(step.state == null || step.state == UiExpectState.VISIBLE || step.state == UiExpectState.HIDDEN) {
                    "a window is visible or hidden"
                }
                require(!textCheck && step.caret == null && step.line == null) { "a window takes is=visible or is=hidden only" }
            }
            step.file != null -> {
                require(step.state == null) { "a file takes value, contains, matches or caret, not is" }
                require(textCheck || step.caret != null) { "expect on a file needs value, contains, matches or caret" }
                require(!(textCheck && step.caret != null)) { "check the text or the caret, not both" }
                step.caret?.let { require(CARET.matches(it)) { "caret is line:column, both 1-based, such as \"3:14\"" } }
                step.line?.let { require(it >= 1) { "line is 1-based, was $it" } }
            }
            else -> require(step.state == null && !textCheck && step.caret == null && step.line == null) {
                "a notification or an error is matched by its text alone; add \"not\":true to expect none"
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
