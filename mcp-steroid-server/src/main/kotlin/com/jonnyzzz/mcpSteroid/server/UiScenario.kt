/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A scenario: a JSON file of steroid_ui steps with what they are for, which steroid_ui replays with `scenario`. It
 * can reproduce a bug, check a feature, take pictures for a visual review, or set the IDE up. The format is described
 * for agents in `mcp-steroid://ide/ui-scenarios`; a field added here goes there too.
 */
data class UiScenario(
    val title: String,
    val issue: String?,
    val description: String?,
    /** The IDE the scenario was recorded or last repaired on, such as `IU-262.10968.63`. */
    val ide: String?,
    /** What the scenario needs open, in words: a project, a file layout, a plugin. */
    val project: String?,
    val steps: List<UiStep>,
    /** Steps that run after the others whether they pass or fail, to put the IDE back as it was. */
    val cleanup: List<UiStep>,
    /** Steps that lay the IDE out before the others, from the `setup` block; they run even when a replay starts later. */
    val setup: List<UiStep> = emptyList(),
    /** What a run does about a window or tool window that cuts controls, one of [LAYOUT_MODES]; null is `note`. */
    val layout: String? = null,
    /** What the IDE must be for the scenario to mean anything; a replay elsewhere is SKIPPED. */
    val requires: UiScenarioRequires? = null,
) {
    companion object {
        const val FORMAT_VERSION = 1

        /**
         * What a run does when a step opens a window, or shows or sizes a tool window, that cuts controls: `note`
         * says so in the step's report, `check` also counts it as a failed soft check, and `auto` makes room with the
         * step the layout line names, as it does before a click on a control past an edge and for the IDE window at
         * the start.
         */
        val LAYOUT_MODES = setOf("note", "check", "auto")

        /** The class of the IDE window, which a setup step targets so that it sizes that window whatever else shows. */
        const val IDE_FRAME_CLASS = "IdeFrameImpl"

        /** Format 1 only grows, so a name this reader does not know may come from a newer plugin. */
        private const val NEWER = " A scenario written for a newer MCP Steroid can use steps and fields this version does not know; update the plugin to replay it"
        /** `$schema` names the JSON schema an editor checks the file against, and the replay ignores it. */
        internal val FIELDS = setOf("\$schema", "scenario", "title", "issue", "description", "ide", "project", "requires", "setup", "steps", "cleanup")
        internal val SETUP_FIELDS = setOf("window", "toolwindows", "menu", "settings", "steps", "layout")

        /**
         * The steps of a `setup` block, in the order they run: settings, the menu mode, the IDE window, the tool windows,
         * then its own steps; and its layout mode.
         */
        private fun setup(obj: JsonObject): Pair<List<UiStep>, String?> {
            val unknown = obj.keys - SETUP_FIELDS
            require(unknown.isEmpty()) { "unknown setup field(s) ${unknown.joinToString()}; known fields: ${SETUP_FIELDS.sorted().joinToString()}.$NEWER" }
            fun action(name: String, fields: JsonObject) = JsonObject(mapOf("action" to JsonPrimitive(name)) + fields)
            fun objectOf(key: String, e: JsonElement): JsonObject = e as? JsonObject ?: throw IllegalArgumentException("setup.$key must be an object")
            fun steps(key: String, list: List<JsonObject>): List<UiStep> = try {
                UiSteps.parse(JsonArray(list))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("setup.$key: ${e.message}", e)
            }
            val settings = obj["settings"]?.let { s ->
                val array = s as? JsonArray ?: throw IllegalArgumentException("setup.settings must be an array of settings, such as {\"registry\":\"key\",\"value\":\"1\"}")
                steps("settings", array.map { action("set", objectOf("settings", it)) })
            }.orEmpty()
            val menu = obj["menu"]?.let {
                val mode = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("setup.menu must be a string")
                steps("menu", listOf(action("menu", JsonObject(mapOf("mode" to JsonPrimitive(mode))))))
            }.orEmpty()
            val window = obj["window"]?.let {
                val fields = objectOf("window", it)
                require(fields.keys.all { k -> k in WINDOW_FIELDS }) { "setup.window takes ${WINDOW_FIELDS.joinToString()}" }
                steps("window", listOf(action("window", JsonObject(fields + ("class" to JsonPrimitive(IDE_FRAME_CLASS))))))
            }.orEmpty()
            val toolWindows = obj["toolwindows"]?.let { tw ->
                objectOf("toolwindows", tw).map { (id, fields) ->
                    val f = objectOf("toolwindows.$id", fields)
                    require(f.keys.all { k -> k in TOOL_WINDOW_FIELDS }) { "setup.toolwindows.$id takes ${TOOL_WINDOW_FIELDS.joinToString()}" }
                    action("toolwindow", JsonObject(f + ("id" to JsonPrimitive(id))))
                }.let { steps("toolwindows", it) }
            }.orEmpty()
            val own = obj["steps"]?.let { s ->
                val array = s as? JsonArray ?: throw IllegalArgumentException("setup.steps must be an array of steps")
                steps("steps", array.map { objectOf("steps", it) })
            }.orEmpty()
            val layout = obj["layout"]?.let {
                val mode = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                require(mode in LAYOUT_MODES) { "setup.layout is one of ${LAYOUT_MODES.joinToString()}" }
                mode
            }
            return settings + menu + window + toolWindows + own to layout
        }

        private val WINDOW_FIELDS = listOf("width", "height", "maximize")
        private val TOOL_WINDOW_FIELDS = listOf("width", "height", "hide", "tab")

        fun parse(json: String): UiScenario {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("the scenario is not valid JSON: ${e.message}", e)
            }
            val obj = root as? JsonObject ?: throw IllegalArgumentException("a scenario is a JSON object with title and steps")
            val unknown = obj.keys - FIELDS
            require(unknown.isEmpty()) { "unknown scenario field(s) ${unknown.joinToString()}; known fields: ${FIELDS.sorted().joinToString()}.$NEWER" }
            val version = (obj["scenario"] as? JsonPrimitive)?.intOrNull
                ?: throw IllegalArgumentException("a scenario starts with \"scenario\": $FORMAT_VERSION, its format version")
            require(version == FORMAT_VERSION) { "scenario format $version is not known; this IDE reads format $FORMAT_VERSION. Update MCP Steroid to replay a newer format" }
            fun text(key: String): String? = obj[key]?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("$key must be a string")
            }
            fun steps(key: String): List<UiStep> {
                val array = obj[key] ?: return emptyList()
                require(array is JsonArray) { "$key must be an array of steps" }
                return try {
                    UiSteps.parse(array)
                } catch (e: IllegalArgumentException) {
                    val newer = if (e.message.orEmpty().contains("unknown ")) ".$NEWER" else ""
                    throw IllegalArgumentException("$key: ${e.message}$newer", e)
                }
            }
            val steps = steps("steps")
            require(steps.isNotEmpty()) { "a scenario needs steps" }
            val (setup, layout) = obj["setup"]?.let { s ->
                setup(s as? JsonObject ?: throw IllegalArgumentException("setup must be an object, such as {\"window\":{\"maximize\":true}}"))
            } ?: (emptyList<UiStep>() to null)
            return UiScenario(
                title = text("title")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("a scenario needs a title"),
                issue = text("issue"),
                description = text("description"),
                ide = text("ide"),
                project = text("project"),
                steps = steps,
                cleanup = steps("cleanup"),
                setup = setup,
                layout = layout,
                requires = obj["requires"]?.let {
                    UiScenarioRequires.parse(it as? JsonObject ?: throw IllegalArgumentException("requires must be an object, such as {\"since\":\"262.10000\"}"))
                },
            )
        }
    }
}

