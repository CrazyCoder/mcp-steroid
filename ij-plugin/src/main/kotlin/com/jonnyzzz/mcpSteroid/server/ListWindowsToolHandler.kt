/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressModel
import com.intellij.openapi.progress.TaskInfo
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.StatusBarEx
import com.jonnyzzz.mcpSteroid.execution.dialogWindowsLookup
import com.jonnyzzz.mcpSteroid.server.split.activeSplitFrontendBridge
import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

class ListWindowsToolHandlerIJ : ListWindowsToolHandler {
    override suspend fun collectListWindowsResponse(): ListWindowsResponse {
        val snapshot = service<IdeWindowsCollector>().collect()
        val self = describeSelfBackend()
        return ListWindowsResponse(
            // windows[]/backgroundTasks[] keep their produced order (#155 sorts list_projects only).
            windows = snapshot.windows.map { it.listed(it.projectName, self.backendName) },
            backgroundTasks = snapshot.backgroundTasks.map { it.listed(it.projectName, self.backendName) },
            // Unconditional self entry — the identity probe works even with zero open windows (#155).
            // In a Split Mode frontend the backend's entry joins it, so both sides are listed.
            backends = backendsTable(listOfNotNull(self.selfBackendRef(), activeSplitFrontendBridge()?.backendSelf())),
        )
    }
}

/**
 * Wire-shaped snapshot of this IDE's windows and background tasks — exactly the lists carried inside
 * the pristine [NpxBridgeWindowsResponse]. Shared seam between the `/windows` bridge
 * ([NpxBridgeService.buildWindows], wire) and the MCP handler ([ListWindowsToolHandlerIJ], which wraps
 * the same lists into [ListedWindow]/[ListedBackgroundTask]).
 */
data class IdeWindowsSnapshot(
    val windows: List<WindowInfo>,
    val backgroundTasks: List<ProgressTaskInfo>,
)

/** Collects the [IdeWindowsSnapshot] from the running IDE (EDT enumeration, modality-aware). */
@Service(Service.Level.APP)
class IdeWindowsCollector {
    private val log = logger<IdeWindowsCollector>()

