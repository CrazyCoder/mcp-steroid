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
    /** How much of the log's end is read: a run's lines fit, and a long log is not read whole on every retry. */
    private const val TAIL_BYTES = 8L * 1024 * 1024
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")
    private const val STAMP_LENGTH = 23
    private const val OWN_LOGGER = "mcpSteroid."

    private fun logFile(): Path = Path.of(PathManager.getLogPath(), "idea.log")

    /**
     * The log lines written since [sinceMs] that contain [text], with the lines each entry continues onto, such as
     * a stack trace; and how many entries were read.
     */
    suspend fun linesSince(sinceMs: Long, text: String): Pair<List<String>, Int> = withContext(Dispatchers.IO) {
        val file = logFile()
        if (!Files.isRegularFile(file)) return@withContext emptyList<String>() to 0
        val tail = RandomAccessFile(file.toFile(), "r").use { raf ->
            val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
        val zone = ZoneId.systemDefault()
        var inRun = false
        var entries = 0
        val found = mutableListOf<String>()
        var current: String? = null
        fun close() {
            // MCP Steroid logs each call's arguments, which hold the text looked for, so its own lines never count.
            current?.let { if (it.contains(text) && !it.lineSequence().first().contains(OWN_LOGGER)) found += it }
            current = null
        }
        for (line in tail.lineSequence()) {
            val at = stamp(line, zone)
            if (at != null) {
                close()
                inRun = at >= sinceMs
                if (inRun) {
                    entries++
                    current = line
                }
            } else if (inRun && current != null) {
                current += "\n" + line
            }
        }
        close()
        found to entries
    }

    private fun stamp(line: String, zone: ZoneId): Long? {
        if (line.length < STAMP_LENGTH || !line[0].isDigit()) return null
        return try {
            LocalDateTime.parse(line.substring(0, STAMP_LENGTH), STAMP).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** The debug level set for [category], and whether its debug and trace output is on now. */
    fun level(category: String): String {
        val set = LogLevelConfigurationManager.getInstance().getCategories().firstOrNull { same(it.category, category) }?.level
        val logger = Logger.getInstance(category)
        val now = when {
            logger.isTraceEnabled -> "trace"
            logger.isDebugEnabled -> "debug"
            else -> "info"
        }
        return "log $category = ${set?.name?.lowercase() ?: "default"} (logs $now and above)"
    }

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
