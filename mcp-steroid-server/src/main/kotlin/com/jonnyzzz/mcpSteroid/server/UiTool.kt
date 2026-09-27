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
enum class UiSnapshotMode(val wire: String) { TREE("tree"), FULL("full"), DIFF("diff"), NONE("none") }

/**
 * The steroid_ui MCP tool: a snapshot of the IDE's UI with refs, and steps that act on it.
 */
class UiToolSpec(val handler: () -> UiToolHandler) : McpToolBase() {
    override val name = "steroid_ui"

    override val description = """
        Read and drive the IDE's UI by what it shows: dialogs, popups, tool windows, Settings pages.

        With no steps it returns a snapshot of the project's showing windows, topmost first: popups and
        dialogs, separate windows such as Settings, then the project frame. Each line is one control: its
        class, accessible name in quotes, label="..." for the caption before an unnamed field, a ref such
        as [ref=e12], states such as [disabled] or [checked], value="..." for text fields and combo boxes,
        text=... for text it paints (tabs, editor text), tip="..." for its tooltip, and action=<id> for the
        IDE action behind a toolbar button or menu item, which a run step takes. Under a list, tree,
        table or tabbed pane come its rows or tabs in view, one per line: #index, the row text indented by
        tree depth, and [expanded], [collapsed] or [selected]. Row #9 under [ref=e91] is the row ref e91#9,
        which the row steps below take as their "ref". A ref stays valid while its control is showing.

        Pass window_id (from steroid_list_windows) to snapshot one window. To see the controls,
        steroid_take_screenshot with marks=true labels each one on the image with the ref used here, and
        each row and tab in view with its row ref, so what is spotted in the picture is targeted by ref,
        never by its pixels. snapshot=full adds each control's screen bounds, for steroid_input on what has
        no ref.

        Steps (a JSON array in `steps`) act on controls in order and report what each one caused: where the
        press landed, whether a button's action ran, IDE actions, windows opened or closed, the new focus. The
        response then shows what changed: a closed window as one line, an opened window whole, and the
        changed lines of the others. The first failing step stops the run and shows the topmost window, with
        the nearest matching controls.

        A target is "ref":"e12", or any of "name" (exact accessible name, or an unnamed field's label),
        "text" (part of the text a control shows or of its label), "class" (class or superclass simple name,
        such as JTextComponent), "xpath" (over the remote-driver model), plus "nth" (0-based) when several
        controls match. Each step waits up to
        "timeout_ms" (default 5000) for its target to show and be enabled. Only the topmost window with a
        match counts, and while a modal dialog shows, only that dialog and its popups are searched.

        - {"action":"click", target, "button":"left|right|middle", "count":1|2, "modifiers":"ctrl+shift"}:
          with "row", "index" or a row ref, presses that row or tab, such as a double click to open a row; a
          tabbed pane matched by name or text presses the tab of that title
        - {"action":"hover", target, optional "row" or "index"}
        - {"action":"type", "text":"...", optional target}: types into the target, or the control that has
          the focus in the topmost window
        - {"action":"fill", target, "text":"..."}: replaces a text field's text
          (in type and fill, "text" is the text to enter, so target the field by ref, name or class)
        - {"action":"press", "keys":"ENTER" or "ctrl+shift+A", optional target}: keymap shortcuts run
        - {"action":"check"|"uncheck", target}: clicks a checkbox only when its state differs
        - {"action":"select", target, "row":"text" or "index":N, or a row ref}: selects a list, tree or table
          row, a tab, or a combo item, without clicking it, so a list that acts on a click does not act. "row"
          is the row's text, else part of it, and "A > B > C" is a tree path, whose collapsed parents it
          expands; several matching rows are an error that lists them by index. An open combo box popup's
          rows are the combo box's items
        - {"action":"scroll", target, optional "row" or "index"}: scrolls the control or row into view, for a
          screenshot; or {"action":"scroll", target, "pages":N}: scrolls the scroll pane around the target by N
          pages, up when negative. Reports which part of the content shows. Other steps scroll their target
          into view by themselves
        - {"action":"close", optional target}: cancels the dialog or popup, or closes a separate window such
          as Settings (the topmost one without a target)
        - {"action":"wait", "for":"visible|hidden|enabled", target} or {"for":"window","title":"..."} or {"for":"idle"}
        - {"action":"snapshot", optional target}: adds a snapshot of the target's subtree or of all windows
        - {"action":"inspect", target, optional "row" or "index"}: where the control comes from, as the IDE's
          UI Inspector finds it: its class and plugin, the action behind it, its tool window, dialog class,
          model and renderer, and "created:" with the code that built it. For a list, tree or table, the facts
          of the named or selected row: its value and user object classes, the action behind a popup item,
          the intention or quick fix behind an Alt+Enter item, a Settings tree row's configurable class and
          ID. The first inspect starts recording where
          controls are created, until the IDE restarts; a window opened after it names its creator. Use it to
          find the class, plugin or code behind a piece of UI
        - {"action":"goto", "file":"src/A.kt", and one of "line":N (with "column":N), "symbol":"name" or
          "text":"exact snippet", plus "nth" for a later occurrence}: opens the file in the editor, focuses it,
          and puts the caret there, or selects the snippet. The file is absolute or relative to the project
        - {"action":"run", "id":"RenameElement"}: runs an IDE action by id where the focus is, after a goto in the
          editor. Reports a disabled action, an unknown id with similar ids, and an in-place template to type into
        - {"action":"expect", subject, check, optional "not":true, "soft":true, "bug":"..."}: checks what the IDE
          shows, retrying until it holds or "timeout_ms" passes; with "not", until it does not. Subjects and checks:
          a target with "is" (visible, hidden, enabled, disabled, checked, unchecked, focused, editable), "value",
          "contains", "matches" (a regex), "count", or "row" with "is" selected, expanded or collapsed;
          "title" (a window) with "is" visible or hidden; "file" with "value", "contains" or "matches", optionally
          on "line", or "caret":"line:column"; "notification":"text" and "error":"text" ("" for any), shown or
          logged since the call started. "soft" reports a failure and goes on; "bug" marks the check whose failure
          means the reported bug is present
        - {"action":"settings", "page":"Code Folding"}: opens Settings at a page by id, path ("Editor > General")
          or name, or switches the open Settings window to it
        - {"action":"toolwindow", "id":"Project", optional "tab":"...", or "hide":true}
        - {"action":"get"|"set", one of "registry":"key", "advanced":"id", "option":"name", "inspection":"ShortName",
          "component":"StateName" with "field", and "value" for set}: reads or changes a setting without a dialog.
          "option" is an on/off option as Search Everywhere lists it (get with part of the name lists matches);
          "inspection" takes on, off or a severity; "component" is a persistent settings component by its state
          name, get alone shows its saved XML. A set reports the value before and after
        - {"action":"write", "file":"src/A.kt", "text":"..."}: creates or replaces a file of the project
        - {"action":"perf", "command":"%openFile src/A.kt"}: runs Performance Testing playback commands, one per line
        - {"action":"code", "code":"...", optional "modal"}: runs a Kotlin body as steroid_execute_code does
        - {"action":"screenshot", optional target, "save":"name"}: saves a picture of the target's window, or of
          the topmost one, to the execution folder, for a visual review
        Any step takes "intent": what it is for, which its report echoes and a repair of the step follows. In
        Split Mode any step takes "side":"backend" to run on the Remote Development backend from a JetBrains
        Client call; write, code, goto, file expects and inspection settings go there by default.

        Example: [{"action":"select","name":"Settings categories","row":"Editor"},
                  {"action":"check","name":"Show line numbers"},{"action":"click","name":"OK"}]
        Refactoring example: [{"action":"goto","file":"src/Util.kt","symbol":"parse"},
                  {"action":"run","id":"ChangeSignature"}], then fill and click in the dialog it opens.
        goto and run work the way a user does: they open files, move the caret and show dialogs, which a
        reproduction needs. To only change code, and leave the user's windows alone, use steroid_refactor.

        Every call with steps records them to the task's recording file, named in the response, with refs
        replaced by names so that they replay in another session. A scenario file of such steps is any
        repeatable IDE procedure: a bug reproduction, a feature check, a visual review, a setup. `scenario`
        replays it and ends with a verdict: PASSED, FAILED (a check did not hold), BROKEN (a step could not
        be done), or REPRODUCED and NOT REPRODUCED for a bug check. Read mcp-steroid://ide/ui-scenarios before
        recording or replaying one.

        A click that opens a modal dialog returns while the dialog is up, and the report names it. A step
        that runs an action or presses a button named with an ellipsis ("Settings…") waits up to 10 s for
        its window; a window that opens later still is reported by the next step as "meanwhile opened".
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

    val scenario = InputSchemaElement.param("scenario")
        .description(
            "Path of a scenario file to replay, absolute or relative to the project: a JSON object with title and " +
                "steps, described in mcp-steroid://ide/ui-scenarios. Replaces steps."
        )
        .cliSynopsis("scenario file to replay")
        .string()
        .registerToSchema()

    val fromStep = InputSchemaElement.param("from_step")
        .description("The first step to run, 1-based; the steps before it are skipped.")
        .cliSynopsis("first step to run (1-based)")
        .int()
        .registerToSchema()

    val toStep = InputSchemaElement.param("to_step")
        .description("The last step to run, 1-based. A scenario's cleanup runs only when its last step does.")
        .cliSynopsis("last step to run (1-based)")
        .int()
        .registerToSchema()

    val snapshot = InputSchemaElement.param("snapshot")
        .description(
            "The snapshot in the response: 'tree' (the controls), 'full' (the controls with their screen " +
                "bounds), 'diff' (what the steps changed) or 'none'. Default: 'tree' without steps, 'diff' with steps."
        )
        .cliSynopsis("tree | full | diff | none")
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
                scenario = context[scenario],
                fromStep = context[fromStep],
                toStep = context[toStep],
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
    val scenario: String? = null,
    val fromStep: Int? = null,
    val toStep: Int? = null,
    val snapshot: UiSnapshotMode? = null,
    val maxNodes: Int = UiToolSpec.DEFAULT_MAX_NODES,
    val trace: Boolean = false,
    val side: String? = null,
    @Transient val executionBackend: ExecutionBackendProvenance? = null,
)

interface UiToolHandler {
    suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult
}
