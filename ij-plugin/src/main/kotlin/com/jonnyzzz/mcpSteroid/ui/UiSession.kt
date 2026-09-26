/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.UIUtil
import com.jonnyzzz.mcpSteroid.server.UiAction
import com.jonnyzzz.mcpSteroid.server.UiSnapshotMode
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiTarget
import com.jonnyzzz.mcpSteroid.server.UiWaitCondition
import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil
import com.jonnyzzz.mcpSteroid.vision.findComponentByWindowId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseEvent
import java.util.Collections
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.time.TimeSource

/** A step that could not do what it asked for. The message says what the IDE showed instead. */
class UiStepFailure(message: String) : RuntimeException(message)

data class UiStepReport(val index: Int, val line: String)

data class UiSessionResult(val reports: List<UiStepReport>, val failure: String?, val snapshot: String)

/**
 * Runs steroid_ui steps in order against the project's windows, or against one window. Each step finds its target
 * inside the IDE, waiting up to its timeout, acts through [UiInput], lets the windows settle and reports what the
 * action did. The first failure stops the run.
 */
class UiSession(
    private val project: Project,
    private val windowId: String?,
    private val maxNodes: Int,
) {
    private val registry = service<UiRefs>().registry
    private val input = UiInput()
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    private class WindowModel(val window: Window, val model: UiModelResult)

    suspend fun run(steps: List<UiStep>, mode: UiSnapshotMode): UiSessionResult {
        val before = if (mode == UiSnapshotMode.DIFF) render(withBounds = false) else null
        val reports = mutableListOf<UiStepReport>()
        var failure: String? = null
        for ((i, step) in steps.withIndex()) {
            val label = "step ${i + 1} ${step.action.wire}${step.target?.let { " $it" }.orEmpty()}"
            try {
                reports += UiStepReport(i + 1, "$label: ${runStep(step)}")
            } catch (e: UiStepFailure) {
                failure = "$label failed: ${e.message}"
                break
            } catch (e: UiBarrierTimeout) {
                failure = "$label failed: ${e.message}"
                break
            } catch (e: IllegalArgumentException) {
                failure = "$label failed: ${e.message}"
                break
            } catch (e: IllegalStateException) {
                failure = "$label failed: ${e.message}"
                break
            }
        }
        val snapshot = when {
            failure != null -> render(withBounds = false, scopeOnly = true)
            mode == UiSnapshotMode.FULL -> render(withBounds = true)
            mode == UiSnapshotMode.NONE -> ""
            else -> UiSnapshotDiff.diff(before.orEmpty(), render(withBounds = false)).ifEmpty { "(the snapshot did not change)" }
        }
        return UiSessionResult(reports, failure, snapshot)
    }

    /** The snapshot text of the windows in scope. */
    suspend fun render(withBounds: Boolean, scopeOnly: Boolean = false): String = withContext(edtAny) {
        var budget = maxNodes
        windowModels(scopeOnly).joinToString("\n\n") { wm ->
            val text = UiSnapshotFormatter.format(header(wm.window, wm.model), wm.model.root, { registry.refFor(it.component) },
                budget.coerceAtLeast(1), withBounds)
            budget -= text.listedCount
            text.text
        }
    }

    private suspend fun runStep(step: UiStep): String = when (step.action) {
        UiAction.WAIT -> waitStep(step)
        UiAction.SNAPSHOT -> snapshotStep(step)
        else -> withEffects { actStep(step) }
    }

    private suspend fun actStep(step: UiStep): String {
        return when (step.action) {
            UiAction.CLICK -> {
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = true)
                val offset = if (step.offsetX != null || step.offsetY != null) {
                    Point(step.offsetX ?: (node.component.width / 2), step.offsetY ?: (node.component.height / 2))
                } else null
                val click = input.click(node.component, button(step.button), step.count, UiInput.modifiersMask(step.modifiers), offset)
                withContext(edtAny) { describeClick(node, click) }
            }
            UiAction.HOVER -> {
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
                input.hover(node.component)
                "moved over ${describe(node)}"
            }
            UiAction.TYPE -> {
                val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = true) }
                val report = input.type(step.text!!, node?.component)
                "typed ${step.text!!.length} character(s) into ${withContext(edtAny) { describeComponent(report.recipient) }}"
            }
            UiAction.FILL -> {
                val node = resolve(step.target!!, step.timeoutMs, requireEnabled = true)
                val field = (node.component as? JComboBox<*>)?.takeIf { it.isEditable }?.editor?.editorComponent as? JTextComponent
                    ?: node.component as? JTextComponent
                    ?: throw UiStepFailure("${describe(node)} is not a text field")
                withContext(edtAny) { field.selectAll() }
                if (step.text!!.isEmpty()) input.press(UiInput.parseKeys("DELETE"), field) else input.type(step.text!!, field)
                val value = withContext(edtAny) { field.text }
                "value is now \"${value.take(80)}\""
            }
            UiAction.PRESS -> {
                val chord = UiInput.parseKeys(step.keys!!)
                val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = true) }
                val report = input.press(chord, node?.component)
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
            UiAction.WAIT, UiAction.SNAPSHOT -> error("not an action step")
        }
    }

    private suspend fun selectStep(step: UiStep): String {
        val node = resolve(step.target!!, step.timeoutMs, requireEnabled = true)
        var host = node.component
        if (host is JComboBox<*>) {
            val combo = host
            // An editable combo box's centre is its text field, so open it by its arrow button when it has one.
            val arrow = withContext(edtAny) { combo.components.firstOrNull { it is AbstractButton && it.isShowing } }
            input.click(arrow ?: combo, MouseEvent.BUTTON1, 1, 0, null)
            host = waitForComboList(combo) ?: throw UiStepFailure("clicked ${describe(node)}, but no list of its items appeared")
        }
        val list = host
        val (index, rows) = withContext(edtAny) {
            val rows = UiRows.rows(list) ?: throw UiStepFailure("${describe(node)} has no rows; select works on lists, trees, tables and combo boxes")
            val index = step.index ?: UiRows.indexOf(rows, step.row!!)
            index to rows
        }
        if (index !in rows.indices) {
            throw UiStepFailure("no row ${step.row?.let { "\"$it\"" } ?: "#${step.index}"} in ${describe(node)}; rows: ${rows.take(20).joinToString(" | ")}${if (rows.size > 20) " | +${rows.size - 20}" else ""}")
        }
        val bounds = withContext(edtAny) { UiRows.reveal(list, index) } ?: throw UiStepFailure("row $index of ${describe(node)} has no bounds")
        val click = input.click(list, MouseEvent.BUTTON1, 1, 0, Point(bounds.x + minOf(bounds.width / 2, 40), bounds.y + bounds.height / 2))
        return "selected row \"${rows[index]}\"" + if (click.pressed == null) "; no component took the press" else ""
    }

    private suspend fun waitForComboList(combo: JComboBox<*>): JList<*>? {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < COMBO_POPUP_MS) {
            withContext(edtAny) { comboList(combo) }?.let { return it }
            delay(POLL_MS / 2)
        }
        return null
    }

    /** The item list of an open combo box popup: a showing list over the combo's own model. */
    private fun comboList(combo: JComboBox<*>): JList<*>? {
        val lists = Window.getWindows().filter { it.isShowing }.flatMap { w ->
            (w as? RootPaneContainer)?.rootPane?.let { UIUtil.findComponentsOfType(it, JList::class.java) }.orEmpty()
        }.filter { it.isShowing }
        return lists.firstOrNull { it.model === combo.model } ?: lists.lastOrNull()
    }

    private suspend fun closeStep(step: UiStep): String {
        val window = if (step.target != null) {
            val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
            withContext(edtAny) { node.component as? Window ?: SwingUtilities.getWindowAncestor(node.component) }
        } else {
            withContext(edtAny) { scopeWindows().firstOrNull { it !is Frame } }
        } ?: throw UiStepFailure("there is no dialog or popup to close")
        val closed = withContext(edtAny) {
            val inside = (window as? RootPaneContainer)?.rootPane
                ?.let { UIUtil.findComponentsOfType(it, JComponent::class.java).lastOrNull() }
            val dialog = inside?.let { DialogWrapper.findInstance(it) }
            val popup = inside?.let { PopupUtil.getPopupContainerFor(it) }
            val title = windowTitle(window)
            when {
                dialog != null -> {
                    ApplicationManager.getApplication().invokeLater({ dialog.doCancelAction() }, ModalityState.any())
                    "cancelled dialog \"$title\""
                }
                popup != null -> {
                    ApplicationManager.getApplication().invokeLater({ popup.cancel() }, ModalityState.any())
                    "closed popup ${WindowIdUtil.compute(window, window)}"
                }
                else -> throw UiStepFailure("window \"$title\" is not a dialog or popup the IDE can close")
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

    private suspend fun snapshotStep(step: UiStep): String {
        if (step.target == null) return "\n" + render(withBounds = true)
        val node = resolve(step.target!!, step.timeoutMs, requireEnabled = false)
        return withContext(edtAny) {
            val window = node.component as? Window ?: SwingUtilities.getWindowAncestor(node.component)
            val wrapper = node.copy(children = listOf(node))
            "\n" + UiSnapshotFormatter.format(
                header(window, UiModelResult(node, "subtree", null)),
                wrapper, { registry.refFor(it.component) }, maxNodes, withBounds = true,
            ).text
        }
    }

    /** Runs an input step and adds what it caused: IDE actions, windows opened or closed, the new focus owner. */
    private suspend fun withEffects(act: suspend () -> String): String {
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(AnActionListener.TOPIC, object : AnActionListener {
            override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                actions += ActionManager.getInstance().getId(action) ?: action.javaClass.name
            }
        })
        val windowsBefore = UiSettle.showingWindows()
        val line = try {
            val result = act()
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
                (windowsAfter - windowsBefore).forEach { add("opened ${describeWindow(it)}") }
                (windowsBefore - windowsAfter).forEach { add("closed ${describeWindow(it)}") }
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let { add("focus: ${describeComponent(it)}") }
            }.joinToString("; ")
        }
    }

    /** Finds [target], waiting up to [timeoutMs] for one showing (and, when asked, enabled) match. */
    private suspend fun resolve(target: UiTarget, timeoutMs: Long, requireEnabled: Boolean): UiNode {
        val started = TimeSource.Monotonic.markNow()
        var last: UiMatch
        while (true) {
            last = match(target)
            when (val m = last) {
                is UiMatch.One -> {
                    if (!requireEnabled || m.node.component.isEnabled) return m.node
                }
                is UiMatch.Many -> throw UiStepFailure(
                    "${m.matches.size} controls match; add nth, a class or a ref: " +
                        m.matches.take(10).joinToString("; ") { describe(it) }
                )
                is UiMatch.None -> Unit
            }
            if (started.elapsedNow().inWholeMilliseconds >= timeoutMs) break
            delay(POLL_MS)
        }
        return when (val m = last) {
            is UiMatch.One -> throw UiStepFailure("${describe(m.node)} stayed disabled for $timeoutMs ms")
            is UiMatch.None -> throw UiStepFailure(
                "no match after $timeoutMs ms" + modalNote() +
                    if (m.candidates.isEmpty()) "" else "; nearest: " + m.candidates.joinToString("; ") { describe(it) }
            )
            is UiMatch.Many -> error("unreachable")
        }
    }

    private suspend fun match(target: UiTarget): UiMatch {
        target.ref?.let { ref ->
            return when (val r = registry.resolve(ref)) {
                is UiRefResolution.Live -> withContext(edtAny) { UiMatch.One(FallbackUiWalker().build(r.component).copy(children = emptyList())) }
                is UiRefResolution.Stale -> throw UiStepFailure("ref $ref is stale: its control is no longer showing. Call steroid_ui without steps for fresh refs")
                is UiRefResolution.Unknown -> throw UiStepFailure("unknown ref $ref. Refs come from a steroid_ui snapshot")
            }
        }
        return withContext(edtAny) {
            val models = windowModels(scopeOnly = true)
            val xpaths = models.mapNotNull { it.model.xpath }
            val xpath: ((String) -> Set<Component>)? = if (xpaths.isEmpty()) null else { x -> xpaths.flatMap { it(x) }.toSet() }
            UiLocator.find(models.map { it.model.root }, target, xpath)
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

    private fun listedWindows(): List<Window> {
        if (windowId != null) {
            val c = findComponentByWindowId(windowId) ?: throw UiStepFailure("no IDE window has window_id $windowId")
            return listOf(c as? Window ?: SwingUtilities.getWindowAncestor(c) ?: throw UiStepFailure("window_id $windowId is not in a window"))
        }
        val frame = WindowManager.getInstance().getFrame(project) ?: throw UiStepFailure("project ${project.name} has no frame")
        return UiWindows.projectWindows(frame)
    }

    private fun windowModels(scopeOnly: Boolean): List<WindowModel> =
        (if (scopeOnly) scopeWindows() else listedWindows()).map { WindowModel(it, UiModel.build(it)) }

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
        return "$kind ${WindowIdUtil.compute(w, w)}" + (windowTitle(w)?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: "")
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
        private const val POLL_MS = 100L
        private const val COMBO_POPUP_MS = 1_500L
        private const val ACTION_QUIET_MS = 700L
        private const val ACTION_SETTLE_MS = 2_500L
    }
}
