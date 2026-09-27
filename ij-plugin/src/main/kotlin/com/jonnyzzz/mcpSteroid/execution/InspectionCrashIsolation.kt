/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.codeInspection.ProblemDescriptor

/**
 * One inspection tool that crashed during a [McpScriptContext.runInspectionsDirectly] sweep
 * (GitHub issue #93). The crash was isolated: findings from all other tools are preserved.
 *
 * @property toolId the inspection short name (same key space as the result map), or
 *   [InspectionRunResult.SWEEP_FAILURE_ID] when the failure was file-level rather than tool-level
 *   (GitHub issue #69): the file has no PSI, or the run did not finish in time.
 * @property error the exception class name and message, e.g.
 *   "java.lang.IllegalStateException: Cannot compute containing PSI ..."
 */
data class FailedInspection(
    val toolId: String,
    val error: String,
)

/**
 * Result of [McpScriptContext.runInspectionsDirectly].
 *
 * ADDITIVE shape (GitHub issue #69): this class IS the `Map<inspectionShortName, List<ProblemDescriptor>>`
 * the method has always returned — every existing call site (`result.values`, `result.forEach`,
 * `result["ToolName"]`, `result.isEmpty()`) keeps compiling and behaving identically. On top of the
 * map it carries [failedTools]: the tools whose execution crashed and was isolated (issue #93).
 *
 * A tool listed in [failedTools] may still contribute partial findings to the map — problems it
 * registered before crashing are real and are kept.
 */
class InspectionRunResult(
    private val problems: Map<String, List<ProblemDescriptor>>,
    /** Tools that crashed during the sweep; empty when every tool completed normally. */
    val failedTools: List<FailedInspection>,
) : Map<String, List<ProblemDescriptor>> by problems {

    companion object {
        /** [FailedInspection.toolId] used when the whole-file sweep failed, not one specific tool. */
        const val SWEEP_FAILURE_ID: String = "<inspection-sweep>"
    }

    override fun equals(other: Any?): Boolean = problems == other
    override fun hashCode(): Int = problems.hashCode()
    override fun toString(): String =
        if (failedTools.isEmpty()) problems.toString()
        else "$problems (failedTools=$failedTools)"
}
