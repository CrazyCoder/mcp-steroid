/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.boolean
import com.jonnyzzz.mcpSteroid.mcp.cliMissingHint
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.enumString
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.int
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.mcp.withDefaultValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** What `steroid_refactor` does to its target. */
enum class RefactorOp(val wire: String) {
    RENAME("rename"),
    SAFE_DELETE("safe_delete"),
    MOVE("move"),
    FIX("fix"),
    INTENTION("intention"),
    OPTIMIZE_IMPORTS("optimize_imports"),
    REFORMAT("reformat"),
    USAGES("usages"),
}

/**
 * The steroid_refactor MCP tool: refactorings the IDE runs without a dialog, with no script to compile.
 */
class RefactorToolSpec(val handler: () -> RefactorToolHandler) : McpToolBase() {
    override val name = "steroid_refactor"

    override val description = """
        Run an IDE refactoring on a symbol without writing a script: rename, safe_delete, move, fix (an
        inspection's quick fix), intention, optimize_imports, reformat, or usages (read-only). It resolves
        the target through the IDE's code model, so every reference is updated, in any language the IDE
        understands.

        The target is "file" (absolute or relative to the project) with one of "symbol" (a whole-word name,
        "nth" for a later occurrence) or "line" and "column" (1-based).

        By default it is a dry run that changes nothing: it returns the element and its usages as
        path:line: text (for rename, the lines it would change and the references it leaves alone), the
        problems and their fixes for fix, the intentions available for intention, or the lines
        optimize_imports and reformat would add and remove.
        Pass "apply": true to change the code; the response lists the changed files and how Edit > Undo takes
        the change back: one step named "MCP Steroid: ..." (fix with all: one step per fix). A refactoring
        that finds conflicts, such as a rename to a name already in use, returns them and changes nothing.

        - rename: "new_name"
        - safe_delete: fails with the usages that block it
        - move: moves "file" into the directory "to", updating references and, where the language has them,
          package statements
        - fix: "inspection" is the inspection's short name (such as SimplifiableCallChain); "all": true
          fixes every problem it reports in the file, else the one on the target's line (the first, without a
          target). A dry run without "inspection" lists what every enabled inspection reports in the file,
          with short names; compiler and annotator errors are not inspections and are not listed
        - intention: "name" is the intention's text, such as "Convert to expression body"
        - optimize_imports, reformat: act on "file"

        While the IDE runs background tasks, as after a start or a project sync, references and problems can be
        incomplete: it waits up to 30 s for them, and the response names any that still run.

        It stays out of the way of a person working in the IDE: no dialog, no editor tab, no caret or focus
        change, and it saves only the files it changed, so their unsaved edits elsewhere stay unsaved. Use
        it to change code.

        To do what a user does instead, as a reproduction needs (the refactoring's dialog, its preview, an
        in-place rename, a customer's exact steps), use steroid_ui: a goto step to the symbol, then a run
        step with the action id. That is also the way for refactorings this tool does not cover, such as
        Change Signature, Extract and Inline.
    """.trimIndent()
    override val cliSynopsis = "rename, delete, move, fix or reformat code through the IDE"

    val projectName = CommonToolParams.projectName().registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = CommonToolParams.reason().registerToSchema()

    val op = InputSchemaElement.param("op")
        .description("The refactoring: " + RefactorOp.entries.joinToString { it.wire } + ".")
        .cliSynopsis("rename | safe_delete | move | fix | intention | usages | more")
        .cliMissingHint("missing --op: one of " + RefactorOp.entries.joinToString { it.wire } + ".")
        .enumString(RefactorOp.entries.associateBy { it.wire })
        .required()
        .registerToSchema()

    val file = stringParam("file", "The target's file, absolute or relative to the project.")
    val line = intParam("line", "1-based line of the target, with column.")
    val column = intParam("column", "1-based column of the target, with line.")
    val symbol = stringParam("symbol", "The target's name in the file, matched as a whole word.")
    val nth = intParam("nth", "Which occurrence of symbol, from 0.")
    val newName = stringParam("new_name", "rename: the new name.")
    val to = stringParam("to", "move: the target directory, absolute or relative to the project.")
    val inspection = stringParam("inspection", "fix: the inspection's short name; omit it in a dry run to list problems.")
    val intentionName = stringParam("name", "intention: the intention's text.")

    val all = InputSchemaElement.param("all")
        .description("fix: fix every problem the inspection reports in the file.")
        .cliSynopsis("fix every problem in the file")
        .boolean()
        .withDefaultValue(false)
        .registerToSchema()

    val apply = InputSchemaElement.param("apply")
        .description("Change the code. Without it the call is a dry run that changes nothing.")
        .cliSynopsis("change the code (default: dry run)")
        .boolean()
        .withDefaultValue(false)
        .registerToSchema()

    private fun stringParam(key: String, text: String) =
        InputSchemaElement.param(key).description(text).cliSynopsis(text.removeSuffix(".")).string().registerToSchema()

    private fun intParam(key: String, text: String) =
        InputSchemaElement.param(key).description(text).cliSynopsis(text.removeSuffix(".")).int().registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult =
        handler().handleRefactor(
            context[projectName],
            RefactorParams(
                taskId = context[taskId],
                reason = context[reason],
                op = context[op],
                file = context[file],
                line = context[line],
                column = context[column],
                symbol = context[symbol],
                nth = context[nth] ?: 0,
                newName = context[newName],
                to = context[to],
                inspection = context[inspection],
                name = context[intentionName],
                all = context[all],
                apply = context[apply],
                executionBackend = context.executionBackendProvenance(),
            ),
        )
}

@Serializable
data class RefactorParams(
    val taskId: String,
    val reason: String,
    val op: RefactorOp,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val symbol: String? = null,
    val nth: Int = 0,
    val newName: String? = null,
    val to: String? = null,
    val inspection: String? = null,
    val name: String? = null,
    val all: Boolean = false,
    val apply: Boolean = false,
    @Transient val executionBackend: ExecutionBackendProvenance? = null,
)

interface RefactorToolHandler {
    suspend fun handleRefactor(projectName: String, params: RefactorParams): ToolCallResult
}
