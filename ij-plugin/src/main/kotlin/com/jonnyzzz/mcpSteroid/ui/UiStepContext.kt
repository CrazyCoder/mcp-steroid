/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.server.UiForwardedStep
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiTarget
import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import javax.swing.SwingUtilities

/** What the step classes take from the [UiSession] that runs them: its project, the call's state, and target resolution. */
internal interface UiStepContext {
    val project: Project
    val registry: UiRefRegistry
    /** In a JetBrains Client, runs a step on the backend and returns its report; null elsewhere. */
    val forward: (suspend (UiStep) -> UiForwardedStep.Report)?
    val edtAny: kotlin.coroutines.CoroutineContext
    /** Where a screenshot step saves its picture: the call's execution folder. */
    val artifacts: Path?
    /** The folder of the replayed scenario file, which a screenshot's relative `out` is relative to. */
    val scenarioDir: Path?
    /** The window and screen point of the call's last click. */
    val lastClick: Pair<Window, Point>?
    suspend fun resolve(target: UiTarget, timeoutMs: Long, requireEnabled: Boolean): UiNode
    suspend fun match(target: UiTarget): UiMatch
    suspend fun pickRow(node: UiNode, step: UiStep): UiRowPick?
    fun scopeWindows(): List<Window>
    fun describe(node: UiNode): String
    fun describeWindow(w: Window): String
    fun windowTitle(w: Window): String?
    fun projectFrame(): Window
    /** Records the steps that put back what the running step changes. */
    fun undo(steps: List<JsonObject>)
    suspend fun applyFix(fix: String): String
}

/** [area], in [c]'s coordinates, on screen. EDT. */
internal fun onScreen(c: Component, area: Rectangle): Rectangle = Rectangle(area).apply { translate(c.locationOnScreen.x, c.locationOnScreen.y) }

/** The window that holds [c], or [c] itself when it is one. EDT. */
internal fun windowOf(c: Component): Window? = c as? Window ?: SwingUtilities.getWindowAncestor(c)

/** A row a step picked, and the tree rows it expanded to reach it. */
internal class UiRowPick(val index: Int, val text: String, val expanded: List<String>) {
    fun expandedNote() = if (expanded.isEmpty()) "" else "expanded ${expanded.joinToString(", ") { "\"$it\"" }}; "
}

/** How often a step looks again while it waits. */
internal const val POLL_MS = 100L