    suspend fun collect(): IdeWindowsSnapshot {
        // Use DialogWindowsLookup for reliable modal detection:
        // fast negative path (canPumpEdtNonModal), then EDT check if needed.
        val lookup = dialogWindowsLookup()
        val (windowInfos, progressTasks) = lookup.withModalityCheck { isModalShowing ->
            // Window enumeration runs on EDT with ModalityState.any() so it works
            // even when a modal dialog is blocking the normal EDT dispatcher.
            withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                val frames = WindowManager.getInstance().allProjectFrames.toList()

                // Collect progress indicators from all frames
                val allProgressTasks = mutableListOf<ProgressTaskInfo>()

                val frameInfos = frames.map { frame ->
                    val project = frame.project
                    val component = frame.component
                    val window = SwingUtilities.getWindowAncestor(component)
                    val bounds = window?.bounds

                    val statusBar = frame.statusBar as? StatusBarEx
                    statusBar?.let { bar ->
                        val tasks = try {
                            val listOfAny: List<Any> = bar.backgroundProcessModels

                            listOfAny.mapNotNull {
                                // Collect progress tasks from the status bar.
                                // Wrapped in try/catch because IntelliJ 262+ changed the return type of
                                // StatusBarEx.backgroundProcessModels from List<c.i.o.u.Pair> to
                                // List<kotlin.Pair>, causing ClassCastException when the plugin is built
                                // against 253. See mcp-steroid#18.
                                runCatching {
                                    val inner = it as com.intellij.openapi.util.Pair<*, *>
                                    return@mapNotNull inner.first to inner.second
                                }
                                runCatching {
                                    return@mapNotNull it as Pair<*, *>
                                }
                                null
                            }.mapNotNull { (a, b) ->
                                (a as? TaskInfo ?: return@mapNotNull null) to (b as? ProgressModel
                                    ?: return@mapNotNull null)
                            }
                        } catch (e: Throwable) {
                            if (e is ControlFlowException) throw e
                            log.warn("Failed to get list windows. Skipping. ${e.message}", e)
                            listOf()
                        }

                        tasks.forEach { pair ->
                            val taskInfo = pair.first
                            val progressModel = pair.second
                            allProgressTasks.add(
                                ProgressTaskInfo(
                                    title = taskInfo.title,
                                    text = progressModel.getText() ?: "",
                                    text2 = progressModel.getDetails() ?: "",
                                    fraction = if (progressModel.isIndeterminate()) null else progressModel.getFraction(),
                                    isIndeterminate = progressModel.isIndeterminate(),
                                    isCancellable = progressModel.isCancellable(),
                                    projectName = project?.let { projectNameFor(it) }
                                )
                            )
                        }
                    }

                    WindowInfo(
                        projectName = project?.let { projectNameFor(it) },
                        projectPath = project?.let { projectPathFor(it) },
                        title = (window as? Frame)?.title,
                        isActive = window?.isActive ?: false,
                        isVisible = window?.isVisible ?: false,
                        bounds = bounds?.let { WindowBounds(it.x, it.y, it.width, it.height) },
                        windowId = WindowIdUtil.compute(window, component),
                        modalDialogShowing = isModalShowing,
                        indexingInProgress = project?.let { DumbService.isDumb(it) },
                        projectInitialized = project?.isInitialized,
                    )
                }

                val knownWindowIds = frameInfos.map { it.windowId }.toMutableSet()
                val frameProjects = frames.mapNotNull { frame ->
                    val project = frame.project?.takeUnless { it.isDisposed } ?: return@mapNotNull null
                    SwingUtilities.getWindowAncestor(frame.component)?.let { it to project }
                }.toMap()
                val fallbackProject = frameProjects.values.firstOrNull()
                val extraInfos = java.awt.Window.getWindows()
                    .filter { it.isDisplayable }
                    .mapNotNull { window ->
                        val windowId = WindowIdUtil.compute(window, window)
                        if (!knownWindowIds.add(windowId)) return@mapNotNull null
                        val bounds = window.bounds
                        // Screenshot and input take a project_name to route the call, so a dialog carries its
                        // owner frame's project, and a window without one carries an open project of this IDE.
                        val route = windowProjectRoute(window, { it.owner }, frameProjects::get, fallbackProject)
                        WindowInfo(
                            projectName = route.project?.let { projectNameFor(it) },
                            projectPath = route.project?.takeIf { route.owned }?.let { projectPathFor(it) },
                            // Dialogs are the common case in this fallback branch — a Frame-only cast
                            // left every dialog with title=null, making them untargetable (issue #309).
                            title = when (window) {
                                is Frame -> window.title
                                is java.awt.Dialog -> window.title
                                else -> null
                            },
                            isActive = window.isActive,
                            isVisible = window.isVisible,
                            bounds = WindowBounds(bounds.x, bounds.y, bounds.width, bounds.height),
                            windowId = windowId,
                            modalDialogShowing = isModalShowing,
                        )
                    }

                (frameInfos + extraInfos) to allProgressTasks.toList()
            }
        }

        return IdeWindowsSnapshot(
            windows = windowInfos,
            backgroundTasks = progressTasks,
        )
    }
}

/** The project a window routes through: [owned] when it comes from the window's owner chain. */
internal data class WindowProjectRoute<P>(val project: P?, val owned: Boolean)

/**
 * Follows [window]'s owners, as given by [ownerOf], to the first one that [projectOf] maps to a project, and
 * falls back to [fallback] when none does. The walk is bounded, so an owner cycle cannot loop.
 */
internal fun <W : Any, P : Any> windowProjectRoute(
    window: W,
    ownerOf: (W) -> W?,
    projectOf: (W) -> P?,
    fallback: P?,
): WindowProjectRoute<P> {
    var current: W? = window
    repeat(MAX_OWNER_DEPTH) {
        val w = current ?: return WindowProjectRoute(fallback, owned = false)
        projectOf(w)?.let { return WindowProjectRoute(it, owned = true) }
        current = ownerOf(w)
    }
    return WindowProjectRoute(fallback, owned = false)
}

private const val MAX_OWNER_DEPTH = 32
