/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import java.nio.file.Path

/**
 * The IDE exceptions captured during one execution, in two forms per exception: a short summary for the
 * script output, and the full stack trace to append to [logFile] in the execution folder.
 *
 * Most captured exceptions come from other plugins or the platform rather than the script, and a full
 * stack trace runs to dozens of lines, so the output keeps only what identifies the exception and names
 * the file that has the rest. A repeat of an exception already summarized is one line, and so is every
 * distinct exception after the first few. Past [MAX_PRINTED] exceptions the output gets one notice and
 * then nothing more, so a script that logs errors in a loop stays readable; the log keeps them all.
 */
internal class CapturedExceptionReport(private val logFile: Path) {
    /** [summary] is null once the output cap is reached; [logText] always goes to the log file. */
    class Entry(val summary: String?, val logText: String)

    private val firstNumberByKey = mutableMapOf<String, Int>()
    private var count = 0

    /** Records [ex] and returns what to print and what to append to the log. */
    fun add(ex: CapturedIdeException): Entry {
        val number = ++count
        val plugin = ex.pluginId?.let { ", plugin $it" } ?: ""
        val logText = buildString {
            append("=== IDE exception #$number at ${ex.timestamp}$plugin ===\n")
            ex.message?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
            append(ex.stacktrace.trimEnd()).append("\n\n")
        }
        return Entry(summarize(ex, number, plugin), logText)
    }

    private fun summarize(ex: CapturedIdeException, number: Int, plugin: String): String? {
        if (number > MAX_PRINTED + 1) return null
        if (number == MAX_PRINTED + 1) {
            return "IDE exceptions from #$number on are not printed here; see $logFile\n"
        }

        val frames = ex.stacktrace.lineSequence().map { it.trim() }.filter { it.startsWith("at ") }.toList()
        val headline = (ex.message?.takeIf { it.isNotBlank() } ?: ex.stacktrace.lineSequence().firstOrNull().orEmpty())
            .lineSequence().first().let { if (it.length > MESSAGE_LIMIT) it.take(MESSAGE_LIMIT) + "..." else it }

        val key = headline + "\n" + frames.take(KEY_FRAMES).joinToString("\n")
        firstNumberByKey[key]?.let { first ->
            return "IDE exception #$number is the same as #$first. Full stack traces: $logFile\n"
        }
        firstNumberByKey[key] = number
        if (firstNumberByKey.size > FULL_SUMMARIES) {
            return "IDE exception #$number$plugin: $headline. Full stack traces: $logFile\n"
        }

        return buildString {
            append("IDE exception #$number$plugin: $headline\n")
            for (frame in frames.take(SUMMARY_FRAMES)) append("  $frame\n")
            val more = frames.size - SUMMARY_FRAMES
            if (more > 0) append("  ... $more more frames\n")
            append("  Full stack trace: $logFile\n")
        }
    }

    companion object {
        const val FILE_NAME = "ide-exceptions.txt"
        private const val MAX_PRINTED = 50
        private const val FULL_SUMMARIES = 5
        private const val SUMMARY_FRAMES = 3
        private const val KEY_FRAMES = 5
        private const val MESSAGE_LIMIT = 300
    }
}
