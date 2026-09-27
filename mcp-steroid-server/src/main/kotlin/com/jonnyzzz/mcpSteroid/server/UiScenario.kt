/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A reproduction scenario: a JSON file of steroid_ui steps with what they are for, which steroid_ui replays with
 * `scenario`. The format is described for agents in `mcp-steroid://ide/ui-scenarios`; a field added here goes there too.
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

/** How one step of a run ended. [index] is the step's 1-based number in the list it came from. */
data class UiStepOutcome(val index: Int, val step: UiStep, val passed: Boolean, val message: String)

/**
 * What a run of steps means for the bug it reproduces. A step with `bug` is a bug check: it states the correct
 * behavior, so it fails while the bug is present. Any other failure means the steps could not get to the check.
 */
object UiVerdict {
    enum class Kind { REPRODUCED, NOT_REPRODUCED, BROKEN, INCOMPLETE, PASSED, FAILED }

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
        val checked = outcomes.filter { it.step.bug != null && it.passed }
        val soft = if (softFailures.isEmpty()) "" else "; ${softFailures.size} soft check(s) failed: steps ${softFailures.joinToString { it.index.toString() }}"
        return when {
            failure != null && checked.size < bugSteps -> Verdict(
                Kind.BROKEN,
                "BROKEN at step ${failure.index}: the steps did not reach the bug check" +
                    (failure.step.intent?.let { ". Repair the step so that it does what it is for: $it" } ?: ". Repair the step") + soft,
            )
            failure != null && bugSteps > 0 -> Verdict(
                Kind.NOT_REPRODUCED,
                "NOT REPRODUCED: the bug check(s) passed, then step ${failure.index} failed$soft",
            )
            failure != null -> Verdict(Kind.FAILED, "FAILED at step ${failure.index}$soft")
            checked.size < bugSteps -> Verdict(
                Kind.INCOMPLETE,
                "INCOMPLETE: the run stopped before ${bugSteps - checked.size} bug check(s)$soft",
            )
            bugSteps > 0 -> Verdict(Kind.NOT_REPRODUCED, "NOT REPRODUCED: every bug check passed$soft")
            else -> Verdict(Kind.PASSED, "PASSED: all ${outcomes.size} step(s)$soft")
        }
    }
}
