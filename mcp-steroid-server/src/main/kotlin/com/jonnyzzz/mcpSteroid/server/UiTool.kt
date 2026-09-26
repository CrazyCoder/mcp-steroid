/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.boolean
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.enumString
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.int
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.mcp.withDefaultValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** What a `steroid_ui` response shows of the UI after its steps. */
enum class UiSnapshotMode(val wire: String) { FULL("full"), DIFF("diff"), NONE("none") }

/**
 * The steroid_ui MCP tool: a snapshot of the IDE's UI with refs, and steps that act on it.
 */
class UiToolSpec(val handler: () -> UiToolHandler) : McpToolBase() {
    override val name = "steroid_ui"

    override val description = """
        Read and drive the IDE's UI by what it shows: dialogs, popups, tool windows, Settings pages.

        With no steps it returns a snapshot of the project's showing windows, topmost first: popups and
        dialogs, then the project frame. Each line is one control: its class, accessible name in quotes,
        a ref such as [ref=e12], states such as [disabled] or [checked], value="..." for text fields and
        combo boxes, text=... for text it paints (tree and list rows, tabs, editor text), and tip="..." for
        its tooltip. A ref stays valid while its control is showing.

        Pass window_id (from steroid_list_windows) to snapshot one window, and snapshot=full to add each
        control's screen bounds, which steroid_input accepts as click:Left@screen:<x>,<y>.

        It compiles no code, so it answers in well under a second. Use it instead of a screenshot to find
        controls and read their state.
    """.trimIndent()
    override val cliSynopsis = "snapshot and drive IDE UI by what it shows"

    val projectName = CommonToolParams.projectName().registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = CommonToolParams.reason().registerToSchema()

    val windowId = CommonToolParams.windowId().registerToSchema()

    val steps = InputSchemaElement.param("steps")
        .description("JSON array of steps to run in order. Omit it for a snapshot only.")
        .cliSynopsis("JSON array of steps; omit it for a snapshot")
        .string()
        .registerToSchema()

    val snapshot = InputSchemaElement.param("snapshot")
        .description(
            "The snapshot in the response: 'full' (with screen bounds), 'diff' (what the steps changed) or " +
                "'none'. Default: 'full' without steps, 'diff' with steps."
        )
        .cliSynopsis("full | diff | none")
        .enumString(UiSnapshotMode.entries.associateBy { it.wire })
        .registerToSchema()

    val maxNodes = InputSchemaElement.param("max_nodes")
        .description("The most controls to list per call (default 400). The rest are counted.")
        .cliSynopsis("most controls to list (default 400)")
        .int()
        .withDefaultValue(DEFAULT_MAX_NODES)
        .registerToSchema()

    val trace = InputSchemaElement.param("trace")
        .description("Record a trace in the execution folder: a picture before and after each step, snapshots and events.")
        .cliSynopsis("record a trace in the execution folder")
        .boolean()
        .withDefaultValue(false)
        .registerToSchema()

    val side = InputSchemaElement.param("side")
        .description(
            "Split Mode only: where to read and act. 'frontend' (default) is the JetBrains Client, which shows " +
                "the windows; 'backend' reaches the Swing components of dialogs the backend draws. In a regular " +
                "IDE both are the same process."
        )
        .cliSynopsis("frontend | backend (Split Mode only)")
        .enumString(mapOf("frontend" to "frontend", "backend" to "backend"))
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult =
        handler().handleUi(
            context[projectName],
            UiParams(
                taskId = context[taskId],
                reason = context[reason],
                windowId = context[windowId],
                steps = context[steps],
                snapshot = context[snapshot],
                maxNodes = context[maxNodes],
                trace = context[trace],
                side = context[side],
                executionBackend = context.executionBackendProvenance(),
            ),
        )

    companion object {
        const val DEFAULT_MAX_NODES = 400
    }
}

@Serializable
data class UiParams(
    val taskId: String,
    val reason: String,
    val windowId: String? = null,
    val steps: String? = null,
    val snapshot: UiSnapshotMode? = null,
    val maxNodes: Int = UiToolSpec.DEFAULT_MAX_NODES,
    val trace: Boolean = false,
    val side: String? = null,
    @Transient val executionBackend: ExecutionBackendProvenance? = null,
)

interface UiToolHandler {
    suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult
}
