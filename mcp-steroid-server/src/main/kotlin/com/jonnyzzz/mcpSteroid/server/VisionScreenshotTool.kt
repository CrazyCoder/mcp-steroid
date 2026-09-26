package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.boolean
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.withDefaultValue
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Handler for the steroid_take_screenshot MCP tool.
 */
class VisionScreenshotToolSpec(val handler: () -> VisionScreenshotToolHandler) : McpToolBase() {
    override val name = "steroid_take_screenshot"

    override val description = """
        Capture a screenshot of the IDE and return an image payload.

        HEAVY ENDPOINT: This is intended for debugging and tricky configuration only.
        To read controls, their names and states, a steroid_ui snapshot is cheaper: it is text, with refs.

        To act on something seen in the picture, pass marks=true. Every interactive control is outlined and
        labelled with its steroid_ui ref, such as e12, and every row of a list, tree or table and every tab
        in view with its row ref, such as e12#3, at the row's right end. Then act on it with steroid_ui by
        that ref: {"action":"click","ref":"e12"}, {"action":"select","ref":"e12#3"}, or fill, check, inspect.
        Controls scrolled out of view are not marked: a steroid_ui scroll step brings them in. Prefer this to
        clicking at pixel coordinates: a ref needs no scale arithmetic, still finds the control after a resize
        or scroll, and the step reports what it caused. Use coordinates with steroid_input only for what has
        no ref, such as a web view (JCEF), a canvas, or a drag.

        Use steroid_list_windows when multiple IDE windows are open and pass window_id to target a specific window.

        The screenshot and component tree are saved under the execution folder:
        - screenshot.png
        - screenshot-tree.md (the steroid_ui snapshot of the window, with refs and screen bounds)
        - screenshot-meta.json

        Coordinates are in the IDE window's LOGICAL pixels. Feed them back only to steroid_input
        (sequence "click:Left@x,y"), which maps them onto the live component. Do NOT pass these
        coordinates to external tools like xdotool — those use the X display's PHYSICAL pixels and
        will be off by the display scale factor; for xdotool, source coordinates from scrot instead.
        On a HiDPI display the image and its OCR boxes are larger than the window. The output then
        has an "Image scale" line: divide positions read from the image by that scale first.

        After execution, call steroid_execute_feedback to log your feedback.
    """.trimIndent()
    override val cliSynopsis = "capture a screenshot of the IDE"

    /** Its result always carries the captured PNG, so `--out` can redirect it. */
    override val cliProducesImage = true

    val projectName = CommonToolParams.projectName().registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = CommonToolParams.reason().registerToSchema()

    val windowId = CommonToolParams.windowId()
        .registerToSchema()

    val marks = InputSchemaElement.param("marks")
        .description(
            "Draw each interactive control's ref (as steroid_ui lists it), and each row's and tab's row ref such as " +
                "e12#3, on the returned image, so what is seen in the picture can be addressed with steroid_ui by its ref. Default false."
        )
        .cliSynopsis("draw steroid_ui refs on the image")
        .boolean()
        .withDefaultValue(false)
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectName = context[projectName]
        val taskId = context[taskId]
        val reason = context[reason]
        val windowId = context[windowId]
        val marks = context[marks]

        return handler().screenshotWindow(
            projectName,
            ScreenshotParams(
                taskId = taskId,
                reason = reason,
                windowId = windowId,
                marks = marks,
                executionBackend = context.executionBackendProvenance(),
            ),
            context.mcpProgressReporter,
        )
    }
}

@Serializable
data class ScreenshotParams(
    val taskId: String,
    val reason: String,
    val windowId: String? = null,
    val marks: Boolean = false,
    @Transient val executionBackend: ExecutionBackendProvenance? = null,
)

interface VisionScreenshotToolHandler {
    suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter
    ): ToolCallResult
}
