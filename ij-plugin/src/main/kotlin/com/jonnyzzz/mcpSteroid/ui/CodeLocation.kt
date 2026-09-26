/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

/**
 * Turns a code location into a range of a document's text: a caret (an empty range) for a line and column or a
 * symbol, a selection for a snippet. Works on the text alone, so it needs no index and no language support. The
 * text is a Document's, whose lines always end with `\n`.
 */
object CodeLocation {
    fun resolve(text: String, line: Int? = null, column: Int? = null, symbol: String? = null, snippet: String? = null, nth: Int = 0): IntRange {
        line?.let { return caretAt(text, it, column ?: 1) }
        symbol?.let { wanted ->
            val starts = Regex("(?<![\\w$])" + Regex.escape(wanted) + "(?![\\w$])").findAll(text).map { it.range.first }.toList()
            val start = pick(starts, nth, "symbol \"$wanted\"")
            return start until start
        }
        snippet?.let { wanted ->
            val starts = generateSequence(text.indexOf(wanted).takeIf { it >= 0 }) { from ->
                text.indexOf(wanted, from + 1).takeIf { it >= 0 }
            }.toList()
            val start = pick(starts, nth, "text \"${wanted.take(40)}\"")
            return start until start + wanted.length
        }
        throw UiStepFailure("give a line, a symbol or a text")
    }

    private fun caretAt(text: String, line: Int, column: Int): IntRange {
        // As the editor counts them: a final newline starts an empty last line.
        val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        if (line > lineStarts.size) throw UiStepFailure("line $line is past the end: the file has ${lineStarts.size} lines")
        val start = lineStarts[line - 1]
        val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
        val offset = (start + column - 1).coerceAtMost(end)
        return offset until offset
    }

    private fun pick(starts: List<Int>, nth: Int, what: String): Int =
        starts.getOrNull(nth) ?: throw UiStepFailure(
            if (starts.isEmpty()) "$what not found (found 0)" else "$what: nth $nth asked, found ${starts.size}"
        )
}
