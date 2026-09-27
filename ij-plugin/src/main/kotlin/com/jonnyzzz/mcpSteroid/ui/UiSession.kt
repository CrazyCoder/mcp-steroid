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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.util.Disposer
import com.jonnyzzz.mcpSteroid.server.UiAction
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
import kotlinx.serialization.json.JsonObject
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.tree.TreePath
import kotlin.time.TimeSource

/** A step that could not do what it asked for. The message says what the IDE showed instead. */
class UiStepFailure(message: String) : RuntimeException(message)

data class UiStepReport(val index: Int, val line: String)

data class UiSessionResult(
    val reports: List<UiStepReport>,
    val failure: String?,
    val snapshot: String,
    /** How each step that ran ended, in order, for the verdict of a scenario. */
    val outcomes: List<UiStepOutcome> = emptyList(),
    /** The steps that ran, rewritten to replay in another session: refs replaced by names, row indexes by row text. */
    val recorded: List<JsonObject> = emptyList(),
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
     * In a JetBrains Client, runs one step on the Remote Development backend and returns its report line, throwing
     * [UiStepFailure] when it fails there. Null in a regular IDE and on the backend, where every step runs here.
     */
    private val forward: (suspend (UiStep) -> String)? = null,
) {
    private val registry = service<UiRefs>().registry
    private val input = UiInput()
    private val editorSteps = UiEditorSteps(project)
    private val ideSteps = UiIdeSteps(project, taskId)
    private val config = UiConfig(project)
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /** When the session started: an expect on errors or notifications counts the ones since then. */
    private val startedMs = System.currentTimeMillis()

    /** The step that runs, and what a replay of it names instead of its refs, row indexes, page names and option names. */
    private var current: UiStep? = null
    private var portableTarget: UiTarget? = null
    private var portableRow: String? = null
    private val portableFields = mutableMapOf<String, String>()

    /** The windows after the previous step, to report the ones that opened or closed between two steps. */
    private var windowsAfterLastStep: Set<Window>? = null

    /** Set by a click on a button whose text ends with an ellipsis, which by convention opens a dialog. */
    private var clickOpensWindow = false

    /**
     * Runs [steps], numbered from [firstIndex] in the reports, as a scenario run from a later step numbers them, and
     * named [labelPrefix], as a scenario's cleanup steps are.
     */
    suspend fun run(steps: List<UiStep>, mode: UiSnapshotMode, firstIndex: Int = 1, labelPrefix: String = "step"): UiSessionResult {
        val before = if (mode == UiSnapshotMode.DIFF) render(withBounds = false) else null
        val reports = mutableListOf<UiStepReport>()
        val outcomes = mutableListOf<UiStepOutcome>()
        val recorded = mutableListOf<JsonObject>()
        var failure: String? = null
        var failedStep: UiStep? = null
        val runStarted = TimeSource.Monotonic.markNow()
        val disposable = Disposer.newDisposable("steroid_ui session")
        val notifications = UiNotificationLog(project, disposable)
        val expect = UiExpect(project, startedMs, notifications, ::matchForExpect, ::describe)
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
                val outcome = try {
                    Result.success(
                        when {
                            forward != null && runsOnBackend(step) -> forward.invoke(step)
                            step.action == UiAction.EXPECT -> expect.run(step)
                            else -> runStep(step)
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
                }
                val line = meanwhile + outcome.fold({ it }, { it.message ?: it.javaClass.simpleName })
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
            }
        } finally {
            Disposer.dispose(disposable)
        }
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
        return UiSessionResult(reports, failure, snapshot, outcomes, recorded)
    }

    /**
     * Whether a JetBrains Client sends [step] to the backend: its `side` when it names one, else the steps that need the
     * project itself, which only the backend holds: files, the editor at a file, editor banners, scripts and the
     * inspection profile.
     */
    private fun runsOnBackend(step: UiStep): Boolean = when (step.side) {
        "backend" -> true
        "frontend" -> false
        else -> step.action in BACKEND_HOME || step.action == UiAction.EXPECT && (step.file != null || step.banner != null) ||
            (step.action == UiAction.GET || step.action == UiAction.SET) && step.inspection != null
    }

    /**
     * A step whose failure the windows do not explain. A failed bug check already says what it found, which is the
     * evidence of the bug.
     */
    private fun showsNoWindow(step: UiStep): Boolean =
        step.action in NO_WINDOW_ACTIONS || step.bug != null || step.action == UiAction.EXPECT && step.target == null && step.title == null

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
            val text = UiSnapshotFormatter.format(header(window, model), model.root, { registry.refFor(it.component) },
                budget.coerceAtLeast(1), withBounds)
            budget -= text.listedCount
            text.text
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
        // Checked once the windows settled: an in-place refactoring shows its name lookup before its template is up.
        UiAction.RUN -> withEffects { actStep(step) }.let {
            if (inplaceActive()) "$it; started an in-place template: type the value, then press ENTER" else it
        }
        UiAction.GET -> config.get(step).line
        UiAction.SET -> config.set(step).also { o -> o.option?.let { portableFields["option"] = it } }.line
        UiAction.WRITE -> ideSteps.write(step)
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
                val offset = if (step.offsetX != null || step.offsetY != null) {
                    Point(step.offsetX ?: (node.component.width / 2), step.offsetY ?: (node.component.height / 2))
                } else null
                val click = input.click(node.component, button(step.button), step.count, UiInput.modifiersMask(step.modifiers), offset, row?.area)
                withContext(edtAny) { row?.let { "on ${it.label}: " }.orEmpty() + describeClick(node, click) }
            }
            UiAction.HOVER -> {
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
                val row = rowArea(node, step)
                input.hover(node.component, row?.area)
                "moved over ${row?.let { "${it.label} in " }.orEmpty()}${describe(node)}"
            }
            UiAction.SCROLL -> scrollStep(step)
            UiAction.TYPE -> {
                val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = true) }
                val report = input.type(step.text!!, node?.component ?: keyRecipient())
                "typed ${step.text!!.length} character(s) into ${withContext(edtAny) { describeComponent(report.recipient) }}"
            }
            UiAction.FILL -> {
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = true)
                val field = withContext(edtAny) { textField(node.component) }
                    ?: throw UiStepFailure("${describe(node)} is not a text field and holds no single one")
                withContext(edtAny) { field.selectAll() }
                if (step.text!!.isEmpty()) input.press(UiInput.parseKeys("DELETE"), field) else input.type(step.text!!, field)
                val value = withContext(edtAny) { field.text }
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
            UiAction.TOOLWINDOW -> ideSteps.toolWindow(step)
            UiAction.WAIT, UiAction.SNAPSHOT, UiAction.INSPECT, UiAction.EXPECT, UiAction.GET, UiAction.SET,
            UiAction.WRITE, UiAction.CODE, UiAction.SETTINGS, UiAction.SCREENSHOT -> error("not an input step")
        }
    }

    /**
     * The field a fill types into: [c] itself, an editable combo box's editor, or the one editable text field inside
     * a wrapper such as `SearchTextField`. EDT.
     */
    private fun textField(c: Component): JTextComponent? {
        (c as? JTextComponent)?.let { return it }
        (c as? JComboBox<*>)?.takeIf { it.isEditable }?.let { return it.editor?.editorComponent as? JTextComponent }
        return UIUtil.findComponentsOfType(c as? JComponent ?: return null, JTextComponent::class.java)
            .singleOrNull { it.isShowing && it.isEditable }
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
            if (wanted != null && c is JTree && UiRows.PATH_SEPARATOR in wanted) return expandPath(c, wanted, step.timeoutMs)
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
        if (node.component !is JTabbedPane || step.row != null || step.index != null) return step
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
        if (pages == null) {
            val row = rowArea(node, step)
            return withContext(edtAny) {
                if (row == null) (c as? JComponent)?.scrollRectToVisible(Rectangle(0, 0, c.width, c.height))
                "scrolled ${row?.let { "${it.label} of " }.orEmpty()}${describe(node)} into view" + (viewport(c)?.let { "; ${position(it)}" }.orEmpty())
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
        val closed = withContext(edtAny) {
            val inside = (window as? RootPaneContainer)?.rootPane
                ?.let { UIUtil.findComponentsOfType(it, JComponent::class.java).lastOrNull() }
            val dialog = inside?.let { DialogWrapper.findInstance(it) }
            val popup = inside?.let { PopupUtil.getPopupContainerFor(it) }
            val title = windowTitle(window)
            when {
                dialog != null -> {
                    ApplicationManager.getApplication().invokeLater({ dialog.doCancelAction() }, ModalityState.any())
                    "cancelled the dialog"
                }
                popup != null -> {
                    ApplicationManager.getApplication().invokeLater({ popup.cancel() }, ModalityState.any())
                    "cancelled the popup"
                }
                // A separate window such as Settings closes as by its title bar's close button.
                window is Frame && window !== projectFrame() -> {
                    ApplicationManager.getApplication().invokeLater(
                        { window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING)) }, ModalityState.any(),
                    )
                    "asked the window to close"
                }
                else -> throw UiStepFailure("window \"$title\" is not a dialog, popup or separate window the IDE can close")
            }
        }
        UiSettle.barrier()
        return closed
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
     * Saves a picture of the window that holds the target, or of the topmost window, as `<save>.png` in the call's
     * execution folder, for a visual review: the agent reads the file, or a person compares it with an earlier run.
     */
    private suspend fun screenshotStep(step: UiStep): String {
        val dir = artifacts ?: throw UiStepFailure("screenshot has no folder to save to in this call")
        val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = false) }
        UiSettle.settle()
        return withContext(edtAny) {
            val window = node?.let { it.component as? Window ?: SwingUtilities.getWindowAncestor(it.component) }
                ?: scopeWindows().firstOrNull() ?: throw UiStepFailure("no window is showing")
            val file = dir.resolve("screenshots").resolve(step.save!! + ".png")
            UiTrace.paint(window, file)
            "saved ${window.width}x${window.height} picture of ${describeWindow(window)} to $file"
        }
    }

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
        var noWindow = false
        val line = try {
            val result = act()
            // An action or button named with an ellipsis opens a dialog, which may take seconds to prepare: wait for
            // it rather than report a step that seemingly did nothing.
            if ((actionOpensWindow.get() || clickOpensWindow) && UiSettle.showingWindows() == windowsBefore) {
                noWindow = !UiSettle.awaitWindowChange(windowsBefore, OPENER_WAIT_MS, stopWhen = ::inplaceActive)
            }
            // An IDE action often opens its window a few hundred milliseconds later (Settings does), so wait longer
            // for the windows to settle after one ran.
            if (actions.isEmpty()) UiSettle.settle() else UiSettle.settle(quietMs = ACTION_QUIET_MS, maxMs = ACTION_SETTLE_MS)
            result
        } finally {
            connection.disconnect()
        }
        val windowsAfter = UiSettle.showingWindows()
        return withContext(edtAny) {
            buildList {
                add(line)
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
     * Whether the selected editor runs a template, as an in-place refactoring such as **Rename…** does: its action
     * is named with an ellipsis and opens no window.
     */
    private suspend fun inplaceActive(): Boolean = withContext(edtAny) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext false
        TemplateManager.getInstance(project).getActiveTemplate(editor) != null
    }

    /** Finds [target], waiting up to [timeoutMs] for one showing (and, when asked, enabled) match. */
    private suspend fun resolve(target: UiTarget, timeoutMs: Long, requireEnabled: Boolean): UiNode {
        val started = TimeSource.Monotonic.markNow()
        var last: UiMatch
        while (true) {
            last = match(target)
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
        val kind = when (w) {
            is Frame -> "frame"
            is Dialog -> if (w.isModal) "modal dialog" else "dialog"
            else -> "popup"
        }
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
        kind = when (window) {
            is Frame -> "frame"
            is Dialog -> "dialog"
            else -> "popup"
        },
        modal = (window as? Dialog)?.isModal == true,
        source = model.source,
        note = model.note,
    )

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
