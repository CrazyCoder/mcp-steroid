/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

/**
 * What changed between two snapshot texts: removed lines marked `-`, then added lines marked `+`, without their
 * indentation. Refs identify a control across the two snapshots, so a changed state shows as its old and new line.
 * The keyboard focus moves with nearly every step, so it is not counted as a change.
 */
object UiSnapshotDiff {
    fun diff(before: String, after: String): String {
        val old = lines(before)
        val new = lines(after)
        val removed = subtract(old, new)
        val added = subtract(new, old)
        return (removed.map { "- $it" } + added.map { "+ $it" }).joinToString("\n")
    }

    private fun lines(text: String): List<String> = text.lines()
        .map { it.replace(" [focused]", "").trim().removePrefix("- ") }
        .filter { it.isNotEmpty() }

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
