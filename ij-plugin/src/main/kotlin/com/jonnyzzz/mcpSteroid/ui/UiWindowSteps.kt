/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.util.ui.UIUtil
import com.jonnyzzz.mcpSteroid.server.UiRestore
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiSteps
import com.jonnyzzz.mcpSteroid.server.UiTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowEvent
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.MenuSelectionManager
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.time.TimeSource

/** How long a closed window, or a dialog its default button closes, takes to go before a step says it stays. */
internal const val CLOSE_WAIT_MS = 3_000L

/** The steps on windows: close a window or menu, size a window, and move a splitter. */
internal class UiWindowSteps(private val ctx: UiStepContext) {
    private val project get() = ctx.project
    private val registry get() = ctx.registry
    private val edtAny get() = ctx.edtAny
    private suspend fun resolve(target: UiTarget, timeoutMs: Long, requireEnabled: Boolean) = ctx.resolve(target, timeoutMs, requireEnabled)
    private fun scopeWindows() = ctx.scopeWindows()
    private fun projectFrame() = ctx.projectFrame()
    private fun describe(node: UiNode) = ctx.describe(node)
    private fun describeWindow(w: Window) = ctx.describeWindow(w)
    private fun windowTitle(w: Window) = ctx.windowTitle(w)
    private fun undo(steps: List<JsonObject>) = ctx.undo(steps)

    suspend fun close(step: UiStep): String {
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
    suspend fun window(step: UiStep): String {
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

    /**
     * Moves a splitter's divider: the splitter the target is, or the nearest one above the control, whose pane holding
     * it gets the size, or for "fit", the one along which its content is cut. With a key and no target, puts back the
     * proportion a `JBSplitter` saves, on the one showing or in the saved settings.
     */
    suspend fun splitter(step: UiStep): String {
        step.key?.let { key -> return withContext(edtAny) { restoreSplitterKey(key, step.proportion!!) } }
        val node = try {
            resolve(step.target!!, step.timeoutMs, requireEnabled = false)
        } catch (e: UiStepFailure) {
            // A restore names the pane by ref with no wait: a pane whose window closed has nothing left to put back.
            if (step.timeoutMs == 0L && step.target?.ref != null) return "the splitter pane ${step.target!!.ref} is gone; nothing to put back"
            throw e
        }
        val line = withContext(edtAny) {
            val c = node.component
            val pane = (if (step.size == UiSteps.FIT) UiSplitters.cutAxes(c).firstNotNullOfOrNull { UiSplitters.paneOf(c, it) } else null)
                ?: UiSplitters.paneOf(c)
                ?: throw UiStepFailure("${describe(node)} is in no splitter; a window or tool window step sizes what holds it")
            val s = pane.splitter
            val restores = mutableListOf(UiRestore.step("splitter", "ref" to registry.refFor(pane.child), "size" to UiSplitters.size(pane), "timeout_ms" to 0))
            UiSplitters.savedKey(s)?.let { key -> restores += UiRestore.step("splitter", "key" to key, "proportion" to UiSplitters.proportion(s)) }
            undo(restores)
            val r = when {
                step.proportion != null -> UiSplitters.setProportion(s, step.proportion!!)
                step.size == UiSteps.FIT -> UiSplitters.setSize(pane, UiSplitters.fitSize(pane, c.takeUnless { it === s }))
                else -> UiSplitters.setSize(pane, step.size!!.toInt())
            }
            val axis = if (pane.axis == UiSplitters.Axis.HEIGHT) "high" else "wide"
            val paneNode = FallbackUiWalker().leaf(pane.child)
            // A console or an editor wants any size; only content the other pane now cuts calls for a larger window.
            val refOf = { n: UiNode -> registry.refFor(n.component) }
            val otherCuts = UiSplitters.others(pane).flatMap { UiLayout.cuts(FallbackUiWalker().build(it), refOf) }
            "moved the divider of ${UiComponentFacts.simpleClassName(s)} [ref=${registry.refFor(s)}]: the pane with ${describe(paneNode)} is ${r.after} px $axis, " +
                "was ${r.before} px (proportion ${UiSplitters.format(r.proportionAfter)}, was ${UiSplitters.format(r.proportionBefore)})" +
                (r.heldBack?.let { "; held back: $it" } ?: "") +
                (if (otherCuts.isNotEmpty()) "; the other pane now cuts content (${otherCuts.first().what}), so a larger window gives both room: " +
                    UiLayout.windowStep(SwingUtilities.getWindowAncestor(s)) else "")
        }
        UiSettle.barrier()
        return line
    }

    /** Puts back proportion [share] under [key]: on the `JBSplitter` showing with that key, else in the saved settings. EDT. */
    private fun restoreSplitterKey(key: String, share: Double): String {
        val showing = Window.getWindows().asSequence().filter { it.isShowing }
            .flatMap { UIUtil.uiTraverser(it).asSequence() }
            .firstOrNull { it is com.intellij.ui.JBSplitter && it.isShowing && UiSplitters.savedKey(it) == key } as? com.intellij.ui.JBSplitter
        if (showing != null) {
            showing.proportion = share.toFloat()
            return "the splitter saved as $key is back to ${UiSplitters.format(share)}"
        }
        // As JBSplitter stores it, a float in text form, which it reads with getFloat.
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(key, share.toFloat().toString())
        return "the saved proportion of $key is back to ${UiSplitters.format(share)}"
    }

    /** The step that gives the IDE window [frame] its size now: maximized, or its width and height. EDT. */
    private fun frameSize(frame: Frame): JsonObject {
        val target = "class" to frame.javaClass.simpleName
        return if (frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) UiRestore.step("window", target, "maximize" to true)
        else UiRestore.step("window", target, "width" to frame.width, "height" to frame.height)
    }
}
