/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
) {
    companion object {
        const val FORMAT_VERSION = 1
        private val FIELDS = setOf("scenario", "title", "issue", "description", "ide", "project", "steps", "cleanup")

        fun parse(json: String): UiScenario {
            val root = try {
                Json.parseToJsonElement(json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("the scenario is not valid JSON: ${e.message}", e)
            }
            val obj = root as? JsonObject ?: throw IllegalArgumentException("a scenario is a JSON object with title and steps")
            val unknown = obj.keys - FIELDS
            require(unknown.isEmpty()) { "unknown scenario field(s) ${unknown.joinToString()}; known fields: ${FIELDS.sorted().joinToString()}" }
            val version = (obj["scenario"] as? JsonPrimitive)?.intOrNull
                ?: throw IllegalArgumentException("a scenario starts with \"scenario\": $FORMAT_VERSION, its format version")
            require(version == FORMAT_VERSION) { "scenario format $version is not known; this IDE reads format $FORMAT_VERSION" }
            fun text(key: String): String? = obj[key]?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("$key must be a string")
            }
            fun steps(key: String): List<UiStep> {
                val array = obj[key] ?: return emptyList()
                require(array is JsonArray) { "$key must be an array of steps" }
                return try {
                    UiSteps.parse(array)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("$key: ${e.message}", e)
                }
            }
            val steps = steps("steps")
            require(steps.isNotEmpty()) { "a scenario needs steps" }
            return UiScenario(
                title = text("title")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("a scenario needs a title"),
                issue = text("issue"),
                description = text("description"),
                ide = text("ide"),
                project = text("project"),
                steps = steps,
                cleanup = steps("cleanup"),
            )
        }
    }
}

/**
 * The report of one step that a JetBrains Client ran on the backend as a steroid_ui call of that step alone. The
 * backend's response names it `step 1 <action> <target>`, as [label] does, then reports it on that line and the
 * lines after it, and ends with its own verdict and recording lines, which the client leaves out.
 */
object UiForwardedStep {
    data class Report(val passed: Boolean, val text: String)

    private val VERDICTS = UiVerdict.Kind.entries.map { it.name.replace('_', ' ') }

    fun label(step: UiStep): String = "step 1 ${step.action.wire}${step.target?.let { " $it" }.orEmpty()}"

    /** [text] is the backend's response, [isError] its error flag, used only when the step's line is missing. */
    fun parse(text: String, label: String, isError: Boolean): Report {
        val lines = text.lines()
        val failedLine = "FAILED $label failed: "
        val start = lines.indexOfFirst { it.startsWith("$label: ") || it.startsWith(failedLine) }
        if (start < 0) return Report(!isError, lines.filterNot { it.startsWith("execution_id:") }.joinToString("\n").trim())
        val more = lines.drop(start + 1).takeWhile { it.isNotBlank() && !it.startsWith("recorded:") && VERDICTS.none(it::startsWith) }
        val first = lines[start].removePrefix(failedLine).removePrefix("$label: ")
        return Report(!lines[start].startsWith(failedLine), (listOf(first) + more).joinToString("\n"))
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
    enum class Kind { PASSED, FAILED, BROKEN, INCOMPLETE, REPRODUCED, NOT_REPRODUCED }

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
            else -> Verdict(Kind.PASSED, "PASSED: all ${outcomes.size} step(s)$soft")
        }
    }
}
