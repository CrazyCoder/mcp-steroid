/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * The steroid_execute_code calls running in this IDE, by execution id, so a freeze can name and cancel one.
 *
 * A cancelled execution's coroutine ends while its thread can still run code that ignores cancellation, so
 * the ids of the last [RECENT] executions stay known after they end.
 */
object RunningExecutions {
    private const val RECENT = 50
    private val jobs = ConcurrentHashMap<String, Job>()
    private val recent = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > RECENT
    }

    fun register(executionId: String, job: Job) {
        jobs[executionId] = job
        synchronized(recent) { recent[executionId] = Unit }
        job.invokeOnCompletion { jobs.remove(executionId, job) }
    }

    fun isRunning(executionId: String): Boolean = jobs.containsKey(executionId)

    /** The execution, running or recent, whose compiled script class is [scriptClass], as a thread dump names it. */
    fun forScriptClass(scriptClass: String): String? =
        synchronized(recent) { recent.keys.toList() }.lastOrNull { scriptClassOf(it) == scriptClass }

    /** Cancels [executionId]; false when it is not running. */
    fun cancel(executionId: String, reason: String): Boolean {
        val job = jobs[executionId] ?: return false
        job.cancel(CancellationException(reason))
        return true
    }

    /** The class name the compiler gives a script: see CodeEvalManager, which wraps it as `Script_@jonnyzzz_<id>`. */
    fun scriptClassOf(executionId: String): String = "Script_@jonnyzzz_$executionId".replace(Regex("[^A-Za-z0-9_]"), "_")
}
