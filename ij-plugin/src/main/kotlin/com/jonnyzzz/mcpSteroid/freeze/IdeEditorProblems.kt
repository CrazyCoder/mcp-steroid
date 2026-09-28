/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeWithMe.asContextElement
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.client.ClientKind
import com.intellij.openapi.client.ClientSessionsManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jonnyzzz.mcpSteroid.server.split.SplitRole
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.WeakHashMap

/** A problem the editor highlights in an open file, as its error stripe and the Problems tool window show it. */
data class EditorProblem(val project: String, val file: String, val line: Int, val column: Int, val severity: String, val text: String)

/**
 * Tells agents about the errors the IDE highlights in its open editors, so that an agent that edits code sees what it
 * broke without asking. A notice counts the errors per file and gives the first of each; a get lists them all, and
 * warnings and other severities with them.
 *
 * The problems are the editor's own: what its code analysis found the last time it ran on the file, read without
 * running it again. A file still being analyzed keeps its last reading. In Split Mode the backend reads them: the
 * JetBrains Client's copy of the highlighting has no descriptions.
 */
@Service(Service.Level.APP)
class IdeEditorProblems {
    /** The errors of the last [refresh]. */
    @Volatile
    internal var current: List<EditorProblem> = emptyList()
    private val seen = WeakHashMap<Any, Set<Key>>()

    /** What makes an error the same one after lines above it moved it. */
    private data class Key(val project: String, val file: String, val text: String)

    private val EditorProblem.key get() = Key(project, file, text)

