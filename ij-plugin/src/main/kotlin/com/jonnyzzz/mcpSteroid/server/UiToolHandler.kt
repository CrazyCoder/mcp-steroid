/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.server.split.SPLIT_FRONTEND_BRIDGE_EP
import com.jonnyzzz.mcpSteroid.server.split.SplitFrontendBridge
import com.jonnyzzz.mcpSteroid.server.split.SplitRole
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import com.jonnyzzz.mcpSteroid.ui.UiEditors
import com.jonnyzzz.mcpSteroid.ui.UiStepFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.ui.UiSession
import com.jonnyzzz.mcpSteroid.ui.UiSessionResult
import com.jonnyzzz.mcpSteroid.ui.UiTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class UiToolHandlerIJ : UiToolHandler {
    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }

    override suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult {
        params.scenario?.let { spec -> replayBatch(projectName, params, spec)?.let { return it } }
        val project = service<ProjectScopedToolHandler>().resolveProject(projectName)
        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_ui",
            arguments = json.encodeToJsonElement(params).jsonObject,
            taskId = params.taskId,
            executionBackend = params.executionBackend,
        )
        project.executionStorage.writeCodeExecutionData(executionId, "reason.txt", params.reason)
        val builder = ToolCallResult.builder()
        val role = currentSplitRole()
        val bridge = if (role == SplitRole.FRONTEND) SPLIT_FRONTEND_BRIDGE_EP.extensionList.firstOrNull() else null
        runCatching { bridge?.refreshProjectKeys() }
        val scenario: UiScenario?
        val allSteps: List<UiStep>
        try {
            require(params.scenario == null || params.steps.isNullOrBlank()) { "pass steps or scenario, not both" }
            // A JetBrains Client's own project folder is a synthetic one under its config: the backend's is the project's.
            val base = bridge?.backendPathFor(project) ?: project.basePath
            scenario = params.scenario?.let { loadScenario(base, it) }
            allSteps = scenario?.steps ?: params.steps?.trim()?.takeIf { it.isNotEmpty() }?.let(UiSteps::parse).orEmpty()
            if (role == SplitRole.BACKEND) {
                val clientSteps = allSteps.withIndex().filter { it.value.side == "frontend" }.map { it.index + 1 }
                require(clientSteps.isEmpty()) {
                    "step(s) ${clientSteps.joinToString()} ask for side frontend, but this is the backend's endpoint: replay through the JetBrains Client's endpoint"
                }
            }
        } catch (e: IllegalArgumentException) {
            return builder.addTextContent("ERROR: ${e.message}").markAsError().build()
        }
        val from = params.fromStep ?: 1
        val to = params.toStep ?: allSteps.size
        if (allSteps.isNotEmpty() && (from < 1 || to > allSteps.size || from > to)) {
            return builder.addTextContent("ERROR: from_step and to_step must pick steps 1 to ${allSteps.size}, from_step first").markAsError().build()
        }
        val steps = if (allSteps.isEmpty()) allSteps else allSteps.subList(from - 1, to)
        val cleanup = scenario?.cleanup.orEmpty().takeIf { to == allSteps.size }.orEmpty()
        // Without steps there is nothing to diff against, so a diff asked for then is the snapshot itself. A scenario
        // reports its steps; the windows are shown only when a step fails, unless asked for.
        val mode = when {
            steps.isEmpty() && (params.snapshot == null || params.snapshot == UiSnapshotMode.DIFF) -> UiSnapshotMode.TREE
            scenario != null -> params.snapshot ?: UiSnapshotMode.NONE
            else -> params.snapshot ?: UiSnapshotMode.DIFF
        }
        val trace = if (params.trace) UiTrace(project.executionStorage.resolveExecutionDir(executionId).resolve("trace")) else null
        // A step a JetBrains Client sent here belongs to the Client's run, whose error and notification checks count
        // from the run's start. The age is relative, so the two machines' clocks need not agree.
        val runStartedMs = System.currentTimeMillis() - (params.runAgeMs ?: 0)
        val session = UiSession(project, params.windowId, params.maxNodes, trace, params.taskId,
            artifacts = project.executionStorage.resolveExecutionDir(executionId),
            forward = bridge?.let { b -> { step -> forwardStep(b, project, params.taskId, step, runStartedMs) } },
            startedMs = runStartedMs)
        // The steps' own waits bound the call, plus an allowance for delivery and settling per step.
        val budgetMs = (steps + cleanup).sumOf { it.timeoutMs + STEP_ALLOWANCE_MS } + BASE_ALLOWANCE_MS
        return try {
            val started = TimeSource.Monotonic.markNow()
            val (result, cleanupResult) = withTimeout(budgetMs.milliseconds) {
                val main = session.run(steps, mode, firstIndex = from)
                // Cleanup puts the IDE back whether the steps passed or failed; a partial run leaves its state for the next.
                // Every cleanup step runs: one that finds nothing to undo, such as a close with no dialog open, stops none.
                val after = if (cleanup.isEmpty()) null
                else session.run(cleanup.map { it.copy(soft = true) }, UiSnapshotMode.NONE, labelPrefix = "cleanup step")
                main to after
            }
            val editorNotice = bridge?.takeIf { steps.any { it.action in EDITOR_ACTIONS } }
                ?.let { editorMismatchNotice(it, project, params.taskId, runStartedMs) }
            val planned = allSteps.drop(from - 1)
            val judged = scenario != null || planned.any { it.bug != null }
            val verdict = if (judged) UiVerdict.of(planned, result.outcomes) else null
            val recording = if (scenario == null) record(project, executionId, params.taskId, result) else null
            val text = buildString {
                append("execution_id: ").append(executionId.executionId)
                append(" (").append(started.elapsedNow().inWholeMilliseconds).append(" ms)")
                scenario?.let { append('\n').append(header(it)) }
                result.reports.forEach { append('\n').append(it.line) }
                result.failure?.let { append('\n').append("FAILED ").append(it) }
                // Cleanup steps run soft, so a failed one is among the reports and never stops the others.
                cleanupResult?.reports?.forEach { append('\n').append(it.line) }
                verdict?.let { append('\n').append(it.line) }
                recording?.let { append('\n').append(it) }
                trace?.let { append('\n').append("trace: ").append(it.folder.resolve("trace.md")) }
                if (result.snapshot.isNotEmpty()) {
                    val title = if (result.failure == null && mode == UiSnapshotMode.DIFF) "changes" else "snapshot"
                    append("\n\n").append(title).append(":\n").append(result.snapshot)
                }
            }
            project.executionStorage.writeCodeExecutionData(executionId, "ui.txt", text)
            editorNotice?.let { builder.addTextContent(it) }
            builder.addTextContent(text)
            val failed = when (verdict?.kind) {
                null -> result.failure != null
                UiVerdict.Kind.BROKEN, UiVerdict.Kind.FAILED -> true
                else -> false
            }
            if (failed) builder.markAsError()
            builder.build()
        } catch (e: TimeoutCancellationException) {
            val message = "steroid_ui did not finish within $budgetMs ms"
            project.executionStorage.writeCodeErrorEvent(executionId, message)
            builder.addTextContent("ERROR: $message").markAsError().build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "steroid_ui failed: ${e.message}"
            project.executionStorage.writeCodeErrorEvent(executionId, message)
            builder.addTextContent("ERROR: $message").markAsError().build()
        }
    }

    /**
     * Runs [step] on the Remote Development backend through the Split Mode bridge, as a steroid_ui call of one step,
     * and returns what the backend reported for it. The backend's own verdict and recording lines are left out: the
     * scenario's verdict and recording are this call's.
     */
    private suspend fun forwardStep(bridge: SplitFrontendBridge, project: Project, taskId: String, step: UiStep, runStartedMs: Long): String {
        val key = bridge.backendKeyFor(project) ?: throw UiStepFailure("the backend does not list this project, so the step cannot run there")
        // Without its intent the backend's label is exactly this, and its report line is what follows it. Without bug
        // and soft the backend judges nothing: a failed bug check there must read as a failed step here, where the
        // verdict is made.
        val source = JsonObject((step.source ?: throw UiStepFailure("the step has no source to send")) - setOf("intent", "bug", "soft"))
        val args = buildJsonObject {
            put("project_name", key)
            put("task_id", taskId)
            put("reason", "a step the JetBrains Client sent to the backend" + (step.intent?.let { ": $it" } ?: ""))
            put("steps", JsonArray(listOf(source)).toString())
            put("snapshot", "none")
            put("side", "backend")
            put("run_age_ms", (System.currentTimeMillis() - runStartedMs).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }
        val result = bridge.forward(ToolCallParams(name = "steroid_ui", arguments = args), object : McpProgressReporter {
            override fun report(message: String) = Unit
        })
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        val report = UiForwardedStep.parse(text, UiForwardedStep.label(step), result.isError)
        if (!report.passed) throw UiStepFailure("on the backend: ${report.text}")
        return "on the backend: ${report.text}"
    }

    /**
     * In a JetBrains Client, where its editors and the backend's record of them disagree after steps that can open or
     * close editors, as a notice, each disagreement told once per task. A file the backend keeps an extra editor of
     * opens neither from the Project view nor from a navigation, and nothing else says why. A failure to read the
     * backend's record is no notice: the run's own report stands.
     */
    private suspend fun editorMismatchNotice(bridge: SplitFrontendBridge, project: Project, taskId: String, runStartedMs: Long): String? {
        val mismatches = try {
            val local = UiEditors(project).local()
            val backend = forwardStep(bridge, project, taskId, GET_EDITORS, runStartedMs).removePrefix("on the backend: ")
            UiEditorState.mismatches(local, UiEditorState.parse(backend)).toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val told = toldMismatches.put(taskId, mismatches).orEmpty()
        val fresh = mismatches - told
        if (fresh.isEmpty()) return null
        // Only a file the backend records more of than the Client shows is stuck; a click on its tab frees it.
        val stuck = fresh.any { UiEditorState.isStuck(it) }
        return "EDITOR STATE: the JetBrains Client and the backend disagree about the open editors:\n" +
            fresh.joinToString("\n") { "- $it" } +
            (if (stuck) "\nA file the Client shows no editor of opens again once its tab is clicked." else "") +
            "\n{\"action\":\"get\",\"editors\":true} lists both sides."
    }

    /**
     * Replays each scenario file of a folder or a list as its own call, and answers with [UiScenarioBatch.render]'s
     * summary and the reports. Null when [spec] names a single file, which the plain scenario path replays.
     */
    private suspend fun replayBatch(projectName: String, params: UiParams, spec: String): ToolCallResult? {
        val project = service<ProjectScopedToolHandler>().resolveProject(projectName)
        val bridge = if (currentSplitRole() == SplitRole.FRONTEND) SPLIT_FRONTEND_BRIDGE_EP.extensionList.firstOrNull() else null
        val base = (bridge?.backendPathFor(project) ?: project.basePath)?.let { Path.of(it) }
        val error = { message: String -> ToolCallResult.builder().addTextContent("ERROR: $message").markAsError().build() }
        val files = try {
            withContext(Dispatchers.IO) { UiScenarioBatch.expand(spec, base) } ?: return null
        } catch (e: IllegalArgumentException) {
            return error(e.message ?: "cannot read the scenario list")
        }
        if (!params.steps.isNullOrBlank()) return error("pass steps or scenario, not both")
        if (params.fromStep != null || params.toStep != null) return error("from_step and to_step pick the steps of one scenario, not of several files")
        val reports = files.map { file ->
            val result = handleUi(projectName, params.copy(scenario = file.toString()))
            file to result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        }
        val builder = ToolCallResult.builder().addTextContent(UiScenarioBatch.render(reports, base))
        if (reports.any { (_, report) -> UiScenarioBatch.isBad(UiScenarioBatch.verdictOf(report)) }) builder.markAsError()
        return builder.build()
    }

    private suspend fun loadScenario(base: String?, path: String): UiScenario {
        val file = Path.of(path).let { p -> if (p.isAbsolute) p else base?.let { Path.of(it).resolve(p) } ?: p }
        val text = withContext(Dispatchers.IO) {
            require(Files.isRegularFile(file)) { "no scenario file at $file" }
            Files.readString(file)
        }
        return try {
            UiScenario.parse(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("scenario $file: ${e.message}", e)
        }
    }

    /** Names the scenario and warns when it was recorded on another IDE build, where steps may need repair. */
    private fun header(s: UiScenario): String {
        val here = ApplicationInfo.getInstance().build.asString()
        return buildString {
            append("scenario: ").append(s.title)
            s.issue?.let { append(" (").append(it).append(')') }
            append("; this IDE: ").append(here)
            if (s.ide != null && s.ide != here) {
                append(", recorded on ").append(s.ide).append(": a step that fails may need repair, by what its intent says")
            }
        }
    }

    /**
     * Appends the portable form of the steps that ran to the task's recording, one JSON step per line, and says where
     * it is. The steps of every call of the task add up to the steps of a scenario.
     */
    private suspend fun record(project: Project, executionId: com.jonnyzzz.mcpSteroid.storage.ExecutionId, taskId: String, result: UiSessionResult): String? {
        if (result.recorded.isEmpty()) return null
        val dir = project.executionStorage.resolveExecutionDir(executionId)
        val file = dir.parent.resolveSibling("ui-recordings").resolve(taskId.replace(UNSAFE, "_").take(120).ifEmpty { "task" } + ".jsonl")
        val total = withContext(Dispatchers.IO) {
            Files.writeString(dir.resolve("recorded.json"), pretty.encodeToString(JsonArray.serializer(), JsonArray(result.recorded)))
            Files.createDirectories(file.parent)
            Files.writeString(file, result.recorded.joinToString("") { json.encodeToString(JsonObject.serializer(), it) + "\n" },
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Files.readAllLines(file).count { it.isNotBlank() }
        }
        // A ref whose control closed before its step found it, as an expect for a closed window does, has no name to take.
        val refs = result.recorded.count { "ref" in it }
        return "recorded: ${result.recorded.size} step(s) to $file ($total in this task), with refs replaced by names; the lines are the steps of a scenario" +
            if (refs == 0) "" else ". $refs recorded step(s) still name a ref, which does not replay: give them a name, text or class before saving"
    }

    companion object {
        // Delivery, settling, and up to 10 s for the dialog of an action named with an ellipsis.
        private const val STEP_ALLOWANCE_MS = 15_000L
        private const val BASE_ALLOWANCE_MS = 30_000L
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")

        /** The steps that can open, close or switch editors, after which a JetBrains Client compares its editors with the backend's. */
        private val EDITOR_ACTIONS = setOf(
            UiAction.CLICK, UiAction.GOTO, UiAction.RUN, UiAction.PRESS, UiAction.CLOSE, UiAction.TYPE, UiAction.SELECT, UiAction.CODE,
        )
        private val GET_EDITORS = UiSteps.parse("""[{"action":"get","editors":true}]""").single()

        /** The editor disagreements each task was told, so a notice repeats none of them. */
        private val toldMismatches = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    }
}
