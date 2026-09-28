/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import com.jonnyzzz.mcpSteroid.server.UiStep
import java.awt.Component
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The goto and run steps: a caret or a selection in the editor, and an IDE action run where the focus is. */
internal class UiEditorSteps(private val project: Project) {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /** The editor the last goto step focused, where a run step after it acts. */
    var gotoEditor: Component? = null
        private set

    suspend fun goto(step: UiStep): String {
        val path = step.file!!
        val file = withContext(Dispatchers.IO) { CodeLocation.findFile(project, path) } ?: throw UiStepFailure("file not found: $path")
        return withContext(edtAny) {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: throw UiStepFailure("$path has no text")
            val range = CodeLocation.resolve(document.text, step.line, step.column, step.symbol, step.text, step.nth)
            val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, range.first), true)
                ?: throw UiStepFailure("$path did not open in a text editor")
            editor.caretModel.moveToOffset(range.first)
            if (range.isEmpty()) editor.selectionModel.removeSelection() else editor.selectionModel.setSelection(range.first, range.last + 1)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
            gotoEditor = editor.contentComponent
            // From the document: a freshly opened editor can map an offset to a stale line.
            val line = document.getLineNumber(range.first)
            val where = "${shortPath(file)}:${line + 1}:${range.first - document.getLineStartOffset(line) + 1}"
            if (range.isEmpty()) "caret at $where"
            else "selected \"${document.getText(TextRange(range.first, range.last + 1)).lineSequence().first().take(60)}\" at $where"
        }
    }

    /**
     * Runs the action [UiStep.id] in the data context of [component]. The action's update runs the platform's way,
     * with a data context prepared off the EDT: refactoring actions resolve PSI in their update, which a plain
     * context would do on the EDT. Returns once the action ran, a window opened or an in-place template started,
     * because an action that shows a modal dialog does not return until the dialog closes.
     */
    suspend fun run(step: UiStep, component: Component, inplaceActive: suspend () -> Boolean): String {
        val id = step.id!!
        val manager = ActionManager.getInstance()
        val action = manager.getAction(id) ?: throw UiStepFailure(unknownId(id))
        val text = action.templatePresentation.text?.takeIf { it.isNotBlank() }
        val analysis = if (id in NEEDS_ANALYSIS) awaitAnalysis(component, step.timeoutMs) else ""
        val undo = if (id == UNDO || id == REDO) undoState(component, id) else null
        val ran = CompletableDeferred<Boolean>()
        val before = UiSettle.showingWindows()
        ApplicationManager.getApplication().invokeLater({
            // Not "now": that path makes up a key event for an action run without one, and on macOS an action the
            // application menu also offers, such as ShowSettings, disables itself for a key event
            // (ActionPlaces.isMacSystemMenuAction), since the menu handles the shortcut.
            manager.tryToExecute(action, null, component, PLACE, false)
                .doWhenDone { ran.complete(true) }
                .doWhenRejected(Runnable { ran.complete(false) })
        }, ModalityState.stateForComponent(component))
        val started = TimeSource.Monotonic.markNow()
        while (!ran.isCompleted && started.elapsedNow().inWholeMilliseconds < step.timeoutMs) {
            if (UiSettle.showingWindows() != before || inplaceActive()) break
            delay(POLL_MS)
        }
        if (ran.isCompleted && !ran.await()) throw UiStepFailure("$id is disabled here${text?.let { " (\"$it\")" } ?: ""}")
        return "${analysis}ran $id${(undo?.name ?: text)?.let { " (\"$it\")" } ?: ""}" + (undo?.let { undoNote(component, it) } ?: "")
    }

    /** What an Undo or Redo is about to do in the editor of [component]: its command's name, the text's stamp and the caret. */
    private class UndoState(val name: String?, val stamp: Long?, val caret: Int?)

    private suspend fun undoState(component: Component, id: String): UndoState = withContext(edtAny) {
        val editor = (component as? EditorComponentImpl)?.editor
        val fileEditor = editor?.virtualFile?.let { FileEditorManager.getInstance(project).getSelectedEditor(it) }
        val manager = UndoManager.getInstance(project)
        val name = if (id == UNDO) manager.getUndoActionNameAndDescription(fileEditor).first else manager.getRedoActionNameAndDescription(fileEditor).first
        UndoState(name?.replace("_", ""), editor?.document?.modificationStamp, editor?.caretModel?.offset)
    }

    /**
     * Where an Undo or Redo that changed no text left the caret. With the caret away from the change, the first Undo only
     * brings it back there, as it does for a person, and the next one undoes.
     */
    private suspend fun undoNote(component: Component, before: UndoState): String = withContext(edtAny) {
        val editor = (component as? EditorComponentImpl)?.editor ?: return@withContext ""
        if (editor.document.modificationStamp != before.stamp || editor.caretModel.offset == before.caret) return@withContext ""
        val pos = editor.caretModel.logicalPosition
        "; it changed no text but moved the caret to ${pos.line + 1}:${pos.column + 1}, where the change is: with the caret away from a change, " +
            "the first Undo brings it back, and the next one undoes"
    }

    /**
     * Waits for the highlighting of the editor that holds [component], up to [timeoutMs]: the context actions and quick
     * fixes come from it, so a popup shown while it runs lists only some of them. Returns what the wait took, for the report.
     */
    private suspend fun awaitAnalysis(component: Component, timeoutMs: Long): String {
        val editor = (component as? EditorComponentImpl)?.editor ?: return ""
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val finished = readAction {
                val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@readAction true
                (DaemonCodeAnalyzer.getInstance(project) as? DaemonCodeAnalyzerEx)?.isErrorAnalyzingFinished(file) ?: true
            }
            val took = started.elapsedNow().inWholeMilliseconds
            if (finished) return if (took < POLL_MS) "" else "waited $took ms for the editor's analysis; "
            if (took >= timeoutMs) return "the editor's analysis was still running after $took ms, so the popup may lack some items; "
            delay(POLL_MS)
        }
    }

    private fun unknownId(id: String): String {
        val similar = ActionManager.getInstance().getActionIdList("").filter { it.contains(id, ignoreCase = true) }
            .sortedWith(compareBy({ !it.startsWith(id, ignoreCase = true) }, { it.length }, { it })).take(5)
        return "unknown action id $id" + if (similar.isEmpty()) "" else "; similar ids: ${similar.joinToString()}"
    }

    private fun shortPath(file: VirtualFile): String = CodeLocation.shortPath(project, file)

    private companion object {
        const val PLACE = "steroid_ui"
        const val POLL_MS = 50L
        const val UNDO = "\$Undo"
        const val REDO = "\$Redo"
        /** The actions whose popups list what the editor's highlighting found. */
        val NEEDS_ANALYSIS = setOf("ShowIntentionActions", "GotoNextError", "GotoPreviousError", "ShowErrorDescription")
    }
}
