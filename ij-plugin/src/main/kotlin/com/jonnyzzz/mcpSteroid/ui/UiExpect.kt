/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.notification.ActionCenter
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.jonnyzzz.mcpSteroid.freeze.IdeBanners
import com.jonnyzzz.mcpSteroid.freeze.IdeErrors
import com.jonnyzzz.mcpSteroid.freeze.IdeMemory
import com.jonnyzzz.mcpSteroid.server.UiExpectState
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Component
import java.awt.Dialog
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.lang.management.ManagementFactory
import java.util.Collections
import javax.swing.AbstractButton
import javax.swing.JTree
import javax.swing.SwingUtilities
import kotlin.time.TimeSource

/**
 * The expect step: a check that the IDE shows what it should, retried until it holds or its timeout passes, as
 * Playwright's web-first assertions do. The UI updates asynchronously, so a check made once right after an action
 * would fail on a dialog that is still filling in. With `not`, the check is retried until it does not hold.
 */
internal class UiExpect(
    private val project: Project,
    private val startedMs: Long,
    private val notifications: UiNotificationLog,
    private val match: suspend (UiTarget) -> UiMatch,
    private val describe: (UiNode) -> String,
) {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()
    private val editors = UiEditors(project)

    /** What a check wanted, in words, and what the IDE showed. */
    private class Check(val holds: Boolean, val wanted: String, val actual: String)

    suspend fun run(step: UiStep): String {
        val started = TimeSource.Monotonic.markNow()
        // An error or a notification arrives some time after what caused it: let the IDE settle before a check that
        // none arrived, which would otherwise pass at once.
        if (step.negate && (step.error != null || step.notification != null || step.log != null)) UiSettle.settle(quietMs = NEGATIVE_QUIET_MS, maxMs = step.timeoutMs)
        val not = if (step.negate) "not " else ""
        var last: Check
        while (true) {
            last = check(step)
            if (last.holds != step.negate) return "${not}${last.wanted} after ${started.elapsedNow().inWholeMilliseconds} ms: ${last.actual}"
            if (started.elapsedNow().inWholeMilliseconds >= step.timeoutMs) break
            delay(POLL_MS)
        }
        throw UiStepFailure("expected ${not}${last.wanted} for ${step.timeoutMs} ms, but ${last.actual}")
    }

    private suspend fun check(step: UiStep): Check = when {
        step.target != null -> control(step, step.target!!)
        step.title != null -> window(step.title!!, step.state)
        step.file != null -> file(step)
        step.notification != null -> notification(step.notification!!)
        step.banner != null -> banner(step.banner!!)
        step.editor != null -> editor(step.editor!!, step.state)
        step.log != null -> log(step.log!!)
        step.memoryMetric != null -> memory(step.memoryMetric!!, step.below!!)
        else -> error(step.error!!)
    }

    /**
     * A memory figure of this side under [below], in MB for the heap. heap_after_gc runs a full GC first, as a click on
     * the memory indicator does, so the figure is what the heap holds live rather than what the last GC left; the GC
     * runs at most once per [GC_EVERY_MS], since a failing check polls.
     */
    private suspend fun memory(metric: String, below: Long): Check {
        val heapMb = { ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024) }
        val (label, value, unit) = when (metric) {
            // With explicit GC disabled, System.gc() does nothing, and the figure is the heap in use as it is.
            "heap_after_gc" -> if (explicitGcDisabled) Triple("the heap in use (the IDE runs with -XX:+DisableExplicitGC, so no full GC ran)", heapMb(), " MB") else {
                val now = System.currentTimeMillis()
                if (now - lastGcMs >= GC_EVERY_MS) {
                    @Suppress("ExplicitGarbageCollectionCall") // The check measures the live heap, which only a full GC shows.
                    withContext(Dispatchers.IO) { System.gc() }
                    lastGcMs = System.currentTimeMillis()
                }
                Triple("the heap in use after a full GC", heapMb(), " MB")
            }
            "heap" -> Triple("the heap in use", heapMb(), " MB")
            "threads" -> Triple("the thread count", ManagementFactory.getThreadMXBean().threadCount.toLong(), "")
            else -> Triple("overloaded-GC signals in the last 15 min", (IdeMemory.getInstanceOrNull()?.recentSignals() ?: 0).toLong(), "")
        }
        return Check(value < below, "$label under $below$unit", "$label is $value$unit")
    }

    /** An editor of a file that this side shows: visible, focused, or with is=hidden, none showing. */
    private suspend fun editor(file: String, state: UiExpectState?): Check {
        val shown = editors.shown(file)
        val actual = when {
            shown.focused -> "its editor shows and has the focus"
            shown.visible -> "its editor shows without the focus"
            shown.open.isEmpty() -> "no editor is open"
            else -> "no editor of it shows; open: ${shown.open.take(10).joinToString()}"
        }
        return when (state) {
            UiExpectState.FOCUSED -> Check(shown.focused, "the editor of $file focused", actual)
            UiExpectState.HIDDEN -> Check(!shown.visible, "no editor of $file showing", actual)
            else -> Check(shown.visible, "the editor of $file showing", actual)
        }
    }

    /** A line of this side's idea.log, written since the run started, that contains [text]. */
    private suspend fun log(text: String): Check {
        val (lines, read) = UiLogs.linesSince(startedMs, text)
        return Check(
            lines.isNotEmpty(),
            "a log line with \"$text\"",
            if (lines.isEmpty()) "none of the $read log entries since the run started has it"
            else "${lines.size} line(s), the last: ${lines.last().lineSequence().first().take(300)}",
        )
    }

    /** A banner above one of the project's open editors, of any severity, whose text contains [text]. */
    private suspend fun banner(text: String): Check {
        val banners = IdeBanners.read(project, all = true)
        return Check(
            banners.any { it.text.contains(text, ignoreCase = true) },
            "a banner above an editor with \"$text\"",
            if (banners.isEmpty()) "no open editor shows a banner"
            else "banners: " + banners.take(5).joinToString("; ") { "${it.file}: ${it.text}" + if (it.links.isEmpty()) "" else " [${it.links.joinToString(" | ")}]" },
        )
    }

    private suspend fun control(step: UiStep, target: UiTarget): Check {
        val m = match(target)
        step.expectCount?.let { wanted ->
            val n = when (m) {
                is UiMatch.None -> 0
                is UiMatch.One -> 1
                is UiMatch.Many -> m.matches.size
            }
            return Check(n == wanted, "$wanted control(s) matching $target", "$n match")
        }
        val node = when (m) {
            is UiMatch.None -> {
                val nearest = if (m.candidates.isEmpty()) "" else "; nearest: " + m.candidates.take(3).joinToString("; ") { describe(it) }
                return Check(step.state == UiExpectState.HIDDEN, what(step, target), "no control matches$nearest")
            }
            // Ambiguous either way: a negated check would pass for the wrong control.
            is UiMatch.Many -> throw UiStepFailure(
                "${m.matches.size} controls match; add nth, a class or a ref: " + m.matches.take(10).joinToString("; ") { describe(it) }
            )
            is UiMatch.One -> m.node
        }
        return withContext(edtAny) {
            val c = node.component
            val shown = describe(node)
            if (step.row != null || step.index != null) return@withContext row(step, node)
            when {
                step.value != null -> {
                    val actual = node.value ?: node.text.joinToString(" ")
                    Check(actual == step.value, "$shown with value \"${step.value}\"", "it shows \"${actual.take(200)}\"")
                }
                step.contains != null || step.matches != null -> {
                    val texts = listOfNotNull(node.value, node.name, node.label) + node.text
                    val holds = if (step.contains != null) texts.any { it.contains(step.contains!!) }
                    else Regex(step.matches!!).let { r -> texts.any { r.containsMatchIn(it) } }
                    val wanted = if (step.contains != null) "containing \"${step.contains}\"" else "matching /${step.matches}/"
                    Check(holds, "$shown $wanted", "it shows ${texts.distinct().joinToString(" | ") { "\"${it.take(120)}\"" }.ifEmpty { "no text" }}")
                }
                else -> when (val state = step.state ?: UiExpectState.VISIBLE) {
                    UiExpectState.VISIBLE -> Check(true, "$target visible", "$shown shows")
                    UiExpectState.HIDDEN -> Check(false, "$target hidden", "$shown shows")
                    UiExpectState.ENABLED -> Check(c.isEnabled, "$shown enabled", if (c.isEnabled) "it is enabled" else "it is disabled")
                    UiExpectState.DISABLED -> Check(!c.isEnabled, "$shown disabled", if (c.isEnabled) "it is enabled" else "it is disabled")
                    UiExpectState.CHECKED, UiExpectState.UNCHECKED -> {
                        val toggle = c as? AbstractButton ?: throw UiStepFailure("$shown is not a checkbox or toggle")
                        val wanted = state == UiExpectState.CHECKED
                        Check(toggle.isSelected == wanted, "$shown ${state.wire}", if (toggle.isSelected) "it is checked" else "it is unchecked")
                    }
                    UiExpectState.FOCUSED -> {
                        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                        val holds = owner != null && (owner === c || SwingUtilities.isDescendingFrom(owner, c)) || UiState.FOCUSED in node.states
                        Check(holds, "$shown focused", "the focus is on ${owner?.let { UiComponentFacts.simpleClassName(it) } ?: "nothing"}")
                    }
                    UiExpectState.EDITABLE -> {
                        val holds = UiState.EDITABLE in node.states
                        Check(holds, "$shown editable", if (holds) "it is editable" else "it is read-only")
                    }
                    else -> error("row state without a row")
                }
            }
        }
    }

    /** The row check of an expect step on a list, tree, table or tabbed pane. EDT. */
    private fun row(step: UiStep, node: UiNode): Check {
        val c = node.component
        val rows = UiRows.rows(c) ?: throw UiStepFailure("${describe(node)} has no rows")
        val name = step.row?.let { "row \"$it\"" } ?: "row #${step.index}"
        val index = step.index ?: UiRows.find(c, rows, step.row!!)
        val shown = "$name of ${describe(node)}"
        if (index !in rows.indices) {
            val some = rows.take(10).withIndex().joinToString("; ") { (i, r) -> "#$i $r" }
            return Check(false, "$shown ${step.state?.wire ?: "present"}", "there is no such row; rows: $some")
        }
        val tree = c as? JTree
        if (step.value != null || step.contains != null || step.matches != null) return cells(step, c, index, shown)
        return when (step.state) {
            null -> Check(true, "$shown present", "row #$index \"${rows[index].take(80)}\"")
            UiExpectState.SELECTED -> UiRows.isSelected(c, index).let { Check(it, "$shown selected", if (it) "it is selected" else "it is not selected") }
            UiExpectState.EXPANDED, UiExpectState.COLLAPSED -> {
                tree ?: throw UiStepFailure("${describe(node)} is not a tree; only tree rows expand")
                val expanded = tree.isExpanded(index)
                Check(expanded == (step.state == UiExpectState.EXPANDED), "$shown ${step.state!!.wire}", if (expanded) "it is expanded" else "it is collapsed")
            }
            else -> error("control state on a row")
        }
    }

    /** The value check of a table row: its cells after the first, which a value equals one of. EDT. */
    private fun cells(step: UiStep, c: Component, index: Int, shown: String): Check {
        val cells = UiRows.cells(c, index)
        if (cells.isEmpty()) throw UiStepFailure("$shown has no cells beside its text; value, contains and matches check a table row's other cells")
        val actual = "it shows ${cells.joinToString(" | ") { "\"${it.take(80)}\"" }}"
        return when {
            step.value != null -> Check(step.value in cells, "$shown with value \"${step.value}\"", actual)
            step.contains != null -> Check(cells.any { it.contains(step.contains!!) }, "$shown containing \"${step.contains}\"", actual)
            else -> Check(Regex(step.matches!!).let { r -> cells.any { r.containsMatchIn(it) } }, "$shown matching /${step.matches}/", actual)
        }
    }

    private suspend fun window(title: String, state: UiExpectState?): Check = withContext(edtAny) {
        val titles = Window.getWindows().filter { it.isShowing }.mapNotNull { (it as? Frame)?.title ?: (it as? Dialog)?.title }.filter { it.isNotBlank() }
        val showing = titles.any { it.contains(title) }
        val listed = "showing: " + titles.joinToString { "\"$it\"" }.ifEmpty { "no titled window" }
        if (state == UiExpectState.HIDDEN) Check(!showing, "no window titled \"$title\"", listed)
        else Check(showing, "a window titled \"$title\"", listed)
    }

    private suspend fun file(step: UiStep): Check {
        val path = step.file!!
        val file = withContext(Dispatchers.IO) { CodeLocation.findFile(project, path) } ?: return Check(false, "file $path", "the file is not found")
        if (step.caret != null) {
            return withContext(edtAny) {
                val editor = FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<TextEditor>().firstOrNull()?.editor
                    ?: return@withContext Check(false, "the caret at ${step.caret} in $path", "the file is not open in an editor")
                val pos = editor.caretModel.logicalPosition
                val actual = "${pos.line + 1}:${pos.column + 1}"
                Check(actual == step.caret, "the caret at ${step.caret} in $path", "it is at $actual")
            }
        }
        val text = readAction { FileDocumentManager.getInstance().getDocument(file)?.let { doc ->
            val line = step.line
            when {
                line == null -> doc.text
                line > doc.lineCount -> null
                else -> doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line - 1), doc.getLineEndOffset(line - 1)))
            }
        } }
        val where = step.line?.let { "line $it of $path" } ?: path
        text ?: return Check(false, where, if (step.line != null) "the file has fewer lines" else "the file has no text")
        val excerpt = "it reads \"${text.take(200).replace("\n", "\\n")}\"" + if (text.length > 200) "…" else ""
        return when {
            step.value != null -> Check(text == step.value, "$where reading \"${step.value}\"", excerpt)
            step.contains != null -> Check(text.contains(step.contains!!), "$where containing \"${step.contains}\"", excerpt)
            else -> Check(Regex(step.matches!!).containsMatchIn(text), "$where matching /${step.matches}/", excerpt)
        }
    }

    private suspend fun notification(text: String): Check {
        val seen = notifications.since(startedMs) + withContext(edtAny) {
            ActionCenter.getNotifications(project).map { UiNotificationLog.describe(it) }
        }
        val all = seen.distinct()
        return Check(
            all.any { it.contains(text, ignoreCase = true) },
            "a notification with \"$text\"",
            if (all.isEmpty()) "no notification since the run started" else "notifications: " + all.take(5).joinToString("; ") { "\"${it.take(120)}\"" },
        )
    }

    private fun error(text: String): Check {
        val errors = IdeErrors.getInstanceOrNull()?.since(startedMs).orEmpty()
        val wanted = if (text.isEmpty()) "an IDE error" else "an IDE error with \"$text\""
        return Check(
            errors.any { it.summary.contains(text, ignoreCase = true) },
            wanted,
            if (errors.isEmpty()) "no IDE error was logged since the run started"
            else "IDE errors: " + errors.take(3).joinToString("; ") { it.summary.take(160) } + if (errors.size > 3) "; +${errors.size - 3} more" else "",
        )
    }

    private fun what(step: UiStep, target: UiTarget): String = when {
        step.state != null -> "$target ${step.state!!.wire}"
        step.value != null -> "$target with value \"${step.value}\""
        step.contains != null -> "$target containing \"${step.contains}\""
        step.matches != null -> "$target matching /${step.matches}/"
        step.row != null || step.index != null -> "$target with ${step.row?.let { "row \"$it\"" } ?: "row #${step.index}"}"
        else -> "$target visible"
    }

    private companion object {
        const val POLL_MS = 100L
        const val NEGATIVE_QUIET_MS = 1_000L
        const val GC_EVERY_MS = 2_000L

        /** When a heap_after_gc check last ran a full GC, in this process. */
        @Volatile
        var lastGcMs = 0L

        val explicitGcDisabled: Boolean by lazy { "-XX:+DisableExplicitGC" in ManagementFactory.getRuntimeMXBean().inputArguments }
    }
}

/** The notifications the IDE shows while a steroid_ui call runs, as `title: content` in plain text. */
internal class UiNotificationLog(project: Project, parent: Disposable) {
    private val shown = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())

    init {
        project.messageBus.connect(parent).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                shown += System.currentTimeMillis() to describe(notification)
            }
        })
    }

    fun since(ms: Long): List<String> = synchronized(shown) { shown.filter { it.first >= ms }.map { it.second } }

    companion object {
        fun describe(n: Notification): String =
            listOf(n.title, n.subtitle.orEmpty(), n.content).map { StringUtil.removeHtmlTags(it, true).trim() }.filter { it.isNotEmpty() }.joinToString(": ")
    }
}
