/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.DataManager
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.contentModules
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.playback.PlaybackContext
import com.intellij.openapi.ui.playback.PlaybackRunner
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.util.ui.UIUtil
import com.jonnyzzz.mcpSteroid.execution.ExecutionManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.ModalMode
import com.jonnyzzz.mcpSteroid.server.UiRestore
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.intellij.openapi.wm.ToolWindowType
import kotlinx.serialization.json.JsonObject
import com.intellij.openapi.extensions.ExtensionPointName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Component
import java.awt.Dialog
import java.awt.Window
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import javax.swing.RootPaneContainer
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.TimeSource

/**
 * The steps that set the IDE up rather than click through it: a Settings page opened directly, a tool window, a file
 * written, a Performance Testing playback command, and a Kotlin body run as steroid_execute_code runs it.
 */
internal class UiIdeSteps(private val project: Project, private val taskId: String) {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /** A Settings page: where it sits in the Settings tree, its display name, and its id when it has one. */
    class SettingsPage(val path: List<String>, val id: String?) {
        val name: String get() = path.last()
        override fun toString() = path.joinToString(" > ") + (id?.let { " (id $it)" } ?: "")
    }

    /** What a settings step did, and the page id a recording replays. */
    class Opened(val line: String, val id: String?)

    /**
     * Opens Settings at [UiStep.page], an id, a path such as `Editor > General > Code Folding` or a display name; an
     * open Settings window or modal Settings dialog switches to the page instead.
     */
    suspend fun settings(step: UiStep): Opened {
        val page = findPage(step.page!!, step.timeoutMs)
        // Asked again, the IDE opens a second modal Settings dialog over an open one: switch the open one instead.
        withContext(edtAny) { selectInModal(page) }?.let { callback ->
            val started = TimeSource.Monotonic.markNow()
            while (!callback.isProcessed && started.elapsedNow().inWholeMilliseconds < step.timeoutMs) delay(POLL_MS)
            if (!callback.isDone) throw UiStepFailure("the open Settings dialog did not switch to $page within ${step.timeoutMs} ms")
            UiSettle.settle(quietMs = SETTLE_QUIET_MS, maxMs = step.timeoutMs)
            return Opened("switched Settings to $page", page.id)
        }
        val before = UiSettle.showingWindows()
        ApplicationManager.getApplication().invokeLater({
            if (page.id != null) ShowSettingsUtilImpl.showSettingsDialog(project, page.id, null)
            else ShowSettingsUtil.getInstance().showSettingsDialog(project, page.name)
        }, ModalityState.any())
        val started = TimeSource.Monotonic.markNow()
        var window: Window? = null
        while (started.elapsedNow().inWholeMilliseconds < step.timeoutMs) {
            window = withContext(edtAny) { settingsWindow() }
            if (window != null) break
            delay(POLL_MS)
        }
        window ?: throw UiStepFailure("no Settings window showed within ${step.timeoutMs} ms")
        UiSettle.settle(quietMs = SETTLE_QUIET_MS, maxMs = step.timeoutMs)
        val verb = if (window in before) "switched Settings to" else "opened Settings at"
        return Opened("$verb $page", page.id)
    }

    private fun settingsWindow(): Window? = Window.getWindows().firstOrNull { settingsEditor(it) != null }

    /** The Settings editor [w] shows, or null. EDT. */
    private fun settingsEditor(w: Window): Component? =
        if (!w.isShowing) null
        else (w as? RootPaneContainer)?.rootPane?.let { root -> UIUtil.uiTraverser(root).firstOrNull { it.javaClass.name == SETTINGS_EDITOR } }

