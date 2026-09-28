/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.mcpSteroid.freeze.IdeBuilds
import com.jonnyzzz.mcpSteroid.freeze.IdeEditorProblems
import com.jonnyzzz.mcpSteroid.freeze.IdeMemory
import com.jonnyzzz.mcpSteroid.freeze.IdeNotifications
import com.jonnyzzz.mcpSteroid.freeze.IdeRuns
import com.jonnyzzz.mcpSteroid.server.UiAction
import com.jonnyzzz.mcpSteroid.server.UiCrop
import com.jonnyzzz.mcpSteroid.server.UiHighlight
import com.jonnyzzz.mcpSteroid.server.UiEditorState
import com.jonnyzzz.mcpSteroid.server.UiForwardedStep
import com.jonnyzzz.mcpSteroid.server.UiRestore
import com.jonnyzzz.mcpSteroid.server.UiSnapshotMode
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiStepOutcome
import com.jonnyzzz.mcpSteroid.server.UiSteps
import com.jonnyzzz.mcpSteroid.server.UiTarget
import com.jonnyzzz.mcpSteroid.server.UiWaitCondition
import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil
import com.jonnyzzz.mcpSteroid.vision.findComponentByWindowId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.image.BufferedImage
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.MenuSelectionManager
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.tree.TreePath
import kotlin.time.TimeSource

/** A step that could not do what it asked for. The message says what the IDE showed instead. */
open class UiStepFailure(message: String) : RuntimeException(message)

/** A step whose target lies past the edge of a panel or of its window, where no pointer reaches it. */
class UiUnreachable(message: String, val component: Component) : UiStepFailure(message)

data class UiStepReport(val index: Int, val line: String)

data class UiSessionResult(
    val reports: List<UiStepReport>,
    val failure: String?,
    val snapshot: String,
    /** How each step that ran ended, in order, for the verdict of a scenario. */
    val outcomes: List<UiStepOutcome> = emptyList(),
    /** The steps that ran, rewritten to replay in another session: refs replaced by names, row indexes by row text. */
    val recorded: List<JsonObject> = emptyList(),
    /** The steps that put back what the session's recorded runs changed, the last change first. */
    val undo: List<JsonObject> = emptyList(),
    /** The project files the run changed, as a diff, or null when it changed none. */
    val codeChanges: String? = null,
)

/**
 * Runs steroid_ui steps in order against the project's windows, or against one window. Each step finds its target
 * inside the IDE, waiting up to its timeout, acts through [UiInput], lets the windows settle and reports what the
 * action did. The first failure stops the run, except a soft expect's, which is reported and passed over.
 */
