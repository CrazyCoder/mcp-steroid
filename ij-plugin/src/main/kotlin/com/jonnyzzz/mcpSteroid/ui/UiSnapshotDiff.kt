/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

/**
 * What changed between two snapshot texts, window by window. A window that closed is one line; a window that
 * opened is listed whole, as a snapshot lists it. In a window that stayed, removed lines are marked `-` and added
 * lines `+`, without their indentation, under the window's header when several windows show. Refs identify a
 * control across the two snapshots, so a changed state shows as its old and new line.
 *
 * Not counted as changes: the keyboard focus, which moves with nearly every step; wrapper panels, which show
 * nothing; and widgets that change on their own, such as the memory indicator.
 */
object UiSnapshotDiff {
    private val VOLATILE_CLASSES = setOf("MemoryUsagePanelImpl", "MemoryUsagePanel")
    private val WRAPPER = Regex("^[\\w$]+$")

    private class Block(val header: String, val lines: List<String>)

    fun diff(before: String, after: String): String {
        val old = blocks(before)
        val new = blocks(after)
        val several = (old.keys + new.keys).size > 1
        val out = mutableListOf<String>()
        for ((id, block) in old) {
            if (id !in new) out += "- ${block.header.substringBefore(" source=")} closed"
        }
        for ((id, block) in new) {
            val was = old[id]
            if (was == null) {
                out += "+ ${block.header}"
                out += block.lines
                continue
            }
            val oldLines = changeLines(was.lines)
            val newLines = changeLines(block.lines)
            val changes = subtract(oldLines, newLines).map { "- $it" } + subtract(newLines, oldLines).map { "+ $it" }
            if (changes.isNotEmpty() && several) out += block.header.substringBefore(" source=") + ":"
            out += changes
        }
        return out.joinToString("\n")
    }

    /** The windows of a snapshot text by window id, in order. Text before the first window header is ignored. */
    private fun blocks(text: String): LinkedHashMap<String, Block> {
        val result = LinkedHashMap<String, Block>()
        var header: String? = null
        val lines = mutableListOf<String>()
        fun flush() {
            val h = header ?: return
            result[h.removePrefix("window ").substringBefore(' ')] = Block(h, lines.toList())
        }
        for (line in text.lines()) {
            if (line.startsWith("window ")) {
                flush()
                header = line
                lines.clear()
            } else if (line.isNotBlank() && header != null) {
                lines += line
            }
        }
        flush()
        return result
    }

    private fun changeLines(lines: List<String>): List<String> = lines
        .map { it.replace(" [focused]", "").trim().removePrefix("- ") }
        .filter { it.isNotEmpty() && !WRAPPER.matches(it) && it.substringBefore(' ') !in VOLATILE_CLASSES }

    /** The lines of [a] not matched by a line of [b], counting repeats. */
    private fun subtract(a: List<String>, b: List<String>): List<String> {
        val left = b.groupingBy { it }.eachCount().toMutableMap()
        return a.filter { line ->
            val n = left[line] ?: 0
            if (n > 0) {
                left[line] = n - 1
                false
            } else {
                true
            }
        }
    }
}