/**
 * The JSON schema of scenario format 1, which `steroid_fetch_resource` serves at [URI] for an agent or an editor to
 * check a scenario file against. It checks the shape; [UiScenario.parse] also checks what depends on several fields.
 */
object UiScenarioSchema {
    const val URI = "mcp-steroid://ide/ui-scenario-schema"

    fun text(): String = UiScenarioSchema::class.java.getResource("/ui-scenarios/scenario-1.schema.json")!!.readText()
}

/**
 * What an IDE must be for a scenario to mean anything: a build range, as a plugin's `since-build` and `until-build`
 * give it, product codes, plugins that must be enabled, operating systems, and Split Mode or a regular IDE. A replay
 * on an IDE that is not is SKIPPED, not FAILED: its steps would test something else.
 */
data class UiScenarioRequires(
    val since: String? = null,
    val until: String? = null,
    val products: List<String> = emptyList(),
    val plugins: List<String> = emptyList(),
    val os: List<String> = emptyList(),
    val mode: String? = null,
) {
    /** The IDE a replay runs on: its build number without the product code, such as `262.10968.63`. */
    data class Here(val build: String, val product: String, val plugins: Set<String>, val os: String, val mode: String)

    /** What [here] lacks, one reason each, or none when it meets every requirement. */
    fun unmet(here: Here): List<String> = listOfNotNull(
        since?.takeIf { compareBuild(here.build, it) < 0 }?.let { "build ${here.build} is older than $it" },
        until?.takeIf { compareBuild(here.build, it) > 0 }?.let { "build ${here.build} is newer than $it" },
        products.takeIf { it.isNotEmpty() && here.product !in it }?.let { "product ${here.product} is not ${it.joinToString(" or ")}" },
        (plugins - here.plugins).takeIf { it.isNotEmpty() }?.let { "plugin(s) ${it.joinToString()} not enabled" },
        os.takeIf { it.isNotEmpty() && here.os !in it }?.let { "the OS is ${here.os}, not ${it.joinToString(" or ")}" },
        mode?.takeIf { it != here.mode }?.let { "this is ${if (here.mode == SPLIT) "Split Mode" else "a regular IDE"}, not ${if (it == SPLIT) "Split Mode" else "a regular IDE"}" },
    )

    companion object {
        const val SPLIT = "split"
        val MODES = setOf(SPLIT, "monolith")
        val OSES = setOf("windows", "macos", "linux")
        internal val FIELDS = setOf("since", "until", "products", "plugins", "os", "mode")
        private val BUILD = Regex("""\d+(\.(\d+|\*))*""")

        fun parse(obj: JsonObject): UiScenarioRequires {
            val unknown = obj.keys - FIELDS
            require(unknown.isEmpty()) { "unknown requires field(s) ${unknown.joinToString()}; known fields: ${FIELDS.sorted().joinToString()}" }
            fun text(key: String): String? = obj[key]?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("requires.$key must be a string")
            }
            fun list(key: String): List<String> = obj[key]?.let { e ->
                (e as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("requires.$key lists strings") }
                    ?: throw IllegalArgumentException("requires.$key must be an array of strings")
            }.orEmpty()
            val r = UiScenarioRequires(text("since"), text("until"), list("products"), list("plugins"), list("os"), text("mode"))
            for ((key, build) in listOf("since" to r.since, "until" to r.until)) {
                build?.let { require(BUILD.matches(it)) { "requires.$key is a build number such as 262.10968 or 262.*, without the product code" } }
            }
            require(r.os.all { it in OSES }) { "requires.os lists ${OSES.joinToString()}" }
            r.mode?.let { require(it in MODES) { "requires.mode is ${MODES.joinToString(" or ")}" } }
            return r
        }

        /**
         * Compares build [a] with [bound] by their numbers, left to right; a `*` in [bound] matches any number from
         * there on, and a missing number counts as 0.
         */
        internal fun compareBuild(a: String, bound: String): Int {
            val x = a.split('.')
            val y = bound.split('.')
            for (i in 0 until maxOf(x.size, y.size)) {
                val b = y.getOrNull(i) ?: "0"
                if (b == "*") return 0
                val c = (x.getOrNull(i)?.toLongOrNull() ?: 0).compareTo(b.toLongOrNull() ?: 0)
                if (c != 0) return c
            }
            return 0
        }
    }
}