class UiSession(
    private val project: Project,
    private val windowId: String?,
    private val maxNodes: Int,
    private val trace: UiTrace? = null,
    taskId: String = "",
    /** Where a screenshot step saves its picture: the call's execution folder. */
    private val artifacts: Path? = null,
    /**
     * In a JetBrains Client, runs one step on the Remote Development backend and returns its report. Null in a
     * regular IDE and on the backend, where every step runs here.
     */
    private val forward: (suspend (UiStep) -> UiForwardedStep.Report)? = null,
    /** When the run started: an expect on errors or notifications counts the ones since then. */
    private val startedMs: Long = System.currentTimeMillis(),
    /** The folder of the replayed scenario file, which a screenshot's relative `out` is relative to; null for steps. */
    private val scenarioDir: Path? = null,
) {
    private val registry = service<UiRefs>().registry
    private val input = UiInput { UiLayout.unreachable(it, project) }
    private val editorSteps = UiEditorSteps(project)
    private val ideSteps = UiIdeSteps(project, taskId)
    private val config = UiConfig(project)
    private val editors = UiEditors(project)
    private val menu = UiMenu()
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /** The step that runs, and what a replay of it names instead of its refs, row indexes, page names and option names. */
    private var current: UiStep? = null
    private var portableTarget: UiTarget? = null
    private var portableRow: String? = null
    private val portableFields = mutableMapOf<String, String>()

    /** The windows after the previous step, to report the ones that opened or closed between two steps. */
    private var windowsAfterLastStep: Set<Window>? = null

    /** Set by a click on a button whose text ends with an ellipsis, which by convention opens a dialog. */
    private var clickOpensWindow = false

    /** The dialog whose default button, such as OK or Refactor, a click pressed: it closes once its work is done. */
    private var closingDialog: Window? = null

    /**
     * What a step does about a window it opened, or a tool window it showed, that cuts controls: one of
     * [UiScenario.LAYOUT_MODES], or null for nothing, as in a call whose snapshot already shows the layout lines.
     */
    var layoutMode: String? = null

    /** The layout lines a step in `check` mode found, which count as a failed soft check. */
    private var layoutFailure: String? = null

    /** The restores of the changes recorded runs made, and those of the step that runs. */
    private val journal = UiRestore.Journal()
    private val stepUndo = mutableListOf<JsonObject>()
    private val undo: (List<JsonObject>) -> Unit = { stepUndo += it }

    /**
     * The project files the session's runs change, from its first run until [close]. A JetBrains Client holds no project
     * files: the backend tracks the steps it runs.
     */
    private var codeChanges: UiCodeChanges? = null
    private val codeChangesDisposable = Disposer.newDisposable("steroid_ui code changes")

    private fun codeChanges(): UiCodeChanges? {
        if (forward != null) return null
        return codeChanges ?: UiCodeChanges(project, codeChangesDisposable).also { codeChanges = it }
    }

    /** Starts the span of changes the next runs report and check: a scenario's steps, after its setup. */
    fun checkpoint() {
        codeChanges()?.checkpoint()
    }

    /** Stops tracking the project's files. */
    fun close() = Disposer.dispose(codeChangesDisposable)

    /**
     * Runs [steps], numbered from [firstIndex] in the reports, as a scenario run from a later step numbers them, and
     * named [labelPrefix], as a scenario's cleanup steps are. With [record], the restores of what the steps change go
     * to the session's journal; a cleanup's and a restore's do not.
     */
    suspend fun run(steps: List<UiStep>, mode: UiSnapshotMode, firstIndex: Int = 1, labelPrefix: String = "step", record: Boolean = true): UiSessionResult {
        val before = if (mode == UiSnapshotMode.DIFF) render(withBounds = false) else null
        val reports = mutableListOf<UiStepReport>()
        val outcomes = mutableListOf<UiStepOutcome>()
        val recorded = mutableListOf<JsonObject>()
        var failure: String? = null
        var failedStep: UiStep? = null
        val runStarted = TimeSource.Monotonic.markNow()
        val disposable = Disposer.newDisposable("steroid_ui session")
        val notifications = UiNotificationLog(project, disposable)
        val tracker = codeChanges()
        val expect = UiExpect(project, startedMs, notifications, ::matchForExpect, ::describe, ::layoutProblems) {
            tracker?.runChanges() ?: throw UiStepFailure(NO_CHANGES)
        }
        try {
            for ((i, step) in steps.withIndex()) {
                val index = firstIndex + i
                val label = "$labelPrefix $index ${step.action.wire}${step.target?.let { " $it" }.orEmpty()}" +
                    (step.intent?.let { " ($it)" }.orEmpty())
                val stepStarted = runStarted.elapsedNow().inWholeMilliseconds
                val pictureBefore = tracePicture(index, "before")
                val meanwhile = meanwhile()
                current = step
                portableTarget = null
                portableRow = null
                portableFields.clear()
                layoutFailure = null
                tracker?.stepStarted()
                suspend fun attempt(): String = when {
                    forward != null && runsOnBackend(step) -> onBackend(step).let { line ->
                        if (step.action == UiAction.GOTO) line + focusClientEditor(step.file!!) else line
                    }
                    step.action == UiAction.EXPECT -> expect.run(step)
                    else -> runStep(step)
                }
                val outcome = try {
                    Result.success(
                        try {
                            attempt()
                        } catch (e: UiUnreachable) {
                            // In auto mode a control past an edge gets room, as a person drags the edge, and one more try.
                            val fix = if (layoutMode == AUTO) withContext(edtAny) { UiLayout.fixFor(e.component, project) } else null
                            fix ?: throw e
                            "made room: ${applyFix(fix)}; then " + attempt()
                        }
                    )
                } catch (e: UiStepFailure) {
                    Result.failure(e)
                } catch (e: UiBarrierTimeout) {
                    Result.failure(e)
                } catch (e: IllegalArgumentException) {
                    Result.failure(e)
                } catch (e: IllegalStateException) {
                    Result.failure(e)
                } finally {
                    current = null
                    // A step that failed part way may have changed some of what it restores.
                    if (record) journal.add(UiRestore.onSide(stepUndo.toList(), step.side))
                    stepUndo.clear()
                }
                // The files the step changed, which an expect only reads; a replay puts each back as the session found it.
                val changed = if (step.action == UiAction.EXPECT || step.action == UiAction.GET) emptyList() else tracker?.stepChanges().orEmpty()
                if (record && changed.isNotEmpty()) journal.add(tracker!!.restores(changed))
                // A write step reports the file it wrote itself.
                val changedNote = if (changed.isEmpty() || step.action == UiAction.WRITE) "" else "; ${UiCodeChanges.counts(changed)}"
                val line = meanwhile + outcome.fold({ it }, { it.message ?: it.javaClass.simpleName }) + changedNote
                trace?.let { t ->
                    val pictureAfter = tracePicture(index, "after")
                    t.record(index, label, line, outcome.isFailure, render(withBounds = true), pictureBefore, pictureAfter,
                        stepStarted, runStarted.elapsedNow().inWholeMilliseconds - stepStarted)
                }
                outcomes += UiStepOutcome(index, step, outcome.isSuccess, line)
                if (outcome.isFailure && !step.soft) {
                    failure = "$label failed: $line"
                    failedStep = step
                    break
                }
                if (step.action !in NOT_RECORDED) step.source?.let { recorded += portable(step, it) }
                windowsAfterLastStep = UiSettle.showingWindows()
                reports += UiStepReport(index, if (outcome.isSuccess) "$label: $line" else "$label: SOFT FAILED: $line")
                layoutFailure?.let { cut ->
                    outcomes += UiStepOutcome(index, UiStep(UiAction.EXPECT, null, layout = true, soft = true, intent = "no control is cut"), false, cut)
                    reports += UiStepReport(index, "$labelPrefix $index layout check: SOFT FAILED: $cut")
                }
            }
        } finally {
            Disposer.dispose(disposable)
        }
        // A refactoring whose dialog closed writes its changes a moment later, after its step reported.
        tracker?.awaitQuiet()
        val runChanges = tracker?.runChanges().orEmpty()
        if (record && runChanges.isNotEmpty()) journal.add(tracker!!.restores(runChanges))
        val snapshot = when {
            failure != null && windowId != null && withContext(edtAny) { listedWindows().isEmpty() } -> ""
            // A failure outside the windows names what went wrong in the code, the action or the setting; the windows add nothing.
            failure != null && failedStep?.let(::showsNoWindow) == true -> ""
            failure != null -> render(withBounds = false, scopeOnly = true, topOnly = true)
            mode == UiSnapshotMode.TREE -> render(withBounds = false)
            mode == UiSnapshotMode.FULL -> render(withBounds = true)
            mode == UiSnapshotMode.NONE -> ""
            else -> UiSnapshotDiff.diff(before.orEmpty(), render(withBounds = false)).ifEmpty { "(the snapshot did not change)" }
        }
        return UiSessionResult(reports, failure, snapshot, outcomes, recorded, journal.steps(), runChanges.takeIf { it.isNotEmpty() }?.let { UiCodeChanges.render(it) })
    }

    /**
     * Runs [step] on the backend and returns its report, keeping the restores the backend reported for it. Throws
     * [UiStepFailure] when it failed there.
     */
    private suspend fun onBackend(step: UiStep): String {
        val report = forward!!.invoke(step)
        undo(report.undo)
        if (!report.passed) throw UiStepFailure(ON_BACKEND + report.text)
        return ON_BACKEND + report.text
    }

    /**
     * After a goto that ran on the backend, focuses the JetBrains Client's editor of [file], which the backend's
     * navigation opened, so that the next step acts where the caret is, as after a goto in a regular IDE. Without it
     * the focus stays where it was, and an action such as Reformat Code runs on the Project view instead. Fails when
     * the Client shows no such editor.
     */
    private suspend fun focusClientEditor(file: String): String {
        val name = file.substringAfterLast('/')
        var editor: Editor? = null
        withTimeoutOrNull(EDITOR_WAIT_MS) {
            while (editor == null) {
                editor = withContext(edtAny) {
                    FileEditorManager.getInstance(project).selectedTextEditor?.takeIf { it.virtualFile?.name == name && it.contentComponent.isShowing }
                }
                if (editor == null) delay(POLL_MS)
            }
        }
        // The backend moved its caret, but the user sees no editor: every step after this one would act elsewhere.
        val shown = editor ?: throw UiStepFailure(
            "the backend opened $file, but the JetBrains Client shows no editor of $name after $EDITOR_WAIT_MS ms; " +
                "a tab the Client restored when it connected can show its editor only once the tab is clicked: " +
                "{\"action\":\"click\",\"name\":\"$name\",\"class\":\"EditorTabLabel\"}"
        )
        withContext(edtAny) { IdeFocusManager.getInstance(project).requestFocus(shown.contentComponent, true) }
        return "; focus: the JetBrains Client's editor of $name"
    }

    /**
     * Whether a JetBrains Client sends [step] to the backend: its `side` when it names one, else the steps that need the
     * project itself, which only the backend holds: files, the editor at a file, editor banners, scripts, the
     * inspection profile, builds and run consoles. The code changes stay: the backend runs each step it is sent as a
     * call of its own, which knows none of the changes before it.
     */
    private fun runsOnBackend(step: UiStep): Boolean = when (step.side) {
        "backend" -> true
        "frontend" -> false
        else -> step.action in BACKEND_HOME ||
            step.action == UiAction.EXPECT && (step.file != null && step.diff == null || step.banner != null || step.console != null) ||
            (step.action == UiAction.GET || step.action == UiAction.SET) && step.inspection != null ||
            step.action == UiAction.GET && (step.file != null || step.builds || step.console != null || step.problems != null)
    }

    /**
     * The problems the editor highlights at [severity] and above in the open file [path], or in every open file for "",
     * once the shown files' analysis finished or [timeoutMs] passed. The editor analyzes only open files, so a closed
     * one fails with how to open it.
     */
    private suspend fun problemsReport(path: String, severity: String, timeoutMs: Long): String {
        val min = IdeEditorProblems.SEVERITIES[severity] ?: throw UiStepFailure("unknown severity $severity")
        if (path.isEmpty()) return IdeEditorProblems.renderList(IdeEditorProblems.readSettled(project, min, null, timeoutMs), severity, "the open files")
        val file = withContext(Dispatchers.IO) { CodeLocation.findFile(project, path) } ?: throw UiStepFailure("no file $path in the project")
        if (file !in IdeEditorProblems.openFiles(project)) throw UiStepFailure("$path is not open in an editor, and the editor analyzes only open files; open it with {\"action\":\"goto\",\"file\":\"$path\",\"line\":1}")
        return IdeEditorProblems.renderList(IdeEditorProblems.readSettled(project, min, file, timeoutMs), severity, path)
    }

    /**
     * The open editors of this side, and on a Remote Development backend its record of each JetBrains Client
     * session. In a Client, also the backend's record, which it gets by sending the step there, and the places where
     * the two disagree.
     */
    private suspend fun editorsReport(step: UiStep): String {
        val local = editors.local()
        val own = UiEditorState.render(listOf(local) + editors.sessions())
        if (forward == null) return own
        val backend = onBackend(step).removePrefix(ON_BACKEND)
        val mismatches = UiEditorState.mismatches(local, UiEditorState.parse(backend))
        return own + "\n" + ON_BACKEND.trimEnd() + "\n" + backend +
            mismatches.joinToString("") { "\nmismatch: $it" }
    }

    /**
     * A step whose failure the windows do not explain. A failed bug check already says what it found, which is the
     * evidence of the bug.
     */
    private fun showsNoWindow(step: UiStep): Boolean =
        step.action in NO_WINDOW_ACTIONS || step.bug != null ||
            step.action == UiAction.EXPECT && step.target == null && step.title == null && !step.layout

    /** [source], the step as written, with what this run found for its refs, row indexes and names. */
    private fun portable(step: UiStep, source: JsonObject): JsonObject =
        UiPortable.rewrite(source, portableTarget, portableRow, portableFields, textIsInput = step.action in TEXT_INPUT)

    /**
     * An expect's match, which also finds the portable target of a ref, as [resolve] does for the other steps. A ref
     * whose control closed matches nothing, rather than failing as stale: that is what `"is":"hidden"` waits for.
     */
    private suspend fun matchForExpect(target: UiTarget): UiMatch {
        target.ref?.let { if (registry.resolve(it) is UiRefResolution.Stale) return UiMatch.None(emptyList()) }
        val m = match(target)
        if (target.ref != null && m is UiMatch.One) notePortable(target, m.node)
        return m
    }

    /** Remembers the target a replay of the current step uses for [target], a ref that found [node]. */
    private suspend fun notePortable(target: UiTarget, node: UiNode) {
        if (target.ref == null || current?.target?.ref != target.ref || portableTarget != null) return
        val textIsInput = current?.action in TEXT_INPUT
        portableTarget = withContext(edtAny) { UiPortable.stableTarget(node, scopeModels().map { it.root }, textIsInput) }
    }

    /**
     * The windows that opened or closed since the previous step ended, as a prefix of this step's report. A window
     * that takes longer to open than the previous step waited, such as Settings on a cold start, shows up here.
     */
    private suspend fun meanwhile(): String {
        val before = windowsAfterLastStep ?: return ""
        val now = UiSettle.showingWindows()
        if (now == before) return ""
        return withContext(edtAny) {
            (now - before).map { "opened ${describeWindow(it)}" } + (before - now).map { "closed ${describeWindow(it)}" }
        }.joinToString("; ", prefix = "meanwhile ", postfix = "; ")
    }

    /** A picture of the topmost window for the trace, or null without a trace. */
    private suspend fun tracePicture(index: Int, suffix: String): String? {
        val t = trace ?: return null
        return withContext(edtAny) { scopeWindows().firstOrNull()?.let { t.picture(it, index, suffix) } }
    }

    /**
     * The snapshot text of the listed windows, or of the windows in scope. [topOnly] keeps the topmost one and names
     * the others, which is what a failed step needs: the window its target was looked for in.
     */
    suspend fun render(withBounds: Boolean, scopeOnly: Boolean = false, topOnly: Boolean = false): String = withContext(edtAny) {
        val windows = (if (scopeOnly) scopeWindows() else listedWindows())
        if (windows.isEmpty()) return@withContext "(window_id $windowId is no longer showing)"
        var budget = maxNodes
        val shown = if (topOnly) windows.take(1) else windows
        val text = shown.joinToString("\n\n") { window ->
            val model = UiModel.build(window)
            val refOf = { node: UiNode -> registry.refFor(node.component) }
            val text = UiSnapshotFormatter.format(header(window, model), model.root, refOf, budget.coerceAtLeast(1), withBounds)
            budget -= text.listedCount
            text.text + UiLayout.summary(window, model.root, refOf, project).joinToString("") { "\n$it" } +
                (if (window is IdeFrame) menu.summary(window)?.let { "\n$it" }.orEmpty() else "")
        }
        val others = windows.drop(shown.size)
        if (others.isEmpty()) text else text + "\n\nalso showing: " + others.joinToString("; ") { describeWindow(it) }
    }

    /** Runs one step and returns its report line; throws [UiStepFailure] when it cannot do what it asks. */
    suspend fun perform(step: UiStep): String = runStep(UiSteps.withRowRef(step))

    /** The component [target] addresses, waiting up to [timeoutMs] for one showing match. */
    suspend fun find(target: UiTarget, timeoutMs: Long): Component = resolve(target, timeoutMs, requireEnabled = false).component

    private suspend fun runStep(step: UiStep): String = when (step.action) {
        UiAction.WAIT -> waitStep(step)
        UiAction.SNAPSHOT -> snapshotStep(step)
        UiAction.INSPECT -> inspectStep(step)
        // Checked once the windows settled: an in-place refactoring shows its name lookup before its template is up. A
        // template that was up before the step, such as a rename whose options popup an ESCAPE closed, is not the step's.
        UiAction.RUN -> {
            val before = inplaceActive()
            withEffects { actStep(step) }.let {
                when {
                    !inplaceActive() -> it
                    before -> "$it; an in-place template is still active in the editor: press ESCAPE to end it"
                    else -> "$it; started an in-place template: type the value, then press ENTER"
                }
            }
        }
        UiAction.GET -> when {
            step.editors -> editorsReport(step)
            step.memory -> IdeMemory.getInstanceOrNull()?.report() ?: throw UiStepFailure("the IDE application is not available")
            step.builds -> IdeBuilds.getInstanceOrNull()?.recent(BUILDS_LISTED)?.let(IdeBuilds::renderRecent) ?: throw UiStepFailure("the IDE application is not available")
            step.notifications -> IdeNotifications.getInstanceOrNull()?.recent(NOTIFICATIONS_LISTED)?.let(IdeNotifications::renderRecent)
                ?: throw UiStepFailure("the IDE application is not available")
            step.problems != null -> problemsReport(step.problems!!, step.severity ?: "error", step.timeoutMs)
            step.changes -> codeChanges()?.runChanges()?.let { if (it.isEmpty()) "the run changed no project file" else UiCodeChanges.render(it, maxLines = Int.MAX_VALUE) }
                ?: throw UiStepFailure(NO_CHANGES)
            step.console != null -> IdeRuns.getInstanceOrNull()?.report(project.name, step.console!!, step.lines ?: UiSteps.DEFAULT_CONSOLE_LINES)
                ?: throw UiStepFailure("the IDE application is not available")
            step.file != null -> editors.facts(step.file!!)
            else -> config.get(step).line
        }
        UiAction.SET -> config.set(step).also { o -> o.option?.let { portableFields["option"] = it }; undo(o.undo) }.line
        UiAction.WRITE -> ideSteps.write(step, undo)
        UiAction.CODE -> ideSteps.code(step)
        UiAction.SETTINGS -> withEffects {
            ideSteps.settings(step).also { o -> o.id?.let { portableFields["page"] = it } }.line
        }
        UiAction.SCREENSHOT -> screenshotStep(step)
        UiAction.EXPECT -> error("an expect runs in run()")
        else -> withEffects { actStep(step) }
    }

    private suspend fun actStep(step: UiStep): String {
        return when (step.action) {
            UiAction.CLICK -> {
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = true)
                val row = rowArea(node, tabOf(node, step))
                clickOpensWindow = withContext(edtAny) { (node.component as? AbstractButton)?.text?.let(::opensWindow) == true }
                closingDialog = withContext(edtAny) {
                    (node.component as? JButton)?.takeIf { it.isDefaultButton && DialogWrapper.findInstance(it) != null }?.let(SwingUtilities::getWindowAncestor)
                }
                val offset = if (step.offsetX != null || step.offsetY != null) {
                    Point(step.offsetX ?: (node.component.width / 2), step.offsetY ?: (node.component.height / 2))
                } else null
                val click = input.click(node.component, button(step.button), step.count, UiInput.modifiersMask(step.modifiers), offset, row?.area)
                (node.component as? JMenu)?.let { menu -> return submenu(menu, node, "clicked") }
                withContext(edtAny) { row?.let { "on ${it.label}: " }.orEmpty() + describeClick(node, click) }
            }
            UiAction.HOVER -> {
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
                val row = rowArea(node, step)
                input.hover(node.component, row?.area)
                (node.component as? JMenu)?.takeIf { row == null }?.let { menu -> return submenu(menu, node, "moved over") }
                "moved over ${row?.let { "${it.label} in " }.orEmpty()}${describe(node)}"
            }
            UiAction.SCROLL -> scrollStep(step)
            UiAction.TYPE -> {
                val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = true) }
                val report = input.type(step.text!!, node?.component ?: keyRecipient())
                "typed ${step.text!!.length} character(s) into ${withContext(edtAny) { describeComponent(report.recipient) }}"
            }
            UiAction.FILL -> {
                // A label and its field share a name: the field is what a fill types into.
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = true, fits = { textField(it) != null || UiRows.rows(it) != null })
                if (step.row != null || step.index != null) return withContext(edtAny) { fillCell(step, node) }
                val field = withContext(edtAny) { textField(node.component) }
                    ?: throw UiStepFailure("${describe(node)} is not a text field and holds no single one")
                withContext(edtAny) { selectAllText(field) }
                if (step.text!!.isEmpty()) input.press(UiInput.parseKeys("DELETE"), field) else input.type(step.text!!, field)
                val value = withContext(edtAny) { field.text }
                // A field that keeps part of its text, as an editor field whose selection did not cover it all does,
                // would type a different value than asked; that is a failure, not a success with a surprise.
                if (value != step.text) throw UiStepFailure("typed \"${step.text}\" but ${describe(node)} holds \"${value.take(80)}\"")
                "value is now \"${value.take(80)}\""
            }
            UiAction.PRESS -> {
                val chord = UiInput.parseKeys(step.keys!!)
                val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = true) }
                val report = input.press(chord, node?.component ?: keyRecipient())
                "pressed ${step.keys} on ${withContext(edtAny) { describeComponent(report.recipient) }}"
            }
            UiAction.CHECK, UiAction.UNCHECK -> {
                val wanted = step.action == UiAction.CHECK
                if (step.path != null) return menu.step(step.path, actionComponent(), step.timeoutMs, wanted, undo)
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = true)
                val toggle = node.component as? AbstractButton ?: throw UiStepFailure("${describe(node)} is not a checkbox or toggle")
                if (withContext(edtAny) { toggle.isSelected } == wanted) {
                    "${describe(node)} was already ${if (wanted) "checked" else "unchecked"}"
                } else {
                    val click = input.click(toggle, MouseEvent.BUTTON1, 1, 0, null)
                    val now = withContext(edtAny) { toggle.isSelected }
                    if (now != wanted) throw UiStepFailure("clicked ${describe(node)} but it is still ${if (now) "checked" else "unchecked"}; ${describeClick(node, click)}")
                    "${describe(node)} is now ${if (wanted) "checked" else "unchecked"}"
                }
            }
            UiAction.SELECT -> selectStep(step)
            UiAction.CLOSE -> closeStep(step)
            UiAction.GOTO -> editorSteps.goto(step)
            UiAction.RUN -> editorSteps.run(step, actionComponent(), ::inplaceActive)
            UiAction.PERF -> ideSteps.perf(step)
            UiAction.TOOLWINDOW -> ideSteps.toolWindow(step, undo)
            UiAction.WINDOW -> windowStep(step)
            UiAction.MENU -> when {
                step.mode != null -> menu.setMode(step.mode!!, withContext(edtAny) { projectFrame() }, undo)
                step.show -> menu.show(step.path!!, withContext(edtAny) { projectFrame() }, input, step.timeoutMs)
                else -> menu.step(step.path, actionComponent(), step.timeoutMs, undo = undo)
            }
            UiAction.WAIT, UiAction.SNAPSHOT, UiAction.INSPECT, UiAction.EXPECT, UiAction.GET, UiAction.SET,
            UiAction.WRITE, UiAction.CODE, UiAction.SETTINGS, UiAction.SCREENSHOT, UiAction.SPLITTER -> error("not an input step")
        }
    }

    /**
     * The field a fill types into: [c] itself, an editable combo box's editor, or the one editable text field inside
     * a wrapper such as `SearchTextField`. EDT.
     */
    /**
     * Selects all of [field]'s text, so that typing replaces it. An IDE editor field, such as the name field of a
     * Rename dialog that preselects the name without its extension, keeps its own selection, which selectAll on its
     * Swing face does not change.
     */
    private fun selectAllText(field: JTextComponent) {
        val editor = (field as? EditorComponentImpl)?.editor
        if (editor != null) editor.selectionModel.setSelection(0, editor.document.textLength) else field.selectAll()
    }

    private fun textField(c: Component): JTextComponent? {
        (c as? JTextComponent)?.let { return it }
        (c as? JComboBox<*>)?.takeIf { it.isEditable }?.let { return it.editor?.editorComponent as? JTextComponent }
        return UIUtil.findComponentsOfType(c as? JComponent ?: return null, JTextComponent::class.java)
            .singleOrNull { it.isShowing && it.isEditable }
    }

    /**
     * A fill step with a row: sets the row's first editable cell after its name, as a table of options such as Code
     * Style's holds its values, through the cell's own editor, and reads the row back. EDT.
     */
    private fun fillCell(step: UiStep, node: UiNode): String {
        val table = node.component as? JTable
            ?: throw UiStepFailure("fill with a row sets a table cell, and ${describe(node)} is not a table")
        val rows = UiRows.rows(table)!!
        val index = step.index ?: UiRows.find(table, rows, step.row!!)
        if (index !in rows.indices) throw UiStepFailure("${describe(node)} has no row ${step.row?.let { "\"$it\"" } ?: "#${step.index}"}; rows: " + rows.take(10).withIndex().joinToString("; ") { (i, r) -> "#$i $r" })
        val column = (1 until table.columnCount).firstOrNull { table.isCellEditable(index, it) }
            ?: throw UiStepFailure("row #$index \"${rows[index]}\" of ${describe(node)} has no cell to edit")
        UiRows.select(table, index)
        if (!table.editCellAt(index, column)) throw UiStepFailure("row #$index \"${rows[index]}\" of ${describe(node)} did not start editing")
        val editor = table.editorComponent ?: throw UiStepFailure("row #$index \"${rows[index]}\" of ${describe(node)} opened no editor")
        val text = step.text!!
        try {
            when {
                editor is JComboBox<*> -> {
                    val item = (0 until editor.itemCount).firstOrNull { UiComponentFacts.clean(editor.getItemAt(it).toString()) == text }
                        ?: throw UiStepFailure("the cell's list has no item \"$text\"; items: " + (0 until minOf(editor.itemCount, 20)).joinToString { editor.getItemAt(it).toString() })
                    editor.selectedIndex = item
                }
                editor is AbstractButton -> editor.isSelected = when (text.lowercase()) {
                    in CHECKED_WORDS -> true
                    in UNCHECKED_WORDS -> false
                    else -> throw UiStepFailure("the cell is a checkbox: fill it with true or false, not \"$text\"")
                }
                else -> {
                    val field = editor as? JTextComponent ?: UIUtil.findComponentOfType(editor as? JComponent, JTextComponent::class.java)
                        ?: throw UiStepFailure("the cell's editor ${UiComponentFacts.simpleClassName(editor)} takes no text")
                    field.text = text
                    (editor as? JSpinner)?.commitEdit()
                }
            }
        } catch (e: UiStepFailure) {
            // A value the cell does not take leaves it as it was, not half edited.
            table.cellEditor?.cancelCellEditing()
            throw e
        }
        if (table.isEditing && table.cellEditor?.stopCellEditing() == false) {
            table.cellEditor?.cancelCellEditing()
            throw UiStepFailure("row #$index \"${rows[index]}\" of ${describe(node)} refused \"$text\"")
        }
        return "row #$index \"${rows[index]}\" of ${describe(node)} now shows ${UiRows.cells(table, index).joinToString(" | ") { "\"${it.take(80)}\"" }}"
    }

    /**
     * Selects a row through the component's selection, as the keyboard does. A click would also activate the row in
     * a list that acts on a click, such as Find Action's results, and a combo box would need its popup opened.
     */
    private suspend fun selectStep(step: UiStep): String {
        val found = resolve(step.target!!, step.timeoutMs, requireEnabled = true)
        // An open combo box popup's list shows the combo box's items: selecting in the list alone would not pick one.
        val node = withContext(edtAny) { UiRows.comboOf(found.component)?.let { FallbackUiWalker().leaf(it) } } ?: found
        val host = node.component
        val pick = pickRow(node, step)!!
        return withContext(edtAny) {
            UiRows.select(host, pick.index)
            if (!UiRows.isSelected(host, pick.index)) throw UiStepFailure("${describe(node)} did not take the selection of row #${pick.index}")
            pick.expandedNote() + "selected row #${pick.index} \"${pick.text}\" in ${describe(node)}"
        }
    }

    /** A row a step picked, and the tree rows it expanded to reach it. */
    private class RowPick(val index: Int, val text: String, val expanded: List<String>) {
        fun expandedNote() = if (expanded.isEmpty()) "" else "expanded ${expanded.joinToString(", ") { "\"$it\"" }}; "
    }

    /**
     * The row "row" or "index" of [step] names in [node]'s list, tree, table or tabbed pane, or null when the step
     * names none. A tree path whose parents are collapsed, such as `Editor > Code Style > Java`, expands them. A row
     * that is not there yet is waited for up to the step's timeout: a list filled asynchronously, such as the Settings
     * tree filtering to what was just typed into its search, shows its rows some time after the step before it.
     */
    private suspend fun pickRow(node: UiNode, step: UiStep): RowPick? {
        if (step.row == null && step.index == null) return null
        val c = node.component
        val wanted = step.row
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val late = started.elapsedNow().inWholeMilliseconds >= step.timeoutMs
            val rows = withContext(edtAny) { UiRows.rows(c) }
                ?: throw UiStepFailure("${describe(node)} has no rows; rows are in lists, trees, tables, tabbed panes and combo boxes")
            val index = step.index ?: withContext(edtAny) { UiRows.find(c, rows, wanted!!) }
            if (index in rows.indices) {
                if (step.index != null && current === step) withContext(edtAny) { UiPortable.stableRow(c, rows, index) }?.let { portableRow = it }
                return RowPick(index, rows[index], emptyList())
            }
            // A tree table's rows are its tree's, so the tree's row found is the table's row too.
            val tree = withContext(edtAny) { UiRows.treeOf(c) }
            if (wanted != null && tree != null && UiRows.PATH_SEPARATOR in wanted) return expandPath(tree, wanted, step.timeoutMs)
            if (late) {
                val shown = rows.withIndex().take(20).joinToString("; ") { (i, row) -> "#$i $row" }
                throw UiStepFailure("no row ${wanted?.let { "\"$it\"" } ?: "#$index"} in ${describe(node)} after ${step.timeoutMs} ms; rows: $shown" +
                    if (rows.size > 20) "; +${rows.size - 20} more" else "")
            }
            delay(POLL_MS)
        }
    }

    /**
     * Finds `A > B > C` in [tree] one segment at a time, expanding each parent as a user would and waiting up to
     * [timeoutMs] for its children to load. The first segment may be any row in view.
     */
    private suspend fun expandPath(tree: JTree, wanted: String, timeoutMs: Long): RowPick {
        val started = TimeSource.Monotonic.markNow()
        val segments = wanted.split(UiRows.PATH_SEPARATOR).map { it.trim() }
        val expanded = mutableListOf<String>()
        var parent: TreePath? = null
        for ((i, segment) in segments.withIndex()) {
            var row: Int
            while (true) {
                // An async tree model shows a "loading" child first, so wait for the row itself. Read the deadline
                // first: a busy EDT can run the expansion only after the time is up, and the look after it counts.
                val late = started.elapsedNow().inWholeMilliseconds >= timeoutMs
                row = withContext(edtAny) { UiRows.childRow(tree, parent, segment) }
                if (row >= 0 || late) break
                delay(POLL_MS)
            }
            if (row < 0) {
                val reached = segments.take(i).joinToString(UiRows.PATH_SEPARATOR)
                val children = withContext(edtAny) { parent?.let { UiRows.childRows(tree, it) } }
                throw UiStepFailure(
                    when {
                        parent == null -> "no row \"$segment\" in the tree's rows in view"
                        children.isNullOrEmpty() -> "\"$reached\" shows no children after $timeoutMs ms"
                        else -> "no row \"$segment\" under \"$reached\"; its rows: ${children.take(20).joinToString("; ")}"
                    }
                )
            }
            parent = withContext(edtAny) {
                val path = tree.getPathForRow(row)
                if (i < segments.lastIndex && !tree.isExpanded(path)) {
                    tree.expandPath(path)
                    expanded += UiRows.treePath(tree, row)
                }
                path
            }
        }
        return withContext(edtAny) {
            val row = tree.getRowForPath(parent)
            RowPick(row, UiRows.treePath(tree, row), expanded)
        }
    }

    /**
     * [step] aimed at a tab when it clicks a tabbed pane without a row: the tab its name or text names. A tabbed pane's
     * name is its selected tab's title, so a click by that name means the tab, not the middle of the pane's content.
     */
    private fun tabOf(node: UiNode, step: UiStep): UiStep {
        if (node.component !is JTabbedPane && node.component !is com.intellij.ui.tabs.JBTabs || step.row != null || step.index != null) return step
        val tab = step.target?.name ?: step.target?.text
            ?: throw UiStepFailure("${describe(node)} is clicked on a tab: pass \"row\" with the tab's title, or a row ref")
        return step.copy(row = tab)
    }

    /** Row [pick] of [node] scrolled into view, with where it is: the row's area in the component and how to name it. */
    private class RowArea(val area: Rectangle, val label: String)

    /** The row a click or hover step names, scrolled into view, or null when it names none. */
    private suspend fun rowArea(node: UiNode, step: UiStep): RowArea? {
        val pick = pickRow(node, step) ?: return null
        return withContext(edtAny) {
            val c = node.component
            val area = UiRows.bounds(c, pick.index)
                ?: throw UiStepFailure("${describe(node)} shows its items in a popup: pick one with select")
            UiRows.scrollTo(c, pick.index)
            RowArea(area, pick.expandedNote() + "row #${pick.index} \"${pick.text.take(80)}\"")
        }
    }

    /**
     * Brings the target, or its row, into view, as a user scrolls to it; or with "pages", scrolls the scroll pane
     * around the target by that many pages, down when positive. The report says what part of the content shows.
     */
    private suspend fun scrollStep(step: UiStep): String {
        val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
        val c = node.component
        val pages = step.pages
        step.align?.let { align ->
            val pick = pickRow(node, step)
            return withContext(edtAny) {
                val area = pick?.let { UiRows.bounds(c, it.index) ?: throw UiStepFailure("${describe(node)} shows its items in a popup: pick one with select") }
                    ?: Rectangle(0, 0, c.width, c.height)
                val what = pick?.let { "row #${it.index} \"${it.text.take(80)}\" of " }.orEmpty() + describe(node)
                // A control in no scroll pane shows where it is: the step reports that, and its bounds, which a JetBrains
                // Client's highlight on a host page reads.
                val port = UiScrollAlign.scroll(c, area, align)
                val moved = if (port == null) "$what is in no scroll pane, so it stays where it is"
                else "scrolled $what to the ${if (align == "top") "top" else "middle"} of its view; ${position(port)}"
                "$moved; ${UiScrollAlign.boundsNote(onScreen(c, area))}"
            }
        }
        if (pages == null) {
            val row = rowArea(node, step)
            return withContext(edtAny) {
                if (row == null) (c as? JComponent)?.scrollRectToVisible(Rectangle(0, 0, c.width, c.height))
                "scrolled ${row?.let { "${it.label} of " }.orEmpty()}${describe(node)} into view" + (viewport(c)?.let { "; ${position(it)}" }.orEmpty()) +
                    "; " + UiScrollAlign.boundsNote(onScreen(c, row?.area ?: Rectangle(0, 0, c.width, c.height)))
            }
        }
        return withContext(edtAny) {
            val port = viewport(c) ?: throw UiStepFailure("${describe(node)} is not in a scroll pane")
            val view = port.view ?: throw UiStepFailure("the scroll pane around ${describe(node)} shows nothing")
            val extent = port.extentSize
            val maxY = maxOf(0, view.height - extent.height)
            val y = (port.viewPosition.y.toLong() + pages.toLong() * extent.height).coerceIn(0, maxY.toLong()).toInt()
            port.viewPosition = Point(port.viewPosition.x, y)
            "scrolled ${UiComponentFacts.simpleClassName(port.parent ?: port)} by $pages page(s); ${position(port)}"
        }
    }

    /** The viewport of the scroll pane that holds [c], or [c]'s own when it is a scroll pane. EDT. */
    private fun viewport(c: Component): JViewport? =
        (c as? JScrollPane)?.viewport ?: SwingUtilities.getAncestorOfClass(JViewport::class.java, c) as? JViewport

    /** Which part of a viewport's content shows, such as `showing 600-1200 of 2400 px, the bottom`. EDT. */
    private fun position(port: JViewport): String {
        val view = port.view ?: return "the scroll pane is empty"
        val top = port.viewPosition.y
        val bottom = top + port.extentSize.height
        val where = when {
            top <= 0 && bottom >= view.height -> "all of it"
            top <= 0 -> "the top"
            bottom >= view.height -> "the bottom"
            else -> "${top * 100 / maxOf(1, view.height)}% down"
        }
        return "showing $top-$bottom of ${view.height} px, $where"
    }

    private suspend fun closeStep(step: UiStep): String {
        val window = if (step.target != null) {
            val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
            withContext(edtAny) { node.component as? Window ?: SwingUtilities.getWindowAncestor(node.component) }
        } else {
            withContext(edtAny) { scopeWindows().firstOrNull { it !== projectFrame() } }
        } ?: throw UiStepFailure("there is no dialog, popup or separate window to close")
        return closeWindow(window)
    }

    /**
     * Closes the windows and menus that opened since [before], the newest first, as a close step does each, for a call
     * with restore. A window that does not close is reported, not failed: the restores after it still run.
     */
    suspend fun closeOpenedSince(before: Set<Window>): List<String> {
        val lines = mutableListOf<String>()
        withContext(edtAny) {
            val menus = MenuSelectionManager.defaultManager()
            if (menus.selectedPath.isNotEmpty()) {
                menus.clearSelectedPath()
                lines += "closed the open menu"
            }
        }
        UiSettle.barrier()
        // The showing windows leave tooltips out; the IDE lists windows in the order they were made.
        val new = UiSettle.showingWindows() - before
        val opened = withContext(edtAny) { Window.getWindows().filter { it in new }.reversed() }
        for (window in opened) {
            if (!withContext(edtAny) { window.isShowing }) continue
            val name = withContext(edtAny) { describeWindow(window) }
            lines += try {
                "$name: ${closeWindow(window)}"
            } catch (e: UiStepFailure) {
                "$name: not closed: ${e.message}"
            }
        }
        return lines
    }

    private suspend fun closeWindow(window: Window): String {
        val windowsBefore = UiSettle.showingWindows()
        val (way, closed) = withContext(edtAny) {
            val root = (window as? RootPaneContainer)?.rootPane
            val inside = root?.let { UIUtil.findComponentsOfType(it, JComponent::class.java).lastOrNull() }
            val dialog = inside?.let { DialogWrapper.findInstance(it) }
            val popup = inside?.let { PopupUtil.getPopupContainerFor(it) }
            val way = UiWindows.closeWay(
                UiWindows.kind(window),
                isProjectFrame = window === projectFrame(),
                hasDialogWrapper = dialog != null,
                hasPopup = popup != null,
                hasMenu = inside != null && UIUtil.findComponentOfType(root, JPopupMenu::class.java) != null,
            ) ?: throw UiStepFailure("the IDE window itself does not close; name a dialog, popup or separate window")
            val app = ApplicationManager.getApplication()
            way to when (way) {
                UiWindows.CloseWay.CANCEL_DIALOG -> {
                    app.invokeLater({ dialog!!.doCancelAction() }, ModalityState.any())
                    "cancelled the dialog"
                }
                UiWindows.CloseWay.CANCEL_POPUP -> {
                    app.invokeLater({ popup!!.cancel() }, ModalityState.any())
                    "cancelled the popup"
                }
                // A context menu or a main menu is a Swing menu, which closes with its submenus, as ESCAPE does.
                UiWindows.CloseWay.CLOSE_MENU -> {
                    MenuSelectionManager.defaultManager().clearSelectedPath()
                    "closed the menu and its submenus"
                }
                // A window no DialogWrapper holds, such as the separate or floating Settings window, closes as by its
                // title bar's close button.
                UiWindows.CloseWay.REQUEST_CLOSE -> {
                    app.invokeLater({ window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) }, ModalityState.any())
                    "asked the window to close"
                }
            }
        }
        UiSettle.barrier()
        // A menu closes in place and may leave its window showing. Any other window must go, or open another, such as
        // a confirmation: a window that ignores the request would otherwise read as closed.
        if (way != UiWindows.CloseWay.CLOSE_MENU) {
            suspend fun stillOpen() = withContext(edtAny) { window.isShowing } && UiSettle.showingWindows().none { it !in windowsBefore }
            val started = TimeSource.Monotonic.markNow()
            while (stillOpen() && started.elapsedNow().inWholeMilliseconds < CLOSE_WAIT_MS) delay(POLL_MS)
            if (stillOpen()) {
                throw UiStepFailure("$closed, and window \"${withContext(edtAny) { windowTitle(window) }}\" is still open; click its Cancel or Close button")
            }
        }
        return closed
    }

    /**
     * Sizes the window that holds the target, the one whose title contains "title", or the topmost window: the Settings
     * dialog when it shows, else the project frame.
     */
    private suspend fun windowStep(step: UiStep): String {
        step.dimension?.let { key -> return withContext(edtAny) { UiResize.restoreSavedSize(key, step.width!!.toInt(), step.height!!.toInt(), project) } }
        val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = false) }
        val window = withContext(edtAny) {
            when {
                node != null -> node.component as? Window ?: SwingUtilities.getWindowAncestor(node.component)
                step.title != null -> Window.getWindows().firstOrNull { it.isShowing && windowTitle(it)?.contains(step.title!!) == true }
                    ?: throw UiStepFailure("no window titled \"${step.title}\"; showing: " +
                        Window.getWindows().filter { it.isShowing }.mapNotNull(::windowTitle).joinToString { "\"$it\"" })
                else -> scopeWindows().firstOrNull()
            }
        } ?: throw UiStepFailure("no window is showing")
        // The IDE window keeps its size after the run. A dialog or the Settings window closes, but the IDE saves its size
        // for the next opening, which the restore puts back.
        withContext(edtAny) {
            when {
                window is IdeFrame && window is Frame -> undo(listOf(frameSize(window)))
                else -> UiResize.savedSizeKey(window)?.let { key ->
                    undo(listOf(UiRestore.step("window", "dimension" to key, "width" to window.width, "height" to window.height)))
                }
            }
        }
        return UiResize.window(window, step.width, step.height, step.maximize)
    }

    /** The step that gives the IDE window [frame] its size now: maximized, or its width and height. EDT. */
    private fun frameSize(frame: Frame): JsonObject {
        val target = "class" to frame.javaClass.simpleName
        return if (frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) UiRestore.step("window", target, "maximize" to true)
        else UiRestore.step("window", target, "width" to frame.width, "height" to frame.height)
    }

    /**
     * The layout lines of the topmost window, or only the controls under [target] that lie past an edge, for an expect
     * of layout. Empty when every control shows whole.
     */
    private suspend fun layoutProblems(target: UiTarget?): List<String> {
        val scope = target?.let {
            when (val m = matchForExpect(it)) {
                is UiMatch.One -> m.node
                is UiMatch.Many -> throw UiStepFailure("${m.matches.size} controls match; add nth, a class or a ref: " + m.matches.take(10).joinToString("; ") { n -> describe(n) })
                is UiMatch.None -> throw UiStepFailure("the target matches nothing" +
                    if (m.candidates.isEmpty()) "" else "; nearest: " + m.candidates.joinToString("; ") { n -> describe(n) })
            }
        }
        return withContext(edtAny) {
            val refOf = { node: UiNode -> registry.refFor(node.component) }
            if (scope != null) {
                val cut = UiModel.build(scope.component).root.walk()
                    .filter { it.listed && (it.clip == UiClip.OUTSIDE || it.clip == UiClip.CLIPPED) }.toList()
                if (cut.isEmpty()) emptyList()
                else listOf(cut.joinToString("; ", prefix = "${cut.size} control(s) are cut: ") { "${describe(it)} [${it.clip!!.label}]" } +
                    "; " + UiLayout.unreachable(cut.first().component, project))
            } else {
                // The window in front, as a person checks it: the others may lie under it.
                scopeWindows().take(1).flatMap { window -> UiModel.build(window).let { UiLayout.summary(window, it.root, refOf, project) } }
            }
        }
    }

    private suspend fun waitStep(step: UiStep): String {
        val started = TimeSource.Monotonic.markNow()
        fun took() = "after ${started.elapsedNow().inWholeMilliseconds} ms"
        return when (step.condition!!) {
            UiWaitCondition.VISIBLE -> "${describe(resolve(step.target!!, step.timeoutMs, requireEnabled = false))} is showing ${took()}"
            UiWaitCondition.ENABLED -> "${describe(resolve(step.target!!, step.timeoutMs, requireEnabled = true))} is enabled ${took()}"
            UiWaitCondition.HIDDEN -> {
                while (started.elapsedNow().inWholeMilliseconds < step.timeoutMs) {
                    if (match(step.target!!) is UiMatch.None) return "no longer showing ${took()}"
                    delay(POLL_MS)
                }
                throw UiStepFailure("still showing after ${step.timeoutMs} ms")
            }
            UiWaitCondition.WINDOW -> {
                while (started.elapsedNow().inWholeMilliseconds < step.timeoutMs) {
                    val found = withContext(edtAny) {
                        Window.getWindows().firstOrNull { it.isShowing && windowTitle(it)?.contains(step.title!!) == true }
                    }
                    if (found != null) return "window ${WindowIdUtil.compute(found, found)} \"${windowTitle(found)}\" is showing ${took()}"
                    delay(POLL_MS)
                }
                val titles = withContext(edtAny) { Window.getWindows().filter { it.isShowing }.mapNotNull(::windowTitle) }
                throw UiStepFailure("no window titled \"${step.title}\" after ${step.timeoutMs} ms; showing: ${titles.joinToString { "\"$it\"" }}")
            }
            UiWaitCondition.IDLE -> {
                UiSettle.settle(quietMs = 300, maxMs = step.timeoutMs)
                "the windows settled ${took()}"
            }
        }
    }

    /**
     * Saves a picture of the window that holds the target, or of the topmost window, with the popups open above it:
     * to `out`, or as `<save>.png` in the call's execution folder. The highlights are outlined and numbered, each
     * scrolled into the middle of its view first when it is out of view, and the crop cuts the picture to the
     * Settings page, the highlights or a control. `<name>.json` beside it records what makes two pictures of the same
     * state differ: the window's size, the scale, the theme, the editor font, the IDE build and the crop. A picture
     * that replaces a file says whether it changed.
     */
    private suspend fun screenshotStep(step: UiStep): String {
        val file = step.out?.let { UiCapturePaths.resolve(it, scenarioDir) }
            ?: (artifacts ?: throw UiStepFailure("screenshot has no folder to save to in this call")).resolve("screenshots").resolve(step.save!! + ".png")
        val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = false) }
        val window = withContext(edtAny) { (node?.let { windowOf(it.component) } ?: scopeWindows().firstOrNull())?.let(UiCapture::pictured) }
            ?: throw UiStepFailure("no window is showing")
        val highlights = step.highlight.orEmpty().map { locateHighlight(it, window, step.timeoutMs) }
        val cropOnBackend = (step.crop as? UiCrop.Control)?.let { backendBounds(it.target, null, null, window)?.first }
        val cropControl = if (cropOnBackend != null) null else (step.crop as? UiCrop.Control)?.let { resolve(it.target, step.timeoutMs, requireEnabled = false) }
        withContext(edtAny) { highlights.forEach { it.bringIntoView() } }
        UiSettle.settle()
        val (canvas, facts, line) = withContext(edtAny) {
            if (!window.isShowing) throw UiStepFailure("${describeWindow(window)} closed before its picture")
            val marks = highlights.mapIndexed { i, h -> UiCapture.Mark(i + 1, h.screenBounds(), h.label) }
            val painted = UiCapture.paint(window).let { if (marks.isEmpty()) it else UiCapture.highlight(it, marks) }
            val area = when (val crop = step.crop) {
                null -> null
                UiCrop.Page -> UiSettingsParts.page(window)?.let { UiCapture.withMarks(painted, it, marks) }
                    ?: throw UiStepFailure("crop \"page\" needs a Settings page, and ${describeWindow(window)} shows none")
                UiCrop.Highlights -> UiCapture.markArea(painted, marks)
                UiCrop.Popups -> UiCapture.popupArea(window)?.let { UiCapture.withMarks(painted, it, marks) }
                    ?: throw UiStepFailure("crop \"popups\" needs an open menu or popup above ${describeWindow(window)}")
                is UiCrop.ToolWindow -> {
                    val views = UiLayout.toolWindows(project)
                    val view = views.firstOrNull { it.id.equals(crop.id, ignoreCase = true) }
                        ?: throw UiStepFailure("no tool window ${crop.id} is showing; showing: ${views.joinToString { it.id }}")
                    val decorator = view.window.decorator
                    if (windowOf(decorator) !== window) throw UiStepFailure("the ${view.id} tool window is in another window than the picture")
                    UiCapture.withMarks(painted, onScreen(decorator, Rectangle(0, 0, decorator.width, decorator.height)), marks)
                }
                is UiCrop.Control -> if (cropOnBackend != null) UiCapture.withMarks(painted, cropOnBackend, marks) else {
                    val c = cropControl!!.component
                    if (windowOf(c) !== window) throw UiStepFailure("the crop ${crop.target} is in another window than the picture")
                    // A tree or list in a scroll pane is as tall as all its rows: the part in view is what shows.
                    val shown = (c as? JComponent)?.visibleRect ?: Rectangle(0, 0, c.width, c.height)
                    UiCapture.withMarks(painted, shown.apply { translate(c.locationOnScreen.x, c.locationOnScreen.y) }, marks)
                }
            }
            val canvas = area?.let { UiCapture.crop(painted, UiCapture.cropArea(it, step.margin ?: UiSteps.DEFAULT_MARGIN, painted.bounds)) } ?: painted
            val facts = UiPictureFacts.of(window)
            val what = listOfNotNull(
                highlights.takeIf { it.isNotEmpty() }?.withIndex()?.joinToString(", ", prefix = "highlights: ") { (i, h) -> "${i + 1} ${h.what}" },
                step.crop?.let { "crop ${if (it is UiCrop.Control) it.target.toString() else it.toString()}" },
            )
            Triple(canvas, facts, "saved ${canvas.image.width}x${canvas.image.height} picture of ${describeWindow(window)} to $file (${facts.describe()})" +
                what.joinToString("") { "; $it" })
        }
        val change = withContext(Dispatchers.IO) {
            try {
                java.nio.file.Files.createDirectories(file.parent)
                val before = if (java.nio.file.Files.isRegularFile(file)) runCatching { javax.imageio.ImageIO.read(file.toFile()) }.getOrNull() else null
                val existed = java.nio.file.Files.exists(file)
                val format = UiCapturePaths.format(file)
                // A JPEG has no alpha channel: ImageIO writes nothing for an ARGB picture.
                val image = if (format == "png") canvas.image else BufferedImage(canvas.image.width, canvas.image.height, BufferedImage.TYPE_INT_RGB).also {
                    it.createGraphics().apply { drawImage(canvas.image, 0, 0, java.awt.Color.WHITE, null); dispose() }
                }
                java.nio.file.Files.newOutputStream(file).use { if (!javax.imageio.ImageIO.write(image, format, it)) throw java.io.IOException("no $format writer") }
                val json = file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + ".json")
                java.nio.file.Files.writeString(json, facts.json(crop = step.crop?.let {
                    when (it) {
                        is UiCrop.Control -> "control"
                        is UiCrop.ToolWindow -> "toolwindow"
                        else -> it.toString()
                    }
                } ?: "window"))
                when {
                    !existed -> ""
                    before == null -> "; replaced a file that was not a readable picture"
                    // A JPEG never reads back as it was painted, so only a PNG is compared.
                    format != "png" -> ""
                    else -> when (val n = UiCapture.differingPixels(before, canvas.image)) {
                        0 -> "; unchanged"
                        Int.MAX_VALUE -> "; changed: the size was ${before.width}x${before.height}"
                        else -> "; changed: $n pixels differ"
                    }
                }
            } catch (e: java.io.IOException) {
                // An AccessDeniedException's message is only the path: its class says what went wrong.
                throw UiStepFailure("cannot write the picture to $file (${e.javaClass.simpleName}" + (e.message?.takeIf { it != file.toString() }?.let { ": $it" } ?: "") + ")")
            }
        }
        return line + change
    }

    /** [area], in [c]'s coordinates, on screen. EDT. */
    private fun onScreen(c: Component, area: Rectangle): Rectangle = Rectangle(area).apply { translate(c.locationOnScreen.x, c.locationOnScreen.y) }

    /** A highlight found: where to outline it, how to name it, and its label. */
    private sealed class Located(val what: String, val label: String?) {
        /** Scrolls the highlight to the middle of its view when part of it is out of view. EDT. */
        abstract fun bringIntoView()

        /** EDT. */
        abstract fun screenBounds(): Rectangle
    }

    /**
     * A highlight in this process: its component and the area of it to outline, in its coordinates. With [visibleOnly],
     * a control larger than its view, the outline covers the part that shows when the picture is taken.
     */
    private inner class LocalHighlight(
        val component: Component, val area: Rectangle, what: String, label: String?, val visibleOnly: Boolean = false,
    ) : Located(what, label) {
        override fun bringIntoView() {
            if (!visibleOnly && !UiScrollAlign.inView(component, area)) UiScrollAlign.scroll(component, area, "center")
        }

        override fun screenBounds(): Rectangle =
            onScreen(component, if (visibleOnly) (component as? JComponent)?.visibleRect ?: area else area)
    }

    /** A highlight on a host Settings page, which the backend found and scrolled into view: its screen bounds. */
    private class BackendHighlight(val bounds: Rectangle, what: String, label: String?) : Located(what, label) {
        override fun bringIntoView() = Unit
        override fun screenBounds(): Rectangle = Rectangle(bounds)
    }

    /**
     * Finds [h] in [window] or a popup above it: the Settings breadcrumb, a row of a list, tree or table, or a control.
     * A control in another window, or one that is not showing, fails the step: its outline would land elsewhere.
     */
    private suspend fun locateHighlight(h: UiHighlight, window: Window, timeoutMs: Long): Located {
        if (h.breadcrumb) return withContext(edtAny) {
            val bar = UiSettingsParts.breadcrumbs(window) ?: throw UiStepFailure("no Settings page is showing, so there is no breadcrumb to highlight")
            val crumbs = UiSettingsParts.crumbsBounds(bar)
            LocalHighlight(bar, Rectangle(0, 0, crumbs.width, crumbs.height), "breadcrumb", h.label)
        }
        backendBounds(h.target!!, h.row, h.index, window)?.let { (bounds, what) -> return BackendHighlight(bounds, what, h.label) }
        val node = resolve(h.target!!, timeoutMs, requireEnabled = false)
        val pick = pickRow(node, UiStep(UiAction.SCREENSHOT, h.target, row = h.row, index = h.index, timeoutMs = timeoutMs))
        return withContext(edtAny) {
            val c = node.component
            if (!c.isShowing) throw UiStepFailure("${describe(node)} is not showing; select its tab or page first")
            val owner = windowOf(c)
            if (owner !== window && owner !in UiCapture.popupsOf(window)) {
                throw UiStepFailure("${describe(node)} is in ${owner?.let { describeWindow(it) } ?: "no window"}, not in the pictured ${describeWindow(window)}")
            }
            val what = pick?.let { "row #${it.index} \"${it.text.take(60)}\" of ${describe(node)}" } ?: describe(node)
            if (pick != null) {
                val row = UiRows.bounds(c, pick.index) ?: throw UiStepFailure("${describe(node)} shows its items in a popup; open it first")
                LocalHighlight(c, row, what, h.label)
            } else {
                val (area, scroll) = UiScrollAlign.wholeAreaOf(c)
                // A control that fits its view is outlined whole; one larger than its view as far as it shows.
                LocalHighlight(c, area, what, h.label, visibleOnly = !scroll && area != Rectangle(0, 0, c.width, c.height))
            }
        }
    }

    /**
     * In a JetBrains Client showing a host Settings page, whose controls exist only on the backend, the screen bounds of
     * [target] (or its row) as the backend finds them after scrolling it to the middle of its view, and how the backend
     * names it. Null when this is no JetBrains Client, no host page shows, or the Client has a match of its own.
     */
    private suspend fun backendBounds(target: UiTarget, row: String?, index: Int?, window: Window): Pair<Rectangle, String>? {
        if (forward == null || !withContext(edtAny) { UiSettingsParts.hostPage(window) } || match(target) !is UiMatch.None) return null
        val source = buildJsonObject {
            put("action", "scroll")
            target.ref?.let { put("ref", it) }
            target.name?.let { put("name", it) }
            target.text?.let { put("text", it) }
            target.cls?.let { put("class", it) }
            target.xpath?.let { put("xpath", it) }
            target.nth?.let { put("nth", it) }
            row?.let { put("row", it) }
            index?.let { put("index", it) }
            put("align", "center")
            put("side", "backend")
        }
        val step = UiSteps.parse(JsonArray(listOf(source))).single()
        val report = forward.invoke(step)
        if (!report.passed) throw UiStepFailure("on the backend's host page: ${report.text}")
        val bounds = UiScrollAlign.parseBounds(report.text) ?: throw UiStepFailure("the backend gave no screen bounds: ${report.text}")
        return bounds to "${target} on the backend's host page"
    }

    /** The window that holds [c], or [c] itself when it is one. EDT. */
    private fun windowOf(c: Component): Window? = c as? Window ?: SwingUtilities.getWindowAncestor(c)

    private suspend fun snapshotStep(step: UiStep): String {
        if (step.target == null) return "\n" + render(withBounds = false)
        val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
        return withContext(edtAny) {
            val window = node.component as? Window ?: SwingUtilities.getWindowAncestor(node.component)
            val wrapper = node.copy(children = listOf(node))
            "\n" + UiSnapshotFormatter.format(
                header(window, UiModelResult(node, "subtree", null)),
                wrapper, { registry.refFor(it.component) }, maxNodes, withBounds = false,
            ).text
        }
    }

    /**
     * Where the target comes from, as the UI Inspector finds it, and for a list, tree or table the facts of one row:
     * the one "row" or "index" names, else the selected one. The first inspect starts recording where components are
     * created, so a window opened after it names its creator.
     */
    private suspend fun inspectStep(step: UiStep): String {
        val started = UiInspect.startRecording()
        val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
        val pick = pickRow(node, step)
        return withContext(edtAny) {
            val c = node.component
            val out = StringBuilder(describe(node)).append('\n').append(UiInspect.describe(c, project))
            if (UiInspect.creator(c) == null) {
                out.append(if (started) "; recording starts now: open the window again, then inspect it" else "; it was showing before the recording started")
            }
            val rows = UiRows.rows(c)
            if (rows != null && c !is JComboBox<*>) {
                val index = pick?.index ?: rows.indices.firstOrNull { UiRows.isSelected(c, it) }
                if (index == null) out.append("\nrows: none selected; pass \"row\" or \"index\" to inspect one")
                else out.append("\nrow #").append(index).append(" \"").append(rows[index].take(80)).append("\": ").append(UiInspect.describeRow(c, index))
            }
            out.toString()
        }
    }

    /** Runs an input step and adds what it caused: IDE actions, windows opened or closed, the new focus owner. */
    private suspend fun withEffects(act: suspend () -> String): String {
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val actionOpensWindow = AtomicBoolean(false)
        clickOpensWindow = false
        closingDialog = null
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(AnActionListener.TOPIC, object : AnActionListener {
            override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                if (event.presentation.text?.let(::opensWindow) == true) actionOpensWindow.set(true)
                // An action made on the fly, such as a tool window button's, has no id: its text says what it is.
                actions += ActionManager.getInstance().getId(action)
                    ?: event.presentation.text?.takeIf { it.isNotBlank() }?.let { "\"$it\"" }
                    ?: action.javaClass.simpleName
            }
        })
        val windowsBefore = UiSettle.showingWindows()
        val toolWindowsBefore = if (layoutMode == null) null else withContext(edtAny) { UiLayout.toolWindows(project).map { it.id }.toSet() }
        var noWindow = false
        val line = try {
            val result = act()
            // An action or button named with an ellipsis opens a dialog, which may take seconds to prepare: wait for
            // it rather than report a step that seemingly did nothing.
            if ((actionOpensWindow.get() || clickOpensWindow) && UiSettle.showingWindows() == windowsBefore) {
                noWindow = !UiSettle.awaitWindowChange(windowsBefore, OPENER_WAIT_MS, stopWhen = ::inplaceActive)
            }
            // A dialog's OK or Refactor first ends a table edit or checks its fields, then does its work, which may show a
            // progress window, and closes: a report made before that would show the dialog still open, and the next
            // step would act on the IDE behind it.
            closingDialog?.let { dialog ->
                val started = TimeSource.Monotonic.markNow()
                while (withContext(edtAny) { dialog.isShowing } && started.elapsedNow().inWholeMilliseconds < CLOSE_WAIT_MS) delay(POLL_MS)
            }
            // An IDE action often opens its window a few hundred milliseconds later (Settings does), so wait longer
            // for the windows to settle after one ran.
            if (actions.isEmpty()) UiSettle.settle() else UiSettle.settle(quietMs = ACTION_QUIET_MS, maxMs = ACTION_SETTLE_MS)
            result
        } finally {
            connection.disconnect()
        }
        val windowsAfter = UiSettle.showingWindows()
        val layout = toolWindowsBefore?.let { layoutAfter((windowsAfter - windowsBefore).filterNot(UiWindows::isHoverPopup), it) }.orEmpty()
        return withContext(edtAny) {
            buildList {
                add(line)
                addAll(layout)
                if (actions.isNotEmpty()) add("IDE actions: ${actions.joinToString()}")
                if (noWindow) add("no window opened within ${OPENER_WAIT_MS / 1000} s, although its name ends with an ellipsis")
                // A hover popup comes and goes with the mouse, so it is not something the step opened.
                (windowsAfter - windowsBefore).filterNot(UiWindows::isHoverPopup).forEach { add("opened ${describeWindow(it)}") }
                (windowsBefore - windowsAfter).filterNot(UiWindows::isHoverPopup).forEach { add("closed ${describeWindow(it)}") }
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let { add("focus: ${describeComponent(it)}") }
            }.joinToString("; ")
        }
    }

    /**
     * The layout lines of the windows a step [opened], of the tool windows it showed, which were not among
     * [toolWindowsBefore], and of the one a toolwindow step sized, as [layoutMode] wants them: noted, noted and
     * counted as a failed check, or made room for, which in auto mode overrides a size a step set too small.
     */
    private suspend fun layoutAfter(opened: List<Window>, toolWindowsBefore: Set<String>): List<String> {
        val sized = current?.takeIf { it.action == UiAction.TOOLWINDOW }?.id
        val problems = withContext(edtAny) {
            val refOf = { node: UiNode -> registry.refFor(node.component) }
            val frame = projectFrame()
            (opened + frame).distinct().filter { it.isShowing }.flatMap { window ->
                val found = UiLayout.problems(window, UiModel.build(window).root, refOf, project)
                if (window in opened) found
                else found.filter { p -> p.toolWindow != null && (p.toolWindow !in toolWindowsBefore || p.toolWindow.equals(sized, ignoreCase = true)) }
            }
        }
        if (problems.isEmpty()) return emptyList()
        if (layoutMode != AUTO) {
            if (layoutMode == CHECK) layoutFailure = problems.joinToString("; ") { it.line.removePrefix("layout: ") }
            return problems.map { it.line }
        }
        return problems.map { p -> p.fix?.let { "made room: " + applyFix(it) } ?: p.line }
    }

    /** Runs [fix], the step a layout line names, and reports the size it gave; its restore goes with the step's. */
    private suspend fun applyFix(fix: String): String {
        val step = UiSteps.parse("[$fix]").single()
        return if (step.action == UiAction.TOOLWINDOW) ideSteps.toolWindow(step, undo, sizeOnly = true) else actStep(step)
    }

    /**
     * In `auto` layout mode, makes room in the IDE window for every tool window and control it cuts, as a person does
     * before starting, and returns what it did, or null when nothing was cut. The restores go to the journal.
     */
    suspend fun makeRoom(): String? {
        if (layoutMode != AUTO) return null
        val fixes = withContext(edtAny) {
            val frame = projectFrame()
            UiLayout.problems(frame, UiModel.build(frame).root, { registry.refFor(it.component) }, project).mapNotNull { it.fix }
        }
        if (fixes.isEmpty()) return null
        val done = fixes.map { applyFix(it) }
        journal.add(stepUndo.toList())
        stepUndo.clear()
        return "made room: " + done.joinToString("; ")
    }

    /**
     * Whether the selected editor runs a template, as an in-place refactoring such as **Rename…** does: its action
     * is named with an ellipsis and opens no window.
     */
    private suspend fun inplaceActive(): Boolean = withContext(edtAny) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext false
        TemplateManager.getInstance(project).getActiveTemplate(editor) != null
    }

    /**
     * Finds [target], waiting up to [timeoutMs] for one showing (and, when asked, enabled) match. Of several matches, the
     * only one [fits] takes, such as the field a label names rather than the label, is the match. EDT for [fits].
     */
    private suspend fun resolve(target: UiTarget, timeoutMs: Long, requireEnabled: Boolean, fits: ((Component) -> Boolean)? = null): UiNode {
        val started = TimeSource.Monotonic.markNow()
        var last: UiMatch
        while (true) {
            last = match(target)
            if (fits != null && last is UiMatch.Many) {
                val many = last
                withContext(edtAny) { many.matches.filter { fits(it.component) }.singleOrNull() }?.let { last = UiMatch.One(it) }
            }
            when (val m = last) {
                is UiMatch.One -> {
                    if (!requireEnabled || withContext(edtAny) { m.node.component.isEnabled }) {
                        notePortable(target, m.node)
                        return m.node
                    }
                }
                is UiMatch.Many -> throw UiStepFailure(
                    "${m.matches.size} controls match; add nth, a class or a ref: " +
                        m.matches.take(10).joinToString("; ") { describe(it) }
                )
                is UiMatch.None -> if (windowId != null && withContext(edtAny) { listedWindows().isEmpty() }) break
            }
            if (started.elapsedNow().inWholeMilliseconds >= timeoutMs) break
            delay(POLL_MS)
        }
        return when (val m = last) {
            is UiMatch.One -> throw UiStepFailure("${describe(m.node)} stayed disabled for $timeoutMs ms")
            is UiMatch.None -> throw UiStepFailure(
                (if (withContext(edtAny) { listedWindows().isEmpty() }) "window_id $windowId is no longer showing" else "no match after $timeoutMs ms") +
                    modalNote() +
                    if (m.candidates.isEmpty()) "" else "; nearest: " + m.candidates.joinToString("; ") { describe(it) }
            )
            is UiMatch.Many -> error("unreachable")
        }
    }

    private suspend fun match(target: UiTarget): UiMatch {
        target.ref?.let { ref ->
            return when (val r = registry.resolve(ref)) {
                // A modal dialog blocks input to the other windows, so a ref into one of them waits like a miss.
                is UiRefResolution.Live -> withContext(edtAny) {
                    val window = r.component as? Window ?: SwingUtilities.getWindowAncestor(r.component)
                    if (window != null && window !in scopeWindows()) UiMatch.None(emptyList())
                    else UiMatch.One(FallbackUiWalker().leaf(r.component))
                }
                is UiRefResolution.Stale -> throw UiStepFailure("ref $ref is stale: its control is no longer showing. Call steroid_ui without steps for fresh refs")
                is UiRefResolution.Unknown -> throw UiStepFailure("unknown ref $ref. Refs come from a steroid_ui snapshot")
            }
        }
        return withContext(edtAny) {
            val models = scopeModels()
            val xpaths = models.mapNotNull { it.xpath }
            val xpath: ((String) -> Set<Component>)? = if (xpaths.isEmpty()) null else { x -> xpaths.flatMap { it(x) }.toSet() }
            UiLocator.find(models.map { it.root }, target, xpath)
        }
    }

    /** Explains a miss when a modal dialog blocks the other windows. Call off the EDT. */
    private suspend fun modalNote(): String = withContext(edtAny) {
        val modal = scopeWindows().firstOrNull { it is Dialog && it.isModal } ?: return@withContext ""
        "; only the modal dialog \"${windowTitle(modal)}\" and its popups were searched, because it blocks the other windows"
    }

    /** Windows to search: the topmost modal dialog and what it owns when one shows, else every listed window. EDT. */
    private fun scopeWindows(): List<Window> {
        val all = listedWindows()
        val modal = all.firstOrNull { it is Dialog && it.isModal } ?: return all
        return all.filter { w -> w === modal || generateSequence(w.owner) { it.owner }.any { it === modal } }
    }

    /** The listed windows: the one [windowId] names, none once it closed, else the project's. EDT. */
    private fun listedWindows(): List<Window> {
        if (windowId != null) {
            val c = findComponentByWindowId(windowId) ?: return emptyList()
            return listOf(c as? Window ?: SwingUtilities.getWindowAncestor(c) ?: return emptyList())
        }
        return UiWindows.projectWindows(project, projectFrame())
    }

    private fun projectFrame(): Window =
        WindowManager.getInstance().getFrame(project) ?: throw UiStepFailure("project ${project.name} has no frame")

    /**
     * Where a key step without a target goes: the focus owner when it is in a window in scope, else the control that
     * last had the focus in the topmost window, which is where the keyboard goes when a user brings it to front.
     * The IDE is often not the active application while an agent drives it, and then nothing has the focus.
     */
    private suspend fun keyRecipient(): Component = withContext(edtAny) {
        val scope = scopeWindows()
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        owner?.takeIf { SwingUtilities.getWindowAncestor(it) in scope }
            ?: scope.firstNotNullOfOrNull { it.mostRecentFocusOwner }
            ?: throw UiStepFailure("no control has the keyboard focus in the project's windows; give the step a target")
    }

    /**
     * Where a run step's action looks for its context: the editor a goto step of this call focused, which may not
     * have the focus yet while another application is active; else the control that has the focus in the project's
     * windows; else the selected editor; else the project frame.
     */
    private suspend fun actionComponent(): Component = withContext(edtAny) {
        val scope = scopeWindows()
        editorSteps.gotoEditor?.takeIf { it.isShowing }
            ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.takeIf { SwingUtilities.getWindowAncestor(it) in scope }
            ?: FileEditorManager.getInstance(project).selectedTextEditor?.contentComponent?.takeIf { it.isShowing }
            ?: (projectFrame() as? RootPaneContainer)?.rootPane
            ?: projectFrame()
    }

    private fun scopeModels(): List<UiModelResult> = scopeWindows().map { UiModel.build(it) }

    /**
     * After a click on or a move over a submenu, waits for its items: the IDE fills an action group's submenu after it
     * shows, so a report made at once would find it empty or not yet open.
     */
    private suspend fun submenu(menu: JMenu, node: UiNode, did: String): String {
        val started = TimeSource.Monotonic.markNow()
        var items = 0
        while (started.elapsedNow().inWholeMilliseconds < SUBMENU_WAIT_MS) {
            items = withContext(edtAny) { if (menu.isPopupMenuVisible) menu.popupMenu.components.count { it.isVisible && it is JMenuItem } else 0 }
            if (items > 0) break
            delay(POLL_MS)
        }
        UiSettle.barrier()
        return withContext(edtAny) {
            if (items > 0) "$did ${describe(node)}; its submenu shows $items items"
            else "$did ${describe(node)}, but its submenu did not open in $SUBMENU_WAIT_MS ms; press RIGHT to open it"
        }
    }

    private fun describeClick(node: UiNode, click: ClickReport): String = buildList {
        add(
            when {
                click.pressed == null -> "no component took the press"
                click.hitTarget -> "clicked ${describe(node)}"
                else -> "the press went to ${describeComponent(click.pressed)}, not to ${describe(node)}"
            }
        )
        when (click.actionPerformed) {
            true -> add("its action ran")
            false -> add("its action did not run")
            null -> Unit
        }
    }.joinToString("; ")

    private fun describe(node: UiNode): String = buildString {
        append(node.className)
        node.name?.let { append(" \"").append(it.take(60)).append('"') }
        append(" [ref=").append(registry.refFor(node.component)).append(']')
        if (!node.component.isEnabled) append(" [disabled]")
    }

    private fun describeComponent(c: Component): String = buildString {
        append(UiComponentFacts.simpleClassName(c))
        UiComponentFacts.name(c)?.let { append(" \"").append(it.take(60)).append('"') }
        append(" [ref=").append(registry.refFor(c)).append(']')
    }

    private fun describeWindow(w: Window): String {
        val kind = UiWindows.kind(w).label.let { if ((w as? Dialog)?.isModal == true) "modal $it" else it }
        val title = windowTitle(w)?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: firstText(w)?.let { " showing \"$it\"" }
        return "$kind ${WindowIdUtil.compute(w, w)}" + title.orEmpty()
    }

    /**
     * The first text a passive window without a title shows, such as a hint balloon's, so a report says what opened.
     * A window with a field or a list, such as Find Action, is left undescribed: its first text is a tab or a caption,
     * not what the window is. EDT.
     */
    private fun firstText(w: Window): String? {
        val root = (w as? RootPaneContainer)?.rootPane ?: return null
        val components = UIUtil.uiTraverser(root).toList()
        if (components.any { it.isShowing && (it is JTextComponent && it.isEditable || it is JList<*> || it is JTree) }) return null
        return components.asSequence()
            .filter { it.isShowing }
            .mapNotNull { UiComponentFacts.ownText(it) ?: (it as? SimpleColoredComponent)?.getCharSequence(false)?.toString() }
            .map(UiComponentFacts::clean)
            .firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length > FIRST_TEXT_MAX) it.take(FIRST_TEXT_MAX) + "…" else it }
    }

    private fun header(window: Window, model: UiModelResult) = UiWindowHeader(
        windowId = WindowIdUtil.compute(window, window),
        title = windowTitle(window),
        kind = UiWindows.kind(window).label,
        modal = (window as? Dialog)?.isModal == true,
        source = model.source,
        note = listOfNotNull(model.note, backendDrawnNote(window)).joinToString("; ").ifEmpty { null },
    )

    /**
     * In a JetBrains Client, a window whose content the backend draws, such as a Rename dialog or a host Settings
     * page, shows none of its controls here. Says so, since the snapshot alone reads as an empty window. The main
     * window always holds some backend-drawn part, so it is left out.
     */
    private fun backendDrawnNote(window: Window): String? {
        if (forward == null || window is IdeFrame) return null
        val root = (window as? RootPaneContainer)?.rootPane ?: return null
        val lux = UIUtil.uiTraverser(root).any { it.isShowing && it.javaClass.simpleName.startsWith(LUX_PREFIX) }
        return if (lux) "the backend draws controls of this window; add \"side\":\"backend\" to a step to see and drive them" else null
    }

    private fun windowTitle(w: Window): String? = (w as? Frame)?.title ?: (w as? Dialog)?.title

    private fun button(name: String?): Int = when (name) {
        null, "left" -> MouseEvent.BUTTON1
        "right" -> MouseEvent.BUTTON3
        "middle" -> MouseEvent.BUTTON2
        else -> throw UiStepFailure("unknown button $name")
    }

    companion object {
        /** Steps whose failure is about code, an action, a setting or a file, not about what the windows show. */
        private val NO_WINDOW_ACTIONS = setOf(
            UiAction.GOTO, UiAction.RUN, UiAction.GET, UiAction.SET, UiAction.WRITE, UiAction.CODE, UiAction.PERF, UiAction.TOOLWINDOW,
            UiAction.SETTINGS,
        )
        /** Steps that only read, which a recording of what to replay leaves out. */
        private val NOT_RECORDED = setOf(UiAction.SNAPSHOT, UiAction.INSPECT, UiAction.GET)
        private val TEXT_INPUT = setOf(UiAction.TYPE, UiAction.FILL)
        /**
         * Steps that a JetBrains Client sends to the backend unless their side says otherwise. A client cannot find a
         * project file by its path, so goto opens it on the backend, whose editor the client shows.
         */
        private val BACKEND_HOME = setOf(UiAction.WRITE, UiAction.CODE, UiAction.GOTO)
        private const val AUTO = "auto"
        private const val CHECK = "check"
        private const val EDITOR_WAIT_MS = 3_000L
        private const val BUILDS_LISTED = 10
        private const val NOTIFICATIONS_LISTED = 20
        private const val SUBMENU_WAIT_MS = 2_000L
        private const val CLOSE_WAIT_MS = 3_000L
        private const val NO_CHANGES = "in Split Mode the steps' code changes are not tracked: the JetBrains Client holds no project files, " +
            "and the backend runs each step it is sent as a call of its own; check a file's text with {\"action\":\"expect\",\"file\":\"...\",\"contains\":\"...\"}"
        private const val ON_BACKEND = "on the backend: "
        private const val LUX_PREFIX = "Lux"
        private val CHECKED_WORDS = setOf("true", "on", "yes", "[x]")
        private val UNCHECKED_WORDS = setOf("false", "off", "no", "[ ]")
        private const val POLL_MS = 100L
        private const val ACTION_QUIET_MS = 700L
        private const val ACTION_SETTLE_MS = 2_500L
        private const val FIRST_TEXT_MAX = 60

        /** How long a step waits for the dialog of an action or button named with an ellipsis. */
        private const val OPENER_WAIT_MS = 10_000L

        /** IntelliJ names an action or button that opens a dialog with a trailing ellipsis: "Settings…", "Edit...". */
        fun opensWindow(text: String): Boolean = text.trimEnd().let { it.endsWith("…") || it.endsWith("...") }
    }
}
