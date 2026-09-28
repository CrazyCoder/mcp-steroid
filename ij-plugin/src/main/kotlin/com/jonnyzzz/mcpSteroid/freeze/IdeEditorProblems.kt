/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
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
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.jonnyzzz.mcpSteroid.server.split.SplitRole
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.TestOnly
import java.util.WeakHashMap

/** A problem the editor highlights in an open file, as its error stripe and the Problems tool window show it. */
data class EditorProblem(val project: String, val file: String, val line: Int, val column: Int, val severity: String, val text: String)

/**
 * Tells agents about the errors the IDE highlights in its open editors, so that an agent that edits code sees what it
 * broke without asking. A notice counts the errors per file and gives the first of each; a get lists them all, and
 * warnings and other severities with them.
 *
 * The problems are the editor's own: what its code analysis found the last time it ran on the file, read without
 * running it again. For the notice, a file still being analyzed keeps its last reading. In Split Mode the backend reads them: the
 * JetBrains Client's copy of the highlighting has no descriptions.
 */
@Service(Service.Level.APP)
class IdeEditorProblems {
    /** The errors of the last [refresh]. */
    @Volatile
    internal var current: List<EditorProblem> = emptyList()
    private val seen = WeakHashMap<Any, Set<Key>>()

    /** What [read] found, and the files whose analysis has not finished, whose problems may be incomplete. */
    class Reading(val problems: List<EditorProblem>, val unfinished: Set<String>)

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
                current = openProjects().filterNot { it.isDisposed }.flatMap { project ->
                    // A file still being analyzed keeps its last reading, so that its errors are not told as gone and new.
                    val reading = read(project, HighlightSeverity.ERROR)
                    reading.problems.filter { it.file !in reading.unfinished } +
                        before.filter { it.project == project.name && it.file in reading.unfinished }
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

    @TestOnly
    internal var openProjects: () -> List<Project> = { ProjectManager.getInstance().openProjects.toList() }

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
        private const val SETTLE_POLL_MS = 250L
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
         * order. The editor analyzes a file while its tab shows, so a tab behind another stays unfinished. In Split
         * Mode each JetBrains Client session's open files are read under its own client id, as [IdeBanners.read] does.
         */
        suspend fun read(project: Project, min: HighlightSeverity, only: VirtualFile? = null): Reading {
            val files = only?.let(::listOf) ?: openFiles(project)
            return readAction {
                if (project.isDisposed) return@readAction Reading(emptyList(), emptySet())
                val daemon = DaemonCodeAnalyzerEx.getInstanceEx(project)
                val unfinished = HashSet<String>()
                val problems = files.flatMap { file ->
                    val path = shortPath(project, file)
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@flatMap emptyList()
                    val psi = PsiManager.getInstance(project).findFile(file) ?: return@flatMap emptyList()
                    if (!finished(daemon, psi)) unfinished += path
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
                Reading(problems, unfinished)
            }
        }

        /** The files open in [project]'s editors, for every JetBrains Client session in Split Mode. */
        suspend fun openFiles(project: Project): List<VirtualFile> = perSession(project) { it.openFiles.toList() }

        /** The files whose tabs show, the ones the editor analyzes, for every JetBrains Client session in Split Mode. */
        suspend fun shownFiles(project: Project): List<VirtualFile> = perSession(project) { it.selectedFiles.toList() }

        private suspend fun perSession(project: Project, files: (FileEditorManager) -> List<VirtualFile>): List<VirtualFile> {
            val sessions = runCatching { ClientSessionsManager.getProjectSessions(project, ClientKind.ALL) }.getOrDefault(emptyList())
            val contexts = sessions.map { it.clientId.asContextElement() }.ifEmpty { listOf(null) }
            return contexts.flatMap { client ->
                val context = Dispatchers.EDT + ModalityState.any().asContextElement()
                withContext(if (client == null) context else context + client) {
                    if (project.isDisposed) emptyList() else files(FileEditorManager.getInstance(project))
                }
            }.distinct()
        }

        /**
         * [read], after waiting up to [timeoutMs] for the analysis of the shown files among them to finish: a get right
         * after an edit or a start would otherwise find the file not analyzed yet. A tab behind another is not waited for.
         */
        suspend fun readSettled(project: Project, min: HighlightSeverity, only: VirtualFile?, timeoutMs: Long): Reading {
            val started = System.currentTimeMillis()
            while (true) {
                val reading = read(project, min, only)
                val shown = shownFiles(project).mapTo(HashSet()) { shortPath(project, it) }
                if (reading.unfinished.none { it in shown } || System.currentTimeMillis() - started >= timeoutMs) return reading
                delay(SETTLE_POLL_MS)
            }
        }

        /**
         * Whether every analysis pass of [psi] finished. The error pass alone ends before a language service, such as
         * TypeScript's, adds its errors, which the editor shows as "Analyzing…" meanwhile. The full check is marked for
         * tests, so the error pass stands in on a build that lacks it. Read action.
         */
        private fun finished(daemon: DaemonCodeAnalyzerEx, psi: PsiFile): Boolean = try {
            (daemon as? DaemonCodeAnalyzerImpl)?.isAllAnalysisFinished(psi) ?: daemon.isErrorAnalyzingFinished(psi)
        } catch (_: LinkageError) {
            daemon.isErrorAnalyzingFinished(psi)
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

        /**
         * The problems a get lists, as `path:line:column: SEVERITY text`, at most [MAX_LISTED] of them, and the files
         * whose analysis has not finished, whose lists may be incomplete.
         */
        fun renderList(reading: Reading, min: String, scope: String): String = buildString {
            val problems = reading.problems
            if (problems.isEmpty()) {
                append("no problem of $min severity or above in $scope")
            } else {
                val bySeverity = problems.groupingBy { it.severity }.eachCount().entries.joinToString { "${it.key.lowercase()} ${it.value}" }
                append("${problems.size} problem(s) of $min severity or above in $scope ($bySeverity):")
                for (p in problems.take(MAX_LISTED)) append("\n${p.file}:${p.line}:${p.column}: ${p.severity} ${p.text}")
                if (problems.size > MAX_LISTED) append("\n… and ${problems.size - MAX_LISTED} more; name one file or raise the severity")
            }
            if (reading.unfinished.isNotEmpty()) {
                append("\nnot analyzed to the end, so possibly incomplete: ${reading.unfinished.sorted().joinToString()}. ")
                append("The editor analyzes a file while its tab shows: select the tab with a goto, then get again.")
            }
        }
    }
}
