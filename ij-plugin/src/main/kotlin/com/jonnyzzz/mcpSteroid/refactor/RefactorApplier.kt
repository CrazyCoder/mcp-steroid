/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.refactor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.util.diff.Diff
import com.jonnyzzz.mcpSteroid.ui.CodeLocation
import com.jonnyzzz.mcpSteroid.ui.UiModel
import com.jonnyzzz.mcpSteroid.ui.UiSettle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.Window
import java.awt.event.WindowEvent
import kotlin.time.TimeSource

/**
 * Applies a change and reports it: the files it changed with lines added and removed, saved to disk. A dialog that
 * opens meanwhile, such as a refactoring's conflicts or "usages detected" dialog, is read, cancelled, and returned
 * as the failure, so the call never waits on a dialog nobody answers.
 */
internal class RefactorApplier(private val project: Project) {

    /** Runs [block] on the EDT under write intent, as a refactoring processor expects, watching for dialogs. */
    suspend fun apply(title: String, block: () -> Unit): String = collectChanges(title) {
        val before = UiSettle.showingWindows()
        val done = CompletableDeferred<Throwable?>()
        ApplicationManager.getApplication().invokeLater({
            try {
                // One command with our name, so the user's Edit > Undo says whose change it takes back.
                WriteIntentReadAction.run { CommandProcessor.getInstance().executeCommand(project, block, UNDO_PREFIX + title, null) }
                done.complete(null)
            } catch (e: Throwable) {
                done.complete(e)
            }
        }, ModalityState.nonModal())
        val started = TimeSource.Monotonic.markNow()
        while (!done.isCompleted) {
            val dialog = (UiSettle.showingWindows() - before).firstOrNull { it is Dialog && it.isModal }
            if (dialog != null) {
                // A conflicts dialog fills its tree and status line after it shows.
                UiSettle.settle(quietMs = 300, maxMs = 3_000)
                val text = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { cancel(dialog) }
                done.await()
                throw RefactorFailure("$title stopped at a dialog, which was cancelled; nothing was changed:\n$text")
            }
            if (started.elapsedNow().inWholeMilliseconds > TIMEOUT_MS) throw RefactorFailure("$title did not finish within ${TIMEOUT_MS / 1000} s")
            delay(POLL_MS)
        }
        done.await()?.let { throw it }
    }

    /** Runs [block], the change named [title], and lists the documents it changed, then saves those and no others. */
    suspend fun collectChanges(title: String, block: suspend () -> Unit): String {
        val originals = LinkedHashMap<Document, String>()
        val disposable = Disposer.newDisposable("steroid_refactor changes")
        try {
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
                override fun beforeDocumentChange(event: DocumentEvent) {
                    originals.putIfAbsent(event.document, event.document.text)
                }
            }, disposable)
            block()
        } finally {
            Disposer.dispose(disposable)
        }
        return withContext(Dispatchers.EDT) {
            val changed = mutableListOf<Document>()
            val lines = originals.mapNotNull { (document, before) ->
                // Not the in-memory copies a ModCommand fix edits before it applies to the real file.
                val file = FileDocumentManager.getInstance().getFile(document)?.takeIf { it.isInLocalFileSystem } ?: return@mapNotNull null
                val after = document.text
                if (after == before) return@mapNotNull null
                changed += document
                var added = 0
                var removed = 0
                val changes = runCatching { Diff.buildChanges(before, after) }.getOrNull()
                generateSequence(changes) { it.link }.forEach { added += it.inserted; removed += it.deleted }
                "${CodeLocation.shortPath(project, file)} +$added -$removed"
            }
            // Only what the change touched: the user's own unsaved edits elsewhere stay unsaved.
            changed.forEach { FileDocumentManager.getInstance().saveDocument(it) }
            if (lines.isEmpty()) "no file changed"
            else "changed ${lines.size} file(s), saved; Edit > Undo \"$UNDO_PREFIX$title\" takes it back:\n" + lines.joinToString("\n")
        }
    }

    /**
     * The dialog's messages, then its Cancel. Reads the UI model the steroid_ui snapshot shows: labels, and the rows
     * of its lists and trees without the "N results" grouping rows a conflicts tree has. Editors and buttons are
     * left out. EDT.
     */
    private fun cancel(dialog: Window): String {
        val title = (dialog as? Dialog)?.title.orEmpty()
        val texts = UiModel.build(dialog).root.walk().flatMap { node ->
            val rows = node.rows
            when {
                rows != null -> rows.rows.asSequence().map { it.text }.filterNot { GROUPING_ROW.containsMatchIn(it) }
                node.interactive || "Editor" in node.className || "Gutter" in node.className -> emptySequence()
                else -> (node.text + listOfNotNull(node.name)).asSequence()
            }
        }.map { it.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && it != title && it !in CHROME }.distinct().toList()
            // A conflict is often both a tree row and the pieces of a coloured label: keep the whole message.
            .let { all -> all.filter { text -> all.none { other -> other != text && text in other } } }
        val wrapper = DialogWrapper.findInstance(dialog.focusOwner ?: dialog.components.firstOrNull())
        if (wrapper != null) wrapper.close(DialogWrapper.CANCEL_EXIT_CODE)
        else dialog.dispatchEvent(WindowEvent(dialog, WindowEvent.WINDOW_CLOSING))
        return ("\"$title\": " + texts.joinToString(" | ")).take(MAX_TEXT)
    }

    companion object {
        /** Starts the name of every change steroid_refactor makes, as Edit > Undo shows it. */
        const val UNDO_PREFIX = "MCP Steroid: "
        private const val POLL_MS = 50L
        private const val TIMEOUT_MS = 60_000L
        private const val MAX_TEXT = 2_000
        private val GROUPING_ROW = Regex("\\d+ results?$")
        private val CHROME = setOf("Application icon", "Frame Header", "Action Toolbar")
    }
}

