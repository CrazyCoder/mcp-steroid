/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.inspection

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectListener
import com.intellij.codeInspection.ex.GlobalInspectionContextUtil
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.util.Collections
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jdom.Element
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A problem an inspection reported, with the inspection's short name. */
data class FoundProblem(val shortName: String, val descriptor: ProblemDescriptor)

/**
 * What [BatchInspection.run] found; when it did not finish, the problems found until it stopped. [crashed] maps each
 * inspection that crashed to its first error; the other inspections' problems in the same files are kept.
 */
data class BatchInspectionResult(
    val problems: List<FoundProblem>,
    val finished: Boolean,
    val files: Int,
    val crashed: Map<String, String> = emptyMap(),
)

/** The inspection short names the current profile does not have, with similar names it has. */
class UnknownInspectionsException(val unknown: List<String>, val similar: List<String>) :
    IllegalArgumentException("no inspection with the short name ${unknown.joinToString()}" +
        if (similar.isEmpty()) "" else "; similar: ${similar.joinToString()}")

/**
 * Runs inspections the way Code | Inspect Code does, without its results view or dialogs.
 *
 * InspectionEngine.inspectEx inside a script's read action can freeze the IDE: it inspects on worker threads
 * that a write action cannot cancel, and an on-the-fly JavaScript or TypeScript inspection can wait there for
 * the TypeScript service while the service waits for a write action behind that read lock. The batch engine
 * inspects one file at a time under a progress indicator that a write action cancels, waits for the write,
 * and inspects the file again; in batch mode the TypeScript inspections do not ask the TypeScript service.
 * Its problems are those of the Inspection Results view, which can differ from what the editor highlights.
 */
class BatchInspection(private val project: Project) {

    /**
     * Inspects [scope] with the current profile, or with only the [inspections] given by short name (and the
     * inspections they depend on). Past [timeout] the run is cancelled and returns what it found so far.
     *
     * An inspection that crashes on a file makes the engine drop that file's other results, so the files a crash
     * hit are inspected again without the crashed inspections, up to [MAX_CRASH_ROUNDS] times.
     */
    suspend fun run(scope: AnalysisScope, inspections: Collection<String>? = null, timeout: Duration = DEFAULT_TIMEOUT): BatchInspectionResult {
        if (!GlobalInspectionContextUtil.canRunInspections(project, false) {}) {
            throw IllegalStateException("the project has no modules, so the IDE cannot inspect it")
        }
        val deadline = TimeSource.Monotonic.markNow() + timeout
        val names = inspections?.takeIf { it.isNotEmpty() }
        val crashed = LinkedHashMap<String, String>()
        var round = runOnce(scope, names?.let { restrictedProfile(it) }, (-deadline.elapsedNow()).coerceAtLeast(Duration.ZERO))
        var problems = round.problems
        var rounds = 0
        while (round.finished && round.crashes.isNotEmpty() && rounds++ < MAX_CRASH_ROUNDS) {
            round.crashes.forEach { crashed.putIfAbsent(it.tool, it.error) }
            val files = round.crashes.mapNotNullTo(LinkedHashSet()) { it.file }
            val left = (names ?: enabledInspections()).filter { it !in crashed }
            problems = readAction { problems.filter { fileOf(it) !in files } }
            if (files.isEmpty() || left.isEmpty()) break
            round = runOnce(AnalysisScope(project, files), restrictedProfile(left), (-deadline.elapsedNow()).coerceAtLeast(Duration.ZERO))
            problems = problems + round.problems
        }
        round.crashes.forEach { crashed.putIfAbsent(it.tool, it.error) }
        return BatchInspectionResult(problems, round.finished, scope.fileCount, crashed)
    }

    private data class Crash(val tool: String, val error: String, val file: VirtualFile?)

    private data class Round(val problems: List<FoundProblem>, val finished: Boolean, val crashes: List<Crash>)

