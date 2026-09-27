/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.client.ClientKind
import com.intellij.openapi.client.ClientSessionsManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.ClientFileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.jonnyzzz.mcpSteroid.server.UiEditorSide
import com.jonnyzzz.mcpSteroid.server.UiEditorState
import com.jonnyzzz.mcpSteroid.server.UiOpenFile
import com.jonnyzzz.mcpSteroid.server.split.SplitRole
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.KeyboardFocusManager
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * The editors each side of the IDE has open: the regular IDE's or JetBrains Client's own, and on a Remote Development
 * backend also its record of every Client session, which a Client's editors can drift from. Also the facts of one
 * project file: its type, language and editor providers.
 */
internal class UiEditors(private val project: Project) {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /**
     * This process's own open editors. On a Remote Development backend, a step a JetBrains Client sent runs under the
     * Client's id, where the editor manager is that Client session's, so the host's own editors are read under the
     * host's id.
     */
    suspend fun local(): UiEditorSide = withContext(if (currentSplitRole() == SplitRole.BACKEND) edtAny + ClientId.localId.asContextElement() else edtAny) {
        val manager = FileEditorManager.getInstance(project)
        val windows = (manager as? FileEditorManagerEx)?.windows.orEmpty()
        val files = manager.openFiles.map { file ->
            UiOpenFile(pathOf(file), windows.count { it.getComposite(file) != null }.coerceAtLeast(1))
        }
        val label = when (currentSplitRole()) {
            SplitRole.FRONTEND -> CLIENT
            SplitRole.BACKEND -> "the backend's own editors"
            else -> "the IDE"
        }
        UiEditorSide(label, files, manager.selectedFiles.firstOrNull()?.let(::pathOf))
    }

    /** On a Remote Development backend, the editors it records for each JetBrains Client session; empty elsewhere. */
    suspend fun sessions(): List<UiEditorSide> {
        if (currentSplitRole() != SplitRole.BACKEND) return emptyList()
        val sessions = ClientSessionsManager.getProjectSessions(project, ClientKind.REMOTE)
        return withContext(edtAny) {
            sessions.map { session ->
                val manager = session.service<ClientFileEditorManager>()
                val files = manager.getAllFiles().map { UiOpenFile(pathOf(it), manager.getAllComposites(it).size) }
                UiEditorSide(UiEditorState.sessionLabel(session.clientId.value.take(SESSION_ID)), files, manager.getSelectedFile()?.let(::pathOf), client = true)
            }
        }
    }

    /** Whether an editor of [wanted], a project-relative path or a file name, shows here, and has the focus. */
    suspend fun shown(wanted: String): Shown = withContext(edtAny) {
        val manager = FileEditorManager.getInstance(project)
        val editors = manager.allEditors.filter { editor -> editor.file?.let { matches(it, wanted) } == true && editor.component.isShowing }
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val focused = owner != null && editors.any { SwingUtilities.isDescendingFrom(owner, it.component) }
        Shown(editors.isNotEmpty(), focused, manager.openFiles.map(::pathOf))
    }

    class Shown(val visible: Boolean, val focused: Boolean, val open: List<String>)

    /** A project file's type, language, size and editor providers, and on a backend how many editors each Client session has of it. */
    suspend fun facts(path: String): String {
        val file = if (Path.of(path).isAbsolute) LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', '/'))
        else project.guessProjectDir()?.findFileByRelativePath(path)
        file ?: throw UiStepFailure("no file $path in the project")
        val (type, language) = readAction { file.fileType.name to (file.fileType as? LanguageFileType)?.language?.id }
        val providers = FileEditorProviderManager.getInstance().getProviderList(project, file).map { it.editorTypeId }
        val hosts = hosts(file)
        return buildString {
            append("file ").append(path).append(": type ").append(type)
            language?.let { append(", language ").append(it) }
            append(", ").append(file.length).append(" bytes; editors: ").append(providers.joinToString().ifEmpty { "none" })
            if (hosts.isNotEmpty()) append("; ").append(hosts.joinToString("; "))
        }
    }

    /** On a Remote Development backend, the editors of [file] each JetBrains Client session has open. */
    private suspend fun hosts(file: VirtualFile): List<String> {
        if (currentSplitRole() != SplitRole.BACKEND) return emptyList()
        val sessions = ClientSessionsManager.getProjectSessions(project, ClientKind.REMOTE)
        return withContext(edtAny) {
            sessions.mapNotNull { session ->
                val composites = session.service<ClientFileEditorManager>().getAllComposites(file)
                if (composites.isEmpty()) return@mapNotNull null
                "open in ${UiEditorState.sessionLabel(session.clientId.value.take(SESSION_ID))} in ${composites.size} editor(s)"
            }
        }
    }

    private fun pathOf(file: VirtualFile): String =
        project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(file, it) }?.takeIf { it.isNotEmpty() } ?: file.name

    private fun matches(file: VirtualFile, wanted: String): Boolean {
        val w = wanted.trim().removePrefix("./")
        return pathOf(file) == w || (!w.contains('/') && file.name == w) || file.path.endsWith("/$w")
    }

    companion object {
        const val CLIENT = "the JetBrains Client"
        private const val SESSION_ID = 8
    }
}
