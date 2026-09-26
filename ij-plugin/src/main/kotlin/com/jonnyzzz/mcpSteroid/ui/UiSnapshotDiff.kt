/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

/**
 * What changed between two snapshot texts, window by window. A window that closed is one line; a window that
 * opened is listed whole, as a snapshot lists it. In a window that stayed, removed lines are marked `-` and added
 * lines `+`, without their indentation, under the window's header when several windows show. Refs identify a
 * control across the two snapshots, so a changed state shows as its old and new line.
 *
 * Not counted as changes: the keyboard focus, which moves with nearly every step; wrapper panels, which show
 * nothing; and widgets that change on their own, the memory indicator and the background tasks in the status
 * bar, with everything under them. A control that came back with the same line under a new ref, as a menu bar
 * rebuilt while the IDE starts, is counted in one line instead of listed.
 */
object UiSnapshotDiff {
    private val VOLATILE_CLASSES = setOf("MemoryUsagePanelImpl", "MemoryUsagePanel", "InlineProgressPanel")
    private val WRAPPER = Regex("^[\\w$]+$")
    private val REF = Regex(" \\[ref=e\\d+]")

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
            val removed = subtract(oldLines, newLines)
            val added = subtract(newLines, oldLines)
            // The same line under a new ref is the same control rebuilt: its old ref is stale, nothing else changed.
            val removedKept = subtract(removed, added, key = { it.replace(REF, "") })
            val addedKept = subtract(added, removed, key = { it.replace(REF, "") })
            val rebuilt = added.size - addedKept.size
            val changes = removedKept.map { "- $it" } + addedKept.map { "+ $it" } +
                listOfNotNull(if (rebuilt > 0) "~ $rebuilt control(s) rebuilt with new refs; take a snapshot for them" else null)
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

    /** The lines a change is counted on: no wrappers, no volatile widgets or anything nested under one. */
    private fun changeLines(lines: List<String>): List<String> {
        val kept = mutableListOf<String>()
        var volatileDepth = -1
        for (raw in lines) {
            val depth = raw.length - raw.trimStart().length
            if (volatileDepth >= 0 && depth > volatileDepth) continue
            volatileDepth = -1
            val line = raw.replace(" [focused]", "").trim().removePrefix("- ")
            if (line.substringBefore(' ') in VOLATILE_CLASSES) {
                volatileDepth = depth
                continue
            }
            if (line.isNotEmpty() && !WRAPPER.matches(line)) kept += line
        }
        return kept
    }

    /** The lines of [a] not matched by a line of [b], compared by [key], counting repeats. */
    private fun subtract(a: List<String>, b: List<String>, key: (String) -> String = { it }): List<String> {
        val left = b.groupingBy(key).eachCount().toMutableMap()
        return a.filter { item ->
            val line = key(item)
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