/**
 * The report of one step that a JetBrains Client ran on the backend as a steroid_ui call of that step alone. The
 * backend's response names it `step 1 <action> <target>`, as [label] does, then reports it on that line and the
 * lines after it, and ends with its own verdict and recording lines, which the client leaves out.
 */
object UiForwardedStep {
    /** [undo] holds the steps that restore what the step changed there, from the response's [UiRestore.LINE]. */
    data class Report(val passed: Boolean, val text: String, val undo: List<JsonObject> = emptyList())

    private val VERDICTS = UiVerdict.Kind.entries.map { it.name.replace('_', ' ') }

    private const val FOCUS = "; focus: "

    fun label(step: UiStep): String = "step 1 ${step.action.wire}${step.target?.let { " $it" }.orEmpty()}"

    /**
     * The backend's notices in its answer to a forwarded step, one string per notice, and the rest of the answer.
     * The backend puts its notices in the answer's first text, apart from the rest, each headed by its kind and
     * `in the backend`, as [ToolOutputContract.noticeOf] reads it. A first text without such a head is the answer itself.
     */
    fun notices(texts: List<String>): Pair<List<String>, List<String>> {
        fun heads(line: String) = ToolOutputContract.noticeOf(line).let { it.kind != "NOTICE" && it.side == "backend" }
        val first = texts.firstOrNull()
        if (texts.size < 2 || first == null || !heads(first)) return emptyList<String>() to texts
        val notices = mutableListOf<StringBuilder>()
        for (line in first.trimEnd('\n').lines()) {
            if (heads(line)) notices += StringBuilder()
            notices.last().append(line).append('\n')
        }
        return notices.map { it.toString() } to texts.drop(1)
    }

