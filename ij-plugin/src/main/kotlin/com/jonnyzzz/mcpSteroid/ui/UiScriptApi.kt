/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.execution.McpUi
import com.jonnyzzz.mcpSteroid.execution.UiQuery
import com.jonnyzzz.mcpSteroid.server.UiAction
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiTarget
import com.jonnyzzz.mcpSteroid.server.UiWaitCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.TimeSource

/**
 * [McpUi] on the steroid_ui engine. Queries and refs are the ones the tool uses, so a ref from a steroid_ui
 * snapshot works here and the other way round.
 */
class UiScriptApi(private val project: Project) : McpUi {
    private fun session() = UiSession(project, windowId = null, maxNodes = SNAPSHOT_NODES)

    override suspend fun snapshot(): String = session().render(withBounds = true)

    override suspend fun find(target: UiQuery, timeoutMs: Long): Component = session().find(target.toTarget(), timeoutMs)

    override suspend fun click(target: UiQuery, count: Int, timeoutMs: Long) =
        step(UiStep(UiAction.CLICK, target.toTarget(), count = count, timeoutMs = timeoutMs))

    override suspend fun hover(target: UiQuery) = step(UiStep(UiAction.HOVER, target.toTarget()))

    override suspend fun type(text: String, target: UiQuery?) = step(UiStep(UiAction.TYPE, target?.toTarget(), text = text))

    override suspend fun fill(target: UiQuery, text: String) = step(UiStep(UiAction.FILL, target.toTarget(), text = text))

    override suspend fun press(keys: String, target: UiQuery?) = step(UiStep(UiAction.PRESS, target?.toTarget(), keys = keys))

    override suspend fun check(target: UiQuery) = step(UiStep(UiAction.CHECK, target.toTarget()))

    override suspend fun uncheck(target: UiQuery) = step(UiStep(UiAction.UNCHECK, target.toTarget()))

    override suspend fun select(target: UiQuery, row: String) = step(UiStep(UiAction.SELECT, target.toTarget(), row = row))

    override suspend fun close(target: UiQuery?) = step(UiStep(UiAction.CLOSE, target?.toTarget()))

    override suspend fun waitFor(target: UiQuery, timeoutMs: Long) =
        step(UiStep(UiAction.WAIT, target.toTarget(), condition = UiWaitCondition.VISIBLE, timeoutMs = timeoutMs))

    override suspend fun waitForWindow(title: String, timeoutMs: Long) =
        step(UiStep(UiAction.WAIT, null, condition = UiWaitCondition.WINDOW, title = title, timeoutMs = timeoutMs))

    override suspend fun open(timeoutMs: Long, block: () -> Unit): Window {
        val before = UiSettle.showingWindows()
        // The modality of the topmost open dialog, or non-modal: the block may open a dialog on top of one.
        val modality = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { ModalityState.current() }
        val thrown = AtomicReference<Throwable>()
        ApplicationManager.getApplication().invokeLater({
            try {
                block()
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }, modality)
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < timeoutMs) {
            delay(POLL_MS)
            thrown.get()?.let { throw UiStepFailure("the block failed before a window opened: $it").apply { initCause(it) } }
            val opened = UiSettle.showingWindows() - before
            if (opened.isNotEmpty()) {
                UiSettle.settle()
                return opened.last()
            }
        }
        throw UiStepFailure("no window opened within $timeoutMs ms")
    }

    private suspend fun step(step: UiStep): String = session().perform(step)

    private fun UiQuery.toTarget() = UiTarget(ref = ref, name = name, text = text, cls = cls, xpath = xpath, nth = nth)

    companion object {
        private const val SNAPSHOT_NODES = 400
        private const val POLL_MS = 50L
    }
}
