/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import java.lang.management.ThreadInfo

/** One thread of a dump, reduced to what [FreezeAnalysis] reads. */
data class ThreadSample(val name: String, val state: Thread.State, val frames: List<StackTraceElement>) {
    companion object {
        fun of(info: ThreadInfo) = ThreadSample(info.threadName, info.threadState, info.stackTrace.toList())
    }
}

/** A thread that holds a read lock while the UI waits. */
data class ReadHolder(
    val thread: String,
    val running: Boolean,
    /** The compiled class of a steroid_execute_code script on this thread, such as `Script__jonnyzzz_eid_...`. */
    val scriptClass: String?,
    /** Where the script is, such as `input.kt:66`. */
    val scriptLine: String?,
    /** The thread's top frames outside the JDK, coroutines and the platform's locking code. */
    val doing: List<String>,
)

/** Who blocks whom in a thread dump taken while the IDE's UI does not respond. */
data class FreezeAnalysis(
    val uiWaitsForLock: Boolean,
    /** The UI thread's top frames when it is busy rather than waiting for a lock. */
    val uiDoing: List<String>,
    /** A thread other than the UI thread that waits to start a write action. */
    val writer: String?,
    val holders: List<ReadHolder>,
) {
    val scriptClasses: Set<String> get() = holders.mapNotNullTo(LinkedHashSet()) { it.scriptClass }

    companion object {
        private val LOCK_WAITS = setOf(
            "acquireWriteIntentPermit", "acquireWriteActionPermit", "upgradeWritePermit",
            "upgradeWritePermitSuspending", "acquireReadPermit",
        )
        private val READ_ENTRIES = setOf("tryRunReadAction", "runReadAction", "insideReadAction")
        private val HIDDEN_PREFIXES = listOf(
            "java.", "jdk.", "sun.", "kotlin.", "kotlinx.",
            "com.intellij.concurrency.", "com.intellij.openapi.progress.", "com.intellij.openapi.application.",
            "com.intellij.platform.locking.", "com.intellij.core.rwmutex.", "com.intellij.util.concurrency.",
        )
        const val SCRIPT_CLASS_PREFIX = "Script__jonnyzzz_"
        private const val MAX_HOLDERS = 4
        private const val MAX_FRAMES = 5

        fun of(threads: List<ThreadSample>): FreezeAnalysis {
            val ui = threads.firstOrNull { isUiThread(it) }
            val writer = threads.firstOrNull { !isUiThread(it) && waitsForWrite(it) }?.name
            val holders = threads
                .filter { !isUiThread(it) && !waitsForLock(it) && holdsRead(it) }
                .map { holder(it) }
                .sortedWith(compareBy<ReadHolder>({ it.scriptClass == null }, { !it.running }))
                .take(MAX_HOLDERS)
            val uiWaits = ui != null && waitsForLock(ui)
            return FreezeAnalysis(uiWaits, if (ui == null || uiWaits) emptyList() else doing(ui), writer, holders)
        }

        private fun isUiThread(t: ThreadSample) = t.name.startsWith("AWT-EventQueue")

        private fun lockFrame(f: StackTraceElement) = f.methodName in LOCK_WAITS &&
            (f.className.startsWith("com.intellij.core.rwmutex.") || f.className.startsWith("com.intellij.platform.locking."))

        private fun waitsForLock(t: ThreadSample) = t.frames.any { lockFrame(it) }

        private fun waitsForWrite(t: ThreadSample) =
            t.frames.any { lockFrame(it) && it.methodName != "acquireReadPermit" }

        private fun holdsRead(t: ThreadSample) =
            t.frames.any { it.methodName in READ_ENTRIES && it.className.startsWith("com.intellij.") }

        private fun doing(t: ThreadSample): List<String> = t.frames
            .filter { f -> HIDDEN_PREFIXES.none { f.className.startsWith(it) } && !f.className.startsWith(SCRIPT_CLASS_PREFIX) }
            .take(MAX_FRAMES)
            .map { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }

        private fun holder(t: ThreadSample): ReadHolder {
            val script = t.frames.firstOrNull { it.className.startsWith(SCRIPT_CLASS_PREFIX) }
            return ReadHolder(
                thread = t.name,
                running = t.state == Thread.State.RUNNABLE,
                scriptClass = script?.className?.substringBefore('$'),
                scriptLine = script?.let { "${it.fileName}:${it.lineNumber}" },
                doing = doing(t),
            )
        }
    }
}
