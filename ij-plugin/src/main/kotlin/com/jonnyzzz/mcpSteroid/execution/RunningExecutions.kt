/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** The steroid_execute_code calls running in this IDE, by execution id, so a freeze can name and cancel one. */
object RunningExecutions {
    private val jobs = ConcurrentHashMap<String, Job>()

    fun register(executionId: String, job: Job) {
        jobs[executionId] = job
        job.invokeOnCompletion { jobs.remove(executionId, job) }
    }

    fun ids(): Set<String> = jobs.keys.toSet()

    /** The execution whose compiled script class is [scriptClass], as a thread dump names it. */
    fun forScriptClass(scriptClass: String): String? = jobs.keys.firstOrNull { scriptClassOf(it) == scriptClass }

    /** Cancels [executionId]; false when it is not running. */
    fun cancel(executionId: String, reason: String): Boolean {
        val job = jobs[executionId] ?: return false
        job.cancel(CancellationException(reason))
        return true
    }

    /** The class name the compiler gives a script: see CodeEvalManager, which wraps it as `Script_@jonnyzzz_<id>`. */
    fun scriptClassOf(executionId: String): String = "Script_@jonnyzzz_$executionId".replace(Regex("[^A-Za-z0-9_]"), "_")
}
