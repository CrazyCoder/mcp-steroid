/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.diagnostic.logs.DebugLogLevel
import com.intellij.diagnostic.logs.LogCategory
import com.intellij.diagnostic.logs.LogLevelConfigurationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * This side's `idea.log`, read for the lines written since a run started, and the debug levels of log categories,
 * set as Help | Diagnostic Tools | Debug Log Settings sets them.
 */
internal object UiLogs {
    /** How far back from the log's end the run's first line is looked for. */
    private const val TAIL_BYTES = 8L * 1024 * 1024
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")
    private const val STAMP_LENGTH = 23
    private const val OWN_LOGGER = "mcpSteroid."

    /**
     * Where in the log the run that started at [since] begins. An expect retries every few milliseconds, so the log
     * is scanned for that point once, and each retry reads only what follows it.
     */
    private data class Start(val file: Path, val since: Long, val offset: Long)

    @Volatile
    private var start: Start? = null

    private fun logFile(): Path = Path.of(PathManager.getLogPath(), "idea.log")

    /**
     * The log entries written since [sinceMs] that contain [text], each with the lines it continues onto, such as a
     * stack trace, and how many entries were read. MCP Steroid's own lines echo the call's arguments, so they never
     * count.
     */
    suspend fun linesSince(sinceMs: Long, text: String): Pair<List<String>, Int> = withContext(Dispatchers.IO) {
        val file = logFile()
        if (!Files.isRegularFile(file)) return@withContext emptyList<String>() to 0
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val length = raf.length()
            val known = start?.takeIf { it.file == file && it.since == sinceMs && it.offset <= length }
            val offset = known?.offset ?: runStart(raf, length, sinceMs).also { start = Start(file, sinceMs, it) }
            raf.seek(offset)
            val bytes = ByteArray((length - offset).toInt())
            raf.readFully(bytes)
            entries(String(bytes, Charsets.UTF_8), text)
        }
    }

    /** The byte offset of the first entry stamped at or after [sinceMs], within the log's last [TAIL_BYTES]. */
    private fun runStart(raf: RandomAccessFile, length: Long, sinceMs: Long): Long {
        val from = (length - TAIL_BYTES).coerceAtLeast(0)
        raf.seek(from)
        val bytes = ByteArray((length - from).toInt())
        raf.readFully(bytes)
        val zone = ZoneId.systemDefault()
        var lineStart = 0
        while (lineStart < bytes.size) {
            val end = bytes.indexOf('\n'.code.toByte(), lineStart).let { if (it < 0) bytes.size else it }
            if (end - lineStart >= STAMP_LENGTH) {
                val at = stamp(String(bytes, lineStart, STAMP_LENGTH, Charsets.US_ASCII), zone)
                if (at != null && at >= sinceMs) return from + lineStart
            }
            lineStart = end + 1
        }
        return length
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    private fun entries(text: String, wanted: String): Pair<List<String>, Int> {
        val zone = ZoneId.systemDefault()
        val found = mutableListOf<String>()
        var entries = 0
        var current: String? = null
        fun close() {
            current?.let { if (it.contains(wanted) && !it.lineSequence().first().contains(OWN_LOGGER)) found += it }
            current = null
        }
        for (line in text.lineSequence()) {
            if (line.length >= STAMP_LENGTH && stamp(line.substring(0, STAMP_LENGTH), zone) != null) {
                close()
                entries++
                current = line
            } else if (current != null) {
                current += "\n" + line
            }
        }
        close()
        return found to entries
    }

    private fun stamp(head: String, zone: ZoneId): Long? {
        if (!head[0].isDigit()) return null
        return try {
            LocalDateTime.parse(head, STAMP).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** The debug level set for [category], and whether its debug and trace output is on now. */
    fun level(category: String): String {
        val logger = Logger.getInstance(category)
        val now = when {
            logger.isTraceEnabled -> "trace"
            logger.isDebugEnabled -> "debug"
            else -> "info"
        }
        return "log $category = ${levelSet(category)} (logs $now and above)"
    }

    /** The level set for [category] as a set step takes it: trace, debug, all, or default when none is set. */
    fun levelSet(category: String): String =
        LogLevelConfigurationManager.getInstance().getCategories().firstOrNull { same(it.category, category) }?.level?.name?.lowercase() ?: "default"

    /** Sets [category]'s debug level, or with `default` removes the level set for it; reports before and after. */
    fun setLevel(category: String, value: String): String {
        val manager = LogLevelConfigurationManager.getInstance()
        val before = level(category)
        val others = manager.getCategories().filterNot { same(it.category, category) }
        val wanted = when (value.lowercase()) {
            "trace" -> DebugLogLevel.TRACE
            "debug" -> DebugLogLevel.DEBUG
            "all" -> DebugLogLevel.ALL
            else -> null
        }
        manager.setCategories(if (wanted == null) others else others + LogCategory(category, wanted))
        return "${before.substringBefore(" (")} -> ${level(category).substringAfter(" = ")}"
    }

    /** Categories are written with or without the leading `#` that marks a class's logger; both name one category. */
    private fun same(a: String, b: String) = a.trimStart('#') == b.trimStart('#')
}
