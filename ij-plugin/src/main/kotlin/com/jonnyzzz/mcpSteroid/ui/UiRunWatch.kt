/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.time.TimeSource

/**
 * What a run step of a run, debug or stop action did to the project's runs: the runs it started, those that did not
 * start and why, the ones it stopped, and what still runs. An action such as RunClass on a configuration that already
 * runs and allows one instance starts nothing, which its step would otherwise report as a plain success.
 */
class UiRunWatch(private val project: Project, private val actionId: String) {
    private val started = Collections.synchronizedList(mutableListOf<String>())
    private val notStarted = Collections.synchronizedList(mutableListOf<String>())
    private val stopped = Collections.synchronizedList(mutableListOf<String>())
    private val connection = project.messageBus.connect()
    private val runAction = actionId in RUN_ACTIONS || actionId.startsWith("Rerun")
    private val stopAction = actionId in STOP_ACTIONS

    init {
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                started += "'${env.runProfile.name}' ($executorId)"
            }

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
                notStarted += "'${env.runProfile.name}' ($executorId): " + (cause?.message?.lineSequence()?.firstOrNull() ?: "it did not start")
            }

            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                stopped += "'${env.runProfile.name}' ($executorId)"
            }
        })
    }

    /** Stops listening without a report, for an action that failed. */
    fun close() = connection.disconnect()

    /**
     * The report line once the action ran, or null for an action that is not about runs. Waits up to [WAIT_MS] for a
     * run to start or stop, as a process takes a moment to launch or to end. Disconnects.
     */
    suspend fun report(): String? {
        try {
            if (!runAction && !stopAction) return null
            val since = TimeSource.Monotonic.markNow()
            while (since.elapsedNow().inWholeMilliseconds < WAIT_MS) {
                if (runAction && (started.isNotEmpty() || notStarted.isNotEmpty()) || stopAction && stopped.isNotEmpty()) break
                delay(POLL_MS)
            }
            val running = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                val manager = RunContentManager.getInstance(project)
                manager.allDescriptors.filter { it.processHandler?.let { h -> !h.isProcessTerminated } == true }
                    .map { "'${it.displayName}' (${manager.getToolWindowByDescriptor(it)?.id ?: "run"})" }
            }
            return render(started.toList(), notStarted.toList(), stopped.toList(), running, runAction, stopAction)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** Actions that start a run or a debug session from a configuration or the context. */
        private val RUN_ACTIONS = setOf("Run", "Debug", "RunClass", "DebugClass", "ChooseRunConfiguration", "ChooseDebugConfiguration", "Coverage", "Profile")
        private val STOP_ACTIONS = setOf("Stop", "StopAll")
        private const val WAIT_MS = 2_000L
        private const val POLL_MS = 100L

        fun render(
            started: List<String>, notStarted: List<String>, stopped: List<String>, running: List<String>, runAction: Boolean, stopAction: Boolean,
        ): String? {
            if (!runAction && !stopAction) return null
            val parts = mutableListOf<String>()
            started.forEach { parts += "started $it" }
            notStarted.forEach { parts += "did not start $it" }
            stopped.forEach { parts += "stopped $it" }
            if (runAction && started.isEmpty() && notStarted.isEmpty()) {
                parts += "no run or debug session started" + if (running.isEmpty()) "" else "; running now: ${running.joinToString()}"
            } else if (stopAction) {
                if (stopped.isEmpty()) parts += "nothing stopped yet"
                if (running.isNotEmpty()) parts += "still running: ${running.joinToString()}"
            }
            return parts.joinToString("; ")
        }
    }
}