    /**
     * Selects [page] in an open modal Settings dialog through the editor's own [Settings], and returns the selection
     * callback. Null when no modal Settings dialog shows, or the page has no id to find it by. EDT.
     */
    private fun selectInModal(page: SettingsPage): ActionCallback? {
        val id = page.id ?: return null
        val editor = Window.getWindows().asSequence()
            .filter { (it as? Dialog)?.isModal == true }
            .firstNotNullOfOrNull(::settingsEditor) ?: return null
        val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(editor)) ?: return null
        val configurable = settings.find(id) ?: return null
        return settings.select(configurable)
    }

    /**
     * The one Settings page [wanted] names, waiting up to [timeoutMs] for it: a JetBrains Client lists the backend's
     * pages only a moment after it connects. Several pages share a name, such as General: a path tells them apart.
     */
    suspend fun findPage(wanted: String, timeoutMs: Long): SettingsPage {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val (page, pages) = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { matchPage(wanted) to pages() }
            if (page != null) return page
            if (started.elapsedNow().inWholeMilliseconds >= timeoutMs) {
                val near = pages.filter { it.name.contains(wanted.trim(), ignoreCase = true) }.take(8)
                throw UiStepFailure("no Settings page \"$wanted\" after $timeoutMs ms" + if (near.isEmpty()) "" else "; similar: ${near.joinToString("; ")}")
            }
            delay(POLL_MS)
        }
    }

    /** The page [wanted] names, or null when none does; several that match fail. */
    private fun matchPage(wanted: String): SettingsPage? {
        val pages = pages()
        val w = wanted.trim()
        val byId = pages.filter { it.id == w }
        val byPath = pages.filter { it.path.joinToString(" > ").equals(w.replace(Regex("\\s*>\\s*"), " > "), ignoreCase = true) }
        val byName = pages.filter { it.name.equals(w, ignoreCase = true) }
        val found = byId.ifEmpty { byPath }.ifEmpty { byName }
        return when (found.size) {
            1 -> found.single()
            0 -> null
            else -> throw UiStepFailure("${found.size} Settings pages are named \"$wanted\"; pass a path or an id: ${found.take(8).joinToString("; ")}")
        }
    }

    private fun pages(): List<SettingsPage> {
        val out = mutableListOf<SettingsPage>()
        fun walk(c: Configurable, path: List<String>) {
            val p = path + (c.displayName?.takeIf { it.isNotBlank() } ?: return)
            out += SettingsPage(p, (c as? SearchableConfigurable)?.id)
            (c as? Configurable.Composite)?.configurables?.forEach { walk(it, p) }
        }
        ShowSettingsUtilImpl.getConfigurableGroups(project, true).forEach { group ->
            (group as? Configurable.Composite)?.configurables?.forEach { walk(it, emptyList()) }
        }
        return out
    }

    /**
     * Shows a tool window and selects its tab, or hides it. Waits up to the step's timeout for the tool window: a
     * JetBrains Client registers the backend's tool windows only a moment after it connects. Gives [undo] the steps
     * that restore its tab, size and visibility. A [sizeOnly] report leaves out the tabs, for a step that only makes
     * room.
     */
    suspend fun toolWindow(step: UiStep, undo: (List<JsonObject>) -> Unit = {}, sizeOnly: Boolean = false): String {
        val wanted = step.id!!
        val started = TimeSource.Monotonic.markNow()
        while (withContext(edtAny) { toolWindowId(wanted) } == null && started.elapsedNow().inWholeMilliseconds < step.timeoutMs) delay(POLL_MS)
        // What the step changes, read before it does: the restore shows the tab and the size first, then hides.
        val before = withContext(edtAny) {
            toolWindowId(wanted)?.let { id ->
                val window = ToolWindowManager.getInstance(project).getToolWindow(id)!!
                val view = (window as? ToolWindowEx)?.takeIf { it.type != ToolWindowType.FLOATING && it.type != ToolWindowType.WINDOWED }
                    ?.let { UiLayout.ToolWindowView(id, it) }
                Triple(id, window.isVisible, window.contentManager.selectedContent?.let(::tabName)) to view?.let { it.axis to it.size }
            }
        }
        val restore = before?.let { (state, size) ->
            val (id, visible, tab) = state
            listOfNotNull(
                tab?.takeIf { step.tab != null }?.let { UiRestore.step("toolwindow", "id" to id, "tab" to it) },
                size?.takeIf { step.width != null || step.height != null }?.let { (axis, px) -> UiRestore.step("toolwindow", "id" to id, axis to px) },
                UiRestore.step("toolwindow", "id" to id, "hide" to !visible).takeIf { visible == step.hide },
            )
        }.orEmpty()
        undo(restore)
        val shown = showToolWindow(step, wanted)
        if (step.width == null && step.height == null) return shown
        val view = withContext(edtAny) {
            val id = toolWindowId(wanted)!!
            UiLayout.ToolWindowView(id, ToolWindowManager.getInstance(project).getToolWindow(id) as ToolWindowEx)
        }
        val sized = UiResize.toolWindow(view, step.width, step.height)
        return if (sizeOnly) sized else "$shown; $sized"
    }

    /** A tab's name as a toolwindow step takes it; a tab name can be HTML, as the Problems tool window's are. */
    private fun tabName(c: com.intellij.ui.content.Content) =
        StringUtil.removeHtmlTags(c.displayName.orEmpty(), true).replace(Regex("\\s+"), " ").trim()

    private fun toolWindowId(wanted: String): String? {
        val manager = ToolWindowManager.getInstance(project)
        return manager.toolWindowIds.firstOrNull { it == wanted }
            ?: manager.toolWindowIds.firstOrNull { it.equals(wanted, ignoreCase = true) || manager.getToolWindow(it)?.stripeTitle.equals(wanted, ignoreCase = true) }
    }

    private suspend fun showToolWindow(step: UiStep, wanted: String): String = withContext(edtAny) {
        val manager = ToolWindowManager.getInstance(project)
        val id = toolWindowId(wanted)
            ?: throw UiStepFailure("no tool window $wanted after ${step.timeoutMs} ms; tool windows: ${manager.toolWindowIds.sorted().joinToString()}")
        val window = manager.getToolWindow(id)!!
        if (step.hide) {
            window.hide(null)
            return@withContext "hid the $id tool window"
        }
        val shown = CompletableDeferred<Unit>()
        window.activate({ shown.complete(Unit) }, true, true)
        // withTimeoutOrNull: a timeout thrown from here would read as the whole call timing out.
        withTimeoutOrNull(step.timeoutMs) { shown.await() }
            ?: throw UiStepFailure("the $id tool window did not show within ${step.timeoutMs} ms" + if (!window.isAvailable) "; it is not available in this project" else "")
        val contents = window.contentManager.contents.toList()
        step.tab?.let { tab ->
            val content = contents.firstOrNull { tabName(it) == tab } ?: contents.firstOrNull { tabName(it).contains(tab, ignoreCase = true) }
                ?: throw UiStepFailure("the $id tool window has no tab \"$tab\"; tabs: ${contents.joinToString { "\"${tabName(it)}\"" }}")
            window.contentManager.setSelectedContent(content, true)
        }
        val selected = window.contentManager.selectedContent
        "the $id tool window is active" + if (contents.size > 1) {
            "; tabs: " + contents.joinToString { "\"${tabName(it)}\"" + if (it === selected) " [selected]" else "" }
        } else ""
    }

    /**
     * Writes [UiStep.text] as the whole content of [UiStep.file], creating the file and its folders, or deletes the
     * file. Gives [undo] the step that puts the text before back, or deletes a file the step created.
     */
    suspend fun write(step: UiStep, undo: (List<JsonObject>) -> Unit = {}): String {
        val path = step.file!!
        val base = project.basePath?.let(Path::of) ?: throw UiStepFailure("the project has no folder to resolve $path against")
        val target = base.resolve(path).normalize()
        if (!target.startsWith(base.normalize())) throw UiStepFailure("write changes files of the project only; $path is outside ${project.basePath}")
        val parent = target.parent ?: throw UiStepFailure("$path has no parent folder")
        val existing = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target) }
        // A binary file has no text to write back, so its change is not restored.
        val before = existing?.takeIf { !it.fileType.isBinary }?.let { file ->
            readAction { FileDocumentManager.getInstance().getDocument(file)?.text } ?: withContext(Dispatchers.IO) { VfsUtil.loadText(file) }
        }
        if (step.delete) {
            existing ?: return "there is no $path to delete"
            writeCommandAction(project, "steroid_ui write") { existing.delete(this) }
            before?.let { undo(listOf(UiRestore.step("write", "file" to path, "text" to it))) }
            return "deleted $path"
        }
        val text = step.text!!
        val created = existing == null
        undo(listOfNotNull(if (created) UiRestore.step("write", "file" to path, "delete" to true) else before?.let { UiRestore.step("write", "file" to path, "text" to it) }))
        writeCommandAction(project, "steroid_ui write") {
            val dir = VfsUtil.createDirectoryIfMissing(parent.invariantSeparatorsPathString) ?: throw UiStepFailure("cannot create folder $parent")
            val file = dir.findChild(target.fileName.toString()) ?: dir.createChildData(this, target.fileName.toString())
            val document = FileDocumentManager.getInstance().getDocument(file)
            if (document != null) {
                document.setText(text.replace("\r\n", "\n"))
                FileDocumentManager.getInstance().saveDocument(document)
            } else {
                VfsUtil.saveText(file, text)
            }
        }
        return "${if (created) "created" else "replaced"} $path: ${text.lines().size} line(s)"
    }

    /**
     * Runs Performance Testing playback commands, one per line, such as `%openFile src/A.kt` or
     * `%findUsages`. The plugin's runner is loaded by name: its module is internal, so Steroid cannot depend on it.
     */
    suspend fun perf(step: UiStep): String {
        val script = step.command!!.trim()
        val runnerClass = playbackRunnerClass()
        // An unknown command would stop the runner with a bare cancellation and an IDE error: name it instead.
        val known = playbackCommands(runnerClass)
        if (known.isNotEmpty()) {
            script.lines().map { it.trim() }.filter { it.startsWith("%") }.map { it.substringBefore(' ') }.forEach { name ->
                if (name !in known) {
                    val similar = known.filter { it.contains(name.drop(1).take(6), ignoreCase = true) }.sorted().take(8)
                    throw UiStepFailure("unknown playback command $name" + if (similar.isEmpty()) "" else "; similar: ${similar.joinToString()}")
                }
            }
        }
        val messages = Collections.synchronizedList(mutableListOf<Pair<PlaybackRunner.StatusCallback.Type, String>>())
        val callback = object : PlaybackRunner.StatusCallback {
            override fun message(context: PlaybackContext?, text: String, type: PlaybackRunner.StatusCallback.Type) {
                messages += type to text
            }
        }
        val runner = runnerClass.getConstructor(String::class.java, PlaybackRunner.StatusCallback::class.java, Project::class.java)
            .newInstance(script, callback, project)
        val future = runnerClass.getMethod("run").invoke(runner) as CompletableFuture<*>
        try {
            withTimeout(step.timeoutMs) { future.await() }
        } catch (e: TimeoutCancellationException) {
            runCatching { runnerClass.getMethod("stop").invoke(runner) }
            throw UiStepFailure("the playback did not finish within ${step.timeoutMs} ms" + lastMessages(messages))
        } catch (e: CancellationException) {
            // The runner cancels its future when a command fails; the call itself being cancelled must go on up.
            if (!currentCoroutineContext().isActive) throw e
            throw UiStepFailure(playbackError(messages) ?: "the playback stopped" + lastMessages(messages))
        } catch (e: Exception) {
            throw UiStepFailure(playbackError(messages) ?: (e.message ?: e.javaClass.simpleName).lineSequence().first().take(MAX_LINE))
        }
        val commands = script.lines().count { it.trim().startsWith("%") }
        return "ran $commands playback command(s)" + lastMessages(messages)
    }

    /** The first line of the last error the runner reported, such as `%assertCaretPosition 3 1: Caret at column 24, expected 1`. */
    private fun playbackError(messages: List<Pair<PlaybackRunner.StatusCallback.Type, String>>): String? =
        synchronized(messages) { messages.lastOrNull { it.first == PlaybackRunner.StatusCallback.Type.error }?.second }
            ?.lineSequence()?.first()?.take(MAX_LINE)

    /**
     * The names of the playback commands the IDE knows, such as `%openFile`, from the plugin's command providers.
     * Several providers are not public classes, so `getCommands` is called through the public interface.
     */
    private fun playbackCommands(runnerClass: Class<*>): Set<String> {
        val getCommands = runCatching { runnerClass.classLoader.loadClass(COMMAND_PROVIDER).getMethod("getCommands") }.getOrNull() ?: return emptySet()
        val providers = runCatching { ExtensionPointName.create<Any>(PERF_COMMAND_PROVIDERS).extensionList }.getOrDefault(emptyList())
        return providers.flatMap { provider ->
            runCatching { (getCommands.invoke(provider) as Map<*, *>).keys.map { it.toString() } }.getOrDefault(emptyList())
        }.toSet()
    }

    private fun lastMessages(messages: List<Pair<PlaybackRunner.StatusCallback.Type, String>>): String {
        val said = synchronized(messages) { messages.filter { it.first == PlaybackRunner.StatusCallback.Type.message }.map { it.second.lineSequence().first().take(MAX_LINE) } }
            // The runner's closing lines are about its test mode, not about the commands.
            .filter { it.isNotBlank() && !it.startsWith("Stopped") && !it.startsWith("Finished OK") }
        return if (said.isEmpty()) "" else "; said: " + said.takeLast(5).joinToString(" | ")
    }

    private fun playbackRunnerClass(): Class<*> {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PERF_PLUGIN))
            ?: throw UiStepFailure("perf needs the Performance Testing plugin, which this IDE does not have")
        if (!PluginManagerCore.isLoaded(plugin.pluginId)) throw UiStepFailure("perf needs the Performance Testing plugin, which is disabled")
        val loaders = sequenceOf(plugin.pluginClassLoader).filterNotNull() + plugin.contentModules.asSequence().mapNotNull { it.pluginClassLoader }
        for (loader in loaders) {
            try {
                return loader.loadClass(PLAYBACK_RUNNER)
            } catch (_: ClassNotFoundException) {
            } catch (_: RuntimeException) {
            }
        }
        throw UiStepFailure("the Performance Testing plugin has no $PLAYBACK_RUNNER")
    }

    /** Runs [UiStep.code] as steroid_execute_code runs it, with the step's modal policy, and returns what it printed. */
    suspend fun code(step: UiStep): String {
        val modal = step.modal?.let { wire -> ModalMode.entries.first { it.wire == wire } } ?: ModalMode.DEFAULT
        val params = ExecCodeParams(
            taskId = taskId,
            code = step.code!!,
            reason = "steroid_ui code step" + (step.intent?.let { ": $it" } ?: ""),
            timeout = ((step.timeoutMs + 999) / 1000).toInt(),
            modal = modal,
        )
        val result = project.service<ExecutionManager>().executeWithProgress(params, object : McpProgressReporter {
            override fun report(message: String) = Unit
        })
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }.trim()
        val shown = if (text.length > MAX_OUTPUT) text.take(MAX_OUTPUT) + "\n… ${text.length - MAX_OUTPUT} more characters" else text
        if (result.isError) throw UiStepFailure("the code failed:\n$shown")
        return "ran the code:\n$shown"
    }

    private companion object {
        const val POLL_MS = 100L
        const val SETTLE_QUIET_MS = 500L
        const val MAX_LINE = 300
        const val MAX_OUTPUT = 4_000
        const val SETTINGS_EDITOR = "com.intellij.openapi.options.newEditor.SettingsEditor"
        const val PERF_PLUGIN = "com.jetbrains.performancePlugin"
        const val PLAYBACK_RUNNER = "com.jetbrains.performancePlugin.PlaybackRunnerExtended"
        const val PERF_COMMAND_PROVIDERS = "com.jetbrains.performancePlugin.commandProvider"
        const val COMMAND_PROVIDER = "com.jetbrains.performancePlugin.CommandProvider"
    }
}