    /** [text] is the backend's response, [isError] its error flag, used only when the step's line is missing. */
    fun parse(text: String, label: String, isError: Boolean): Report {
        val all = text.lines()
        val undo = all.firstOrNull { it.startsWith(UiRestore.LINE) }?.let(UiRestore::parse).orEmpty()
        val lines = all.filterNot { it.startsWith(UiRestore.LINE) }
        val failedLine = "FAILED $label failed: "
        val start = lines.indexOfFirst { it.startsWith("$label: ") || it.startsWith(failedLine) }
        if (start < 0) return Report(!isError, lines.filterNot { it.startsWith("execution_id:") }.joinToString("\n").trim(), undo)
        val more = lines.drop(start + 1).takeWhile { it.isNotBlank() && !it.startsWith("recorded:") && VERDICTS.none(it::startsWith) }
            .filterNot { it.startsWith("execution_id:") }
        // The backend's keyboard focus is not where the user types in the JetBrains Client, so it is left out.
        val first = lines[start].removePrefix(failedLine).removePrefix("$label: ").substringBeforeLast(FOCUS)
        return Report(!lines[start].startsWith(failedLine), (listOf(first) + more).joinToString("\n"), undo)
    }
}

/** How one step of a run ended. [index] is the step's 1-based number in the list it came from. */
data class UiStepOutcome(val index: Int, val step: UiStep, val passed: Boolean, val message: String)

/**
 * What a run of steps means. Two kinds of failure are told apart for every scenario: a check that did not hold (an
 * expect, which found the IDE behaving otherwise) and a step that could not be done (anything else, which usually
 * means the UI changed and the step needs repair). A scenario that reproduces a bug marks the check that fails while
 * the bug is present with `bug`, and its verdict says whether the bug reproduced.
 */
object UiVerdict {
    enum class Kind { PASSED, FAILED, BROKEN, INCOMPLETE, REPRODUCED, NOT_REPRODUCED, SKIPPED }

    /** The verdict of a scenario whose `requires` this IDE does not meet, [unmet] naming what it lacks. */
    fun skipped(unmet: List<String>) = Verdict(Kind.SKIPPED, "SKIPPED: this IDE does not meet the scenario's requires: ${unmet.joinToString("; ")}")

    /** The verdict of a scenario whose setup step [index] failed, so that none of its steps ran. */
    fun setupBroken(index: Int) =
        Verdict(Kind.BROKEN, "BROKEN at setup step $index: the scenario's setup could not be done, so no step ran. Repair the setup")

    data class Verdict(val kind: Kind, val line: String)

    /**
     * [steps] are all the steps of the scenario or call, [outcomes] the ones that ran, in order. A run stops at its
     * first hard failure, so a failure is always the last outcome, followed only by soft failures before it.
     */
    fun of(steps: List<UiStep>, outcomes: List<UiStepOutcome>): Verdict {
        val bugSteps = steps.count { it.bug != null }
        val reproduced = outcomes.firstOrNull { it.step.bug != null && !it.passed }
        if (reproduced != null) {
            return Verdict(Kind.REPRODUCED, "REPRODUCED at step ${reproduced.index}: ${reproduced.step.bug}")
        }
        val softFailures = outcomes.filter { !it.passed && it.step.soft }
        val failure = outcomes.lastOrNull()?.takeIf { !it.passed && !it.step.soft }
        val checked = outcomes.count { it.step.bug != null && it.passed }
        val soft = if (softFailures.isEmpty()) "" else "; ${softFailures.size} soft check(s) failed: steps ${softFailures.joinToString { it.index.toString() }}"
        val repair = failure?.step?.intent?.let { ". Repair the step so that it does what it is for: $it" } ?: ". Repair the step"
        return when {
            failure != null && checked < bugSteps -> Verdict(Kind.BROKEN, "BROKEN at step ${failure.index}: the steps did not reach the bug check$repair$soft")
            failure != null && bugSteps > 0 -> Verdict(Kind.NOT_REPRODUCED, "NOT REPRODUCED: the bug check(s) passed, then step ${failure.index} failed$soft")
            failure != null && failure.step.action == UiAction.EXPECT ->
                Verdict(Kind.FAILED, "FAILED at step ${failure.index}: the check did not hold" + (failure.step.intent?.let { " ($it)" } ?: "") + soft)
            failure != null -> Verdict(Kind.BROKEN, "BROKEN at step ${failure.index}: the step could not be done$repair$soft")
            checked < bugSteps -> Verdict(Kind.INCOMPLETE, "INCOMPLETE: the run stopped before ${bugSteps - checked} bug check(s)$soft")
            bugSteps > 0 -> Verdict(Kind.NOT_REPRODUCED, "NOT REPRODUCED: every bug check passed$soft")
            // A step's layout check is an outcome of that step, not a step of its own.
            else -> Verdict(Kind.PASSED, "PASSED: all ${outcomes.distinctBy { it.index }.size} step(s)$soft")
        }
    }
}
