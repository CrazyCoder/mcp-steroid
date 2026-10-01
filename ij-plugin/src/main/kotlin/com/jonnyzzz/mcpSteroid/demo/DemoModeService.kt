/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.demo

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import kotlinx.coroutines.*
import java.awt.Frame
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JWindow

/**
 * Project-level service managing the Demo Mode overlay.
 * Only one overlay is shown per project at a time.
 * Subscribes to execution events via the message bus.
 */
@Service(Service.Level.PROJECT)
class DemoModeService(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) : Disposable {

    private val log = thisLogger()

    private var currentWindow: JWindow? = null
    private var overlayPanel: DemoOverlayPanel? = null
    private var currentPanelDisposable: Disposable? = null
    private var hideJob: Job? = null

    // Track which executions are being displayed (thread-safe)
    private val displayedExecutions: MutableSet<ExecutionId> = ConcurrentHashMap.newKeySet()

    private val notificationsAreStarted = AtomicBoolean(false)

    fun startDemoNotifications() {
        if (!notificationsAreStarted.compareAndSet(false, true)) return

        // Subscribe to execution events via message bus
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(EXECUTION_EVENTS_TOPIC, object : ExecutionEventListener {
            override fun onExecutionStarted(event: ExecutionEvent.Started) {
                if (!DemoModeSettings.isEnabled) return
                if (event.project != project) return

                displayedExecutions.add(event.executionId)
                coroutineScope.launch(Dispatchers.EDT) {
                    showOverlay()
                    overlayPanel?.appendLogLine("[${event.taskId}] Starting: ${event.reason}")
                }
            }

            override fun onExecutionProgress(event: ExecutionEvent.Progress) {
                if (!DemoModeSettings.isEnabled) return
                if (event.executionId !in displayedExecutions) return

                coroutineScope.launch(Dispatchers.EDT) {
                    overlayPanel?.appendLogLine(event.message)
                }
            }

            override fun onExecutionOutput(event: ExecutionEvent.Output) {
                if (!DemoModeSettings.isEnabled) return
                if (event.executionId !in displayedExecutions) return

                coroutineScope.launch(Dispatchers.EDT) {
                    overlayPanel?.appendLogLine(event.message)
                }
            }

            override fun onExecutionCompleted(event: ExecutionEvent.Completed) {
                if (!DemoModeSettings.isEnabled) return
                if (event.executionId !in displayedExecutions) return

                displayedExecutions.remove(event.executionId)

                val statusMsg = if (event.success) "✓ Completed" else "✗ Failed: ${event.errorMessage ?: "Unknown error"}"
                coroutineScope.launch(Dispatchers.EDT) {
                    overlayPanel?.appendLogLine(statusMsg)
                }

                // Schedule hide if no more active executions for this project
                if (displayedExecutions.isEmpty()) {
                    scheduleHide()
                }
            }
        })
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = coroutineScope.launch {
            delay(DemoModeSettings.minDisplayTimeMs.toLong())
            if (displayedExecutions.isEmpty()) {
                withContext(Dispatchers.EDT) {
                    hideOverlay()
                }
            }
        }
    }

    private fun showOverlay() {
        val frame = WindowManager.getInstance().getFrame(project) ?: return

        // Keep reference to old window to close AFTER showing new one (avoid flickering)
        val oldWindow = currentWindow

        // Optionally bring frame to front
        if (DemoModeSettings.focusFrame) {
            frame.toFront()
            frame.requestFocus()
            if (frame.state == Frame.ICONIFIED) {
                frame.state = Frame.NORMAL
            }
        }

        // Create disposable for this overlay instance
        val panelDisposable = Disposer.newDisposable("demo-overlay-${System.currentTimeMillis()}")
        Disposer.register(this, panelDisposable)
        currentPanelDisposable = panelDisposable

        // Create panel
        val panel = DemoOverlayPanel(panelDisposable) {
            hideOverlayImmediately()
        }
        overlayPanel = panel

        // Create window
        val window = JWindow(frame)
        window.contentPane = panel
        window.pack()

        // Set window shape for rounded corners
        try {
            window.shape = RoundRectangle2D.Double(
                0.0, 0.0,
                DemoOverlayPanel.PANEL_WIDTH.toDouble(),
                DemoOverlayPanel.PANEL_HEIGHT.toDouble(),
                DemoOverlayPanel.CORNER_RADIUS.toDouble(),
                DemoOverlayPanel.CORNER_RADIUS.toDouble()
            )
        } catch (e: Exception) {
            log.warn("Failed to set window shape", e)
        }

        // Center on frame
        val frameLocation = frame.locationOnScreen
        window.setLocation(
            frameLocation.x + (frame.width - window.width) / 2,
            frameLocation.y + (frame.height - window.height) / 2
        )

        window.isAlwaysOnTop = true
        // The overlay only shows progress. Keys steroid_ui and steroid_input dispatch go to the focused component,
        // so a focusable overlay would swallow them; mouse clicks on its close button still arrive.
        window.focusableWindowState = false
        currentWindow = window

        // Show window and fade in
        window.isVisible = true
        panel.fadeIn()

        // Close old window AFTER showing new one (avoid flickering)
        oldWindow?.dispose()
    }

    private fun hideOverlay() {
        overlayPanel?.fadeOut {
            hideOverlayImmediately()
        }
    }

    private fun hideOverlayImmediately() {
        hideJob?.cancel()
        hideJob = null
        currentWindow?.dispose()
        currentWindow = null
        overlayPanel = null
        currentPanelDisposable?.let { disposable ->
            try {
                Disposer.dispose(disposable)
            } catch (_: Exception) {
                // Already disposed
            }
        }
        currentPanelDisposable = null
    }

    /**
     * Force hide the overlay (e.g., from close button).
     */
    @Suppress("unused") // Public API for programmatic control
    fun forceHide() {
        displayedExecutions.clear()
        hideOverlayImmediately()
    }

    override fun dispose() {
        hideOverlayImmediately()
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): DemoModeService = project.service()
    }
}

/**
 * Extension property for convenient access.
 */
@Suppress("unused") // Public API for external use
inline val Project.demoModeService: DemoModeService
    get() = DemoModeService.getInstance(this)
