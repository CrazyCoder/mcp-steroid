/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * A replay of several scenario files in one steroid_ui call: `scenario` names a folder, whose `*.scenario.json`
 * files at any depth replay in path order, or a JSON array of paths, each a file or a folder. Each file replays as
 * its own call would, and the result starts with a summary of one verdict per file.
 */
object UiScenarioBatch {
    const val SUFFIX = ".scenario.json"
    /** More files than this in one call is almost always a wrong folder, such as the whole workspace. */
    const val MAX_FILES = 100

    private val VERDICTS = UiVerdict.Kind.entries.map { it.name.replace('_', ' ') }

    /**
     * The scenario files [spec] names, or null when it names a single file, which replays as a plain scenario call.
     * Relative paths resolve against [base]. Throws [IllegalArgumentException] for a malformed list, a folder without
     * scenario files, or more than [MAX_FILES] of them.
     */
    fun expand(spec: String, base: Path?): List<Path>? {
        val text = spec.trim()
        val listed = text.startsWith("[")
        val names = if (listed) parseList(text) else listOf(text)
        val paths = names.map { name -> Path.of(name).let { p -> if (p.isAbsolute || base == null) p else base.resolve(p) } }
        if (!listed && !paths.single().isDirectory()) return null
        val files = paths.flatMap { p ->
            if (p.isDirectory()) Files.walk(p).use { s -> s.filter { it.isRegularFile() && it.name.endsWith(SUFFIX) }.sorted().toList() }
                .also { require(it.isNotEmpty()) { "no *$SUFFIX file under $p" } }
            else listOf(p)
        }.distinct()
        require(files.isNotEmpty()) { "the scenario list is empty" }
        require(files.size <= MAX_FILES) { "${files.size} scenario files, more than the $MAX_FILES one call replays; name a narrower folder" }
        return files
    }

    private fun parseList(text: String): List<String> {
        val array = try {
            Json.parseToJsonElement(text) as? JsonArray
        } catch (e: SerializationException) {
            null
        } ?: throw IllegalArgumentException("scenario starts with [ but is not a JSON array of paths")
        return array.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: throw IllegalArgumentException("each scenario in the list is a path string") }
    }

    /**
     * The verdict line of one file's report, its ERROR line, or a note that it has neither. A verdict reads
     * `KIND: ...` or `KIND at step N: ...`, as [UiVerdict.of] writes it; a failed step's own line, `FAILED step N ...`,
     * is not one.
     */
    fun verdictOf(report: String): String {
        val lines = report.lines()
        return lines.lastOrNull { l -> VERDICTS.any { l.startsWith("$it:") || l.startsWith("$it at step ") || l.startsWith("$it at setup step ") } }
            ?: lines.firstOrNull { it.startsWith("ERROR") }
            ?: "no verdict: see its report below"
    }

    /** Whether a verdict line means the file needs a look: a failure, a broken run, or an error. */
    fun isBad(verdict: String): Boolean = verdict.startsWith("FAILED") || verdict.startsWith("BROKEN") || verdict.startsWith("ERROR") || verdict.startsWith("no verdict")

    /**
     * The batch result: a count per verdict kind, one line per file, then each file's full report under its name.
     * [reports] pairs each file with its report. A file is named relative to [base] when every file is under it, and
     * otherwise relative to the folder the files share.
     */
    fun render(reports: List<Pair<Path, String>>, base: Path?): String = buildString {
        val files = reports.map { it.first }
        val root = base?.takeIf { b -> files.all { it.startsWith(b) } } ?: commonFolder(files)
        val named = reports.map { (file, report) -> shortName(file, root) to report }
        val verdicts = named.map { (_, report) -> verdictOf(report) }
        val counts = verdicts.groupingBy { v -> VERDICTS.firstOrNull { v.startsWith(it) } ?: if (v.startsWith("ERROR")) "ERROR" else "NO VERDICT" }.eachCount()
        append("replayed ${reports.size} scenario file(s): ").append(counts.entries.joinToString { "${it.value} ${it.key}" })
        for ((i, pair) in named.withIndex()) append("\n- ").append(pair.first).append(": ").append(verdicts[i])
        for ((name, report) in named) append("\n\n== ").append(name).append(" ==\n").append(report.trim())
    }

    private fun shortName(file: Path, root: Path?): String =
        root?.takeIf { file.startsWith(it) && file != it }?.relativize(file)?.toString()?.replace('\\', '/') ?: file.toString()

    /** The deepest folder that holds every file, or null when they share none. */
    private fun commonFolder(files: List<Path>): Path? =
        files.map { it.toAbsolutePath().parent }.reduceOrNull { a, b ->
            var p: Path? = a
            while (p != null && !b.startsWith(p)) p = p.parent
            p ?: return null
        }
}
