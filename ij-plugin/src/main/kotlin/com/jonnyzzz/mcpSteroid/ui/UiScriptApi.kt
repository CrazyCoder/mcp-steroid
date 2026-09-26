/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.server.UiAction
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiSteps
import com.jonnyzzz.mcpSteroid.server.UiTarget
import com.jonnyzzz.mcpSteroid.server.UiWaitCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Window
import kotlin.time.TimeSource

/**
 * The steroid_ui engine for scripts, as `ui` in `steroid_execute_code`. Targets and refs are the ones the tool
 * uses, so a ref from a steroid_ui snapshot works here. Every action returns the line the tool would report and
 * throws [UiStepFailure] when it cannot do what it asks.
 *
 * ```
 * val dialog = ui.open { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Editor") }
 * ui.select(ui.name("Settings categories"), "Keymap")
 * ui.click(ui.name("Cancel"))
 * ```
 */
class UiScriptApi(private val project: Project) {
    private fun session() = UiSession(project, windowId = null, maxNodes = SNAPSHOT_NODES)

    fun ref(ref: String) = UiTarget(ref = ref)
    fun name(name: String) = UiTarget(name = name)
    fun text(text: String) = UiTarget(text = text)
    fun cls(cls: String) = UiTarget(cls = cls)
    fun xpath(xpath: String) = UiTarget(xpath = xpath)

    /** The snapshot text of the project's showing windows, topmost first. */
    suspend fun snapshot(): String = session().render(withBounds = true)

    suspend fun find(target: UiTarget, timeoutMs: Long = UiSteps.DEFAULT_TIMEOUT_MS): Component = session().find(target, timeoutMs)

    suspend fun click(target: UiTarget, count: Int = 1, timeoutMs: Long = UiSteps.DEFAULT_TIMEOUT_MS) =
        step(UiStep(UiAction.CLICK, target, count = count, timeoutMs = timeoutMs))

    suspend fun hover(target: UiTarget) = step(UiStep(UiAction.HOVER, target))

    suspend fun type(text: String, target: UiTarget? = null) = step(UiStep(UiAction.TYPE, target, text = text))

    suspend fun fill(target: UiTarget, text: String) = step(UiStep(UiAction.FILL, target, text = text))

    suspend fun press(keys: String, target: UiTarget? = null) = step(UiStep(UiAction.PRESS, target, keys = keys))

    suspend fun check(target: UiTarget) = step(UiStep(UiAction.CHECK, target))

    suspend fun uncheck(target: UiTarget) = step(UiStep(UiAction.UNCHECK, target))

    suspend fun select(target: UiTarget, row: String) = step(UiStep(UiAction.SELECT, target, row = row))

    suspend fun close(target: UiTarget? = null) = step(UiStep(UiAction.CLOSE, target))

    suspend fun waitFor(target: UiTarget, timeoutMs: Long = UiSteps.DEFAULT_TIMEOUT_MS) =
        step(UiStep(UiAction.WAIT, target, condition = UiWaitCondition.VISIBLE, timeoutMs = timeoutMs))

    suspend fun waitForWindow(title: String, timeoutMs: Long = UiSteps.DEFAULT_TIMEOUT_MS) =
        step(UiStep(UiAction.WAIT, null, condition = UiWaitCondition.WINDOW, title = title, timeoutMs = timeoutMs))

    /**
     * Runs [block], which opens a window such as a dialog, in its own EDT task and returns the window it opened.
     * The script keeps running while a modal dialog is up, instead of waiting inside the dialog's event loop.
     */
    suspend fun open(timeoutMs: Long = OPEN_TIMEOUT_MS, block: () -> Unit): Window {
        val before = UiSettle.showingWindows()
        // The modality of the topmost open dialog, or non-modal: the block may open a dialog on top of one.
        val modality = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { ModalityState.current() }
        ApplicationManager.getApplication().invokeLater(block, modality)
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < timeoutMs) {
            delay(POLL_MS)
            val opened = UiSettle.showingWindows() - before
            if (opened.isNotEmpty()) {
                UiSettle.settle()
                return opened.last()
            }
        }
        throw UiStepFailure("no window opened within $timeoutMs ms")
    }

    private suspend fun step(step: UiStep): String = session().perform(step)

    companion object {
        private const val SNAPSHOT_NODES = 400
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 50L
    }
}