    /**
     * Reads the errors of every open project's editors again, unless that takes longer than [REFRESH_MS]. It runs
     * after every call, so a failure leaves the last reading and is logged once instead of failing the call.
     */
    suspend fun refresh() {
        if (currentSplitRole() == SplitRole.FRONTEND) return
        try {
            withTimeoutOrNull(REFRESH_MS) {
                val before = current
                current = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.flatMap { project ->
                    read(project, HighlightSeverity.ERROR, keep = before.filter { it.project == project.name })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                thisLogger().warn("Cannot read the editors' problems; the editor errors notice stays off", e)
            }
        }
    }

    @Volatile
    private var failureLogged = false

    /**
     * The open files' errors, as a notice, when one of them is new to [session]; every current error is then told. An
     * error that went away and came back is new again.
     */
    fun noticeFor(session: Any): String? {
        val now = current
        val fresh = synchronized(seen) {
            val before = seen[session].orEmpty()
            seen[session] = now.mapTo(HashSet()) { it.key }
            now.any { it.key !in before }
        }
        return if (fresh) render(now, FreezeMonitor.sideOf(currentSplitRole())) else null
    }

    companion object {
        const val MAX_FILES = 3
        const val MAX_LISTED = 100
        private const val REFRESH_MS = 1_000L
        private const val MAX_TEXT = 200

        /** The severities a get takes, from the most severe. */
        val SEVERITIES = linkedMapOf(
            "error" to HighlightSeverity.ERROR,
            "warning" to HighlightSeverity.WARNING,
            "weak_warning" to HighlightSeverity.WEAK_WARNING,
            "info" to HighlightSeverity.INFORMATION,
        )

        fun getInstanceOrNull(): IdeEditorProblems? = ApplicationManager.getApplication()?.let { service<IdeEditorProblems>() }

        /**
         * The problems of [min] severity and above in [project]'s open files, or in [only] of them, in file and line
         * order. A file whose analysis has not finished gives what [keep] holds for it. In Split Mode each JetBrains
         * Client session's open files are read under its own client id, as [IdeBanners.read] does.
         */
        suspend fun read(project: Project, min: HighlightSeverity, keep: List<EditorProblem> = emptyList(), only: VirtualFile? = null): List<EditorProblem> {
            val files = only?.let(::listOf) ?: openFiles(project)
            return readAction {
                if (project.isDisposed) return@readAction emptyList()
                val daemon = DaemonCodeAnalyzerEx.getInstanceEx(project)
                files.flatMap { file ->
                    val path = shortPath(project, file)
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@flatMap emptyList()
                    val psi = PsiManager.getInstance(project).findFile(file) ?: return@flatMap emptyList()
                    if (!daemon.isErrorAnalyzingFinished(psi)) return@flatMap keep.filter { it.file == path }
                    val found = ArrayList<EditorProblem>()
                    DaemonCodeAnalyzerEx.processHighlights(document, project, min, 0, document.textLength) { info: HighlightInfo ->
                        val text = info.description?.trim()?.takeIf { it.isNotEmpty() }
                        if (text != null) {
                            val offset = info.startOffset.coerceIn(0, document.textLength)
                            val line = document.getLineNumber(offset)
                            found += EditorProblem(
                                project.name, path, line + 1, offset - document.getLineStartOffset(line) + 1,
                                severityName(info.severity), if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text,
                            )
                        }
                        true
                    }
                    found.distinct().sortedWith(compareBy({ it.line }, { it.column }))
                }
            }
        }

        /** The files open in [project]'s editors, for every JetBrains Client session in Split Mode. */
        suspend fun openFiles(project: Project): List<VirtualFile> {
            val sessions = runCatching { ClientSessionsManager.getProjectSessions(project, ClientKind.ALL) }.getOrDefault(emptyList())
            val contexts = sessions.map { it.clientId.asContextElement() }.ifEmpty { listOf(null) }
            return contexts.flatMap { client ->
                val context = Dispatchers.EDT + ModalityState.any().asContextElement()
                withContext(if (client == null) context else context + client) {
                    if (project.isDisposed) emptyList() else FileEditorManager.getInstance(project).openFiles.toList()
                }
            }.distinct()
        }

        private fun severityName(s: HighlightSeverity): String =
            SEVERITIES.entries.firstOrNull { s >= it.value }?.key?.uppercase() ?: s.name

        private fun shortPath(project: Project, file: VirtualFile): String =
            project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(file, it) }?.takeIf { it.isNotEmpty() } ?: file.presentableUrl

        private fun count(n: Int) = if (n == 1) "1 error" else "$n errors"

        /**
         * The errors per file, each file's count and its first error, at most [MAX_FILES] files, with how to list them
         * all and the other severities. [side] names the process in Split Mode, as [FreezeMonitor.sideOf] gives it; a
         * get of problems goes to the backend by itself.
         */
        fun render(errors: List<EditorProblem>, side: String? = null): String = buildString {
            val byFile = errors.groupBy { it.project to it.file }.entries.sortedByDescending { it.value.size }
            val projects = errors.map { it.project }.distinct().size > 1
            append("EDITOR ERRORS${side?.let { " in $it" }.orEmpty()}: ")
            append("${count(errors.size)} in ${if (byFile.size == 1) "1 open file" else "${byFile.size} open files"}")
            append("; the steroid_ui step {\"action\":\"get\",\"problems\":\"<file>\"} lists a file's errors, and with \"severity\":\"warning\" its warnings too:")
            for ((key, problems) in byFile.take(MAX_FILES)) {
                val first = problems.minWith(compareBy({ it.line }, { it.column }))
                append("\n- ").append(if (projects) "${key.first}: ${key.second}" else key.second)
                append(": ${count(problems.size)}, first at ${first.line}:${first.column}: ${first.text}")
            }
            if (byFile.size > MAX_FILES) append("\n- and ${byFile.size - MAX_FILES} more files, {\"action\":\"get\",\"problems\":true} lists every open file")
            append('\n')
        }

        /** The problems a get lists, as `path:line:column: SEVERITY text`, at most [MAX_LISTED] of them. */
        fun renderList(problems: List<EditorProblem>, min: String, scope: String): String {
            if (problems.isEmpty()) return "no problem of $min severity or above in $scope"
            val bySeverity = problems.groupingBy { it.severity }.eachCount().entries.joinToString { "${it.key.lowercase()} ${it.value}" }
            return buildString {
                append("${problems.size} problem(s) of $min severity or above in $scope ($bySeverity):")
                for (p in problems.take(MAX_LISTED)) append("\n${p.file}:${p.line}:${p.column}: ${p.severity} ${p.text}")
                if (problems.size > MAX_LISTED) append("\n… and ${problems.size - MAX_LISTED} more; name one file or raise the severity")
            }
        }
    }
}