    /** One Inspect Code run over [scope] with [profile], or the project's current profile. */
    private suspend fun runOnce(scope: AnalysisScope, profile: InspectionProfileImpl?, timeout: Duration): Round {
        val manager = InspectionManager.getInstance(project) as InspectionManagerEx
        val context = Context(project, manager)
        // The project's current profile, as the editor uses it; the context's own default goes through a profile
        // name InspectionManager keeps, which can name an older profile.
        context.setExternalProfile(profile ?: InspectionProjectProfileManager.getInstance(project).currentProfile)
        // The engine reports a crashed inspection on INSPECT_TOPIC. The topic is the project's, so crashes outside
        // [scope], from another inspection run, are not counted.
        val crashes = Collections.synchronizedList(mutableListOf<Crash>())
        val connection = project.messageBus.connect()
        connection.subscribe(GlobalInspectionContextEx.INSPECT_TOPIC, object : InspectListener {
            override fun inspectionFailed(toolId: String, throwable: Throwable, file: PsiFile?, project: Project) {
                if (throwable is ProcessCanceledException || throwable is CancellationException) return
                val virtualFile = file?.virtualFile
                if (virtualFile != null && !scope.contains(virtualFile)) return
                crashes += Crash(toolId, "${throwable.javaClass.name}: ${throwable.message}", virtualFile)
            }
        })
        try {
            return coroutineScope {
                // Not awaited: where the task runs synchronously, as in tests, the start returns only when it ends.
                launch(Dispatchers.EDT) { WriteIntentReadAction.run { context.start(scope) } }
                val done = withTimeoutOrNull(timeout) { context.done.await() }
                if (done != null) Round(done.first, done.second, crashes.toList())
                else Round(context.cancelAndAwait(), false, crashes.toList())
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { context.cancelAndAwait() }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun fileOf(problem: FoundProblem): VirtualFile? = problem.descriptor.psiElement?.containingFile?.virtualFile

    private suspend fun enabledInspections(): List<String> = readAction {
        InspectionProjectProfileManager.getInstance(project).currentProfile.getAllEnabledInspectionTools(project).map { it.tool.shortName }
    }

    /** [files], which can include directories, as one scope. */
    suspend fun scopeOf(files: Collection<VirtualFile>): AnalysisScope = readAction {
        val single = files.singleOrNull()
        when {
            single != null && single.isDirectory -> AnalysisScope(PsiManager.getInstance(project).findDirectory(single) ?: error("${single.path} is not in the project"))
            single != null -> AnalysisScope(PsiManager.getInstance(project).findFile(single) ?: error("${single.path} is not in the project"))
            else -> AnalysisScope(project, files)
        }
    }

    fun projectScope(): AnalysisScope = AnalysisScope(project)

    /** A profile with only [shortNames] enabled, set up as in the current profile, as Run Inspection by Name builds it. */
    private suspend fun restrictedProfile(shortNames: Collection<String>): InspectionProfileImpl = readAction {
        val root = InspectionProjectProfileManager.getInstance(project).currentProfile
        val wrappers = LinkedHashSet<InspectionToolWrapper<*, *>>()
        val unknown = mutableListOf<String>()
        for (name in shortNames) {
            val wrapper = root.getInspectionTool(name, project)
            if (wrapper == null) unknown += name else {
                wrappers += wrapper
                root.collectDependentInspections(wrapper, wrappers, project)
            }
        }
        if (unknown.isNotEmpty()) {
            val all = root.allTools.map { it.tool.shortName }.distinct()
            val similar = unknown.flatMap { name -> all.filter { it.contains(name, ignoreCase = true) || name.contains(it, ignoreCase = true) } }.distinct().take(8)
            throw UnknownInspectionsException(unknown, similar)
        }
        val model = InspectionProfileImpl("MCP Steroid: " + shortNames.joinToString(), InspectionToolsSupplier.Simple(wrappers.toList()), root)
        for (wrapper in wrappers) {
            model.enableTool(wrapper.shortName, project)
            val settings = Element("toCopy")
            runCatching {
                wrapper.tool.writeSettings(settings)
                model.getInspectionTool(wrapper.shortName, project)?.tool?.readSettings(settings)
            }
        }
        model
    }

    /** The IDE's inspection context, reporting its problems here instead of in the results view. */
    private class Context(project: Project, manager: InspectionManagerEx) : GlobalInspectionContextImpl(project, manager.contentManager) {
        val done = CompletableDeferred<Pair<List<FoundProblem>, Boolean>>()

        @Volatile
        private var indicator: ProgressIndicator? = null

        /** Starts the background task. EDT, with the write-intent lock. */
        fun start(scope: AnalysisScope) {
            currentScope = scope
            launchInspections(scope)
        }

        override fun runTools(scope: AnalysisScope, runGlobalToolsOnly: Boolean, isOfflineInspections: Boolean) {
            indicator = ProgressManager.getGlobalProgressIndicator()
            super.runTools(scope, runGlobalToolsOnly, isOfflineInspections)
        }

        override fun notifyInspectionsFinished(scope: AnalysisScope) {
            done.complete(problems() to true)
            close(true)
        }

        override fun canceled() {
            done.complete(problems() to false)
            // The platform's part cancels the indicators of the threads that inspect the files.
            super.canceled()
        }

        private fun problems(): List<FoundProblem> = usedTools.flatMap { tools ->
            val wrapper = tools.tool
            getPresentation(wrapper).problemDescriptors.filterIsInstance<ProblemDescriptor>().map { FoundProblem(wrapper.shortName, it) }
        }

        /** Cancels the task, as its Cancel button does, and returns what it found. */
        suspend fun cancelAndAwait(): List<FoundProblem> {
            val started = withTimeoutOrNull(START_WAIT) { while (indicator == null && !done.isCompleted) delay(50) } != null
            indicator?.cancel()
            if (!started && !done.isCompleted) return emptyList()
            return withTimeoutOrNull(STOP_WAIT) { done.await() }?.first ?: emptyList()
        }
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 300.seconds
        const val MAX_CRASH_ROUNDS = 3
        private val START_WAIT = 5.seconds
        private val STOP_WAIT = 30.seconds
    }
}
