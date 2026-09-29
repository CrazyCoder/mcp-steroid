/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import java.awt.Point
import java.awt.Rectangle

/**
 * The area a code highlight outlines in an editor, in its content component's coordinates, as the editor paints the
 * text: soft wraps, folds and inlays included. Call on the EDT, except [outline].
 */
object UiCodeRange {
    /**
     * One visual line of a range: the x of its first character that is not whitespace, or null when it has none or
     * continues a wrapped line, the x where its text ends (0 for a blank line), and its top and bottom.
     */
    data class LineSpan(val left: Int?, val right: Int, val top: Int, val bottom: Int) {
        val hasText get() = left != null || right > 0
    }

    /** The box from the leftmost text to the widest line, over the lines from the first with text to the last; null for none. */
    fun outline(spans: List<LineSpan>): Rectangle? {
        val text = spans.filter { it.hasText }
        if (text.isEmpty()) return null
        val left = text.mapNotNull { it.left }.minOrNull() ?: return null
        val right = text.maxOf { it.right }
        val top = text.minOf { it.top }
        val bottom = text.maxOf { it.bottom }
        return Rectangle(left, top, maxOf(1, right - left), bottom - top)
    }

    /** The area of lines [range], 1-based, of [editor]'s document. A range with no text, or past the end, fails. */
    fun linesArea(editor: Editor, range: IntRange): Rectangle {
        val doc = editor.document
        if (range.last > doc.lineCount) throw UiStepFailure("lines ${range.first}-${range.last} run past the end: the file has ${doc.lineCount} lines")
        val spans = mutableListOf<LineSpan>()
        for (line in range) {
            val start = doc.getLineStartOffset(line - 1)
            val end = doc.getLineEndOffset(line - 1)
            // A line folded into the one above it shows nothing of its own.
            val fold = editor.foldingModel.getCollapsedRegionAtOffset(start)
            if (fold != null && fold.startOffset < start) continue
            val first = (start until end).firstOrNull { !doc.charsSequence[it].isWhitespace() }
            val firstVisual = editor.offsetToVisualPosition(start).line
            val lastVisual = editor.offsetToVisualPosition(end).line
            for (v in firstVisual..lastVisual) {
                val top = editor.visualLineToY(v)
                val right = editor.visualPositionToXY(VisualPosition(v, EditorUtil.getLastVisualLineColumnNumber(editor, v))).x
                val left = if (v == firstVisual && first != null) editor.offsetToXY(first).x else null
                spans += LineSpan(left, if (first != null) right else 0, top, top + editor.lineHeight)
            }
        }
        return outline(spans) ?: throw UiStepFailure("lines ${range.first}-${range.last} hold no text")
    }

    /** The area of the [nth] occurrence of [symbol] as a whole word. */
    fun symbolArea(editor: Editor, symbol: String, nth: Int): Rectangle {
        val start = CodeLocation.pick(CodeLocation.symbolStarts(editor.document.text, symbol), nth, "symbol \"$symbol\"")
        val from = editor.offsetToXY(start)
        val to = editor.offsetToXY(start + symbol.length)
        return Rectangle(from.x, from.y, maxOf(1, to.x - from.x), editor.lineHeight)
    }

    /**
     * The text of [lines], 0-based, of [editor]'s document: for each visual line with text, the box from its first
     * character that is not whitespace to its end. A soft-wrapped line has one on each of its visual lines. A
     * picture's badges and arrows keep off them.
     */
    fun textSpans(editor: Editor, lines: IntRange): List<Rectangle> {
        val doc = editor.document
        return lines.filter { it in 0 until doc.lineCount }.flatMap { line ->
            val start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            val cuts = listOf(start) + editor.softWrapModel.getSoftWrapsForLine(line).map { it.start } + end
            cuts.zipWithNext().mapNotNull { (a, b) ->
                val first = (a until b).firstOrNull { !doc.charsSequence[it].isWhitespace() } ?: return@mapNotNull null
                val from = editor.offsetToXY(first)
                // The end of a visual line that wraps is before the wrap: past it, the offset is on the next visual line.
                val to = editor.offsetToXY(b, false, b != end)
                Rectangle(from.x, from.y, maxOf(1, to.x - from.x), editor.lineHeight)
            }
        }
    }

    /** Where a click at the caret lands: just right of it, halfway down its line. */
    fun caretPoint(editor: Editor): Point = nudged(editor, editor.visualPositionToXY(editor.caretModel.visualPosition))

    /** Where a click at [offset] lands, as [caretPoint] places it, whatever the caret shows yet. */
    fun offsetPoint(editor: Editor, offset: Int): Point = nudged(editor, editor.offsetToXY(offset))

    private fun nudged(editor: Editor, p: Point) = Point(p.x + CARET_NUDGE, p.y + editor.lineHeight / 2)

    /**
     * Scrolls [editor] so that [area] shows in the middle of its view, unless it shows whole already. An area taller
     * than the view fails with the view's height in lines.
     */
    fun bringIntoView(editor: Editor, area: Rectangle, what: String) {
        val visible = editor.scrollingModel.visibleArea
        if (area.height > visible.height) {
            throw UiStepFailure("$what is ${area.height} px high and the editor shows ${visible.height} px, ${visible.height / editor.lineHeight} lines; outline fewer lines")
        }
        if (visible.contains(area)) return
        val middle = editor.xyToLogicalPosition(Point(area.x, area.y + area.height / 2))
        editor.scrollingModel.disableAnimation()
        try {
            editor.scrollingModel.scrollTo(LogicalPosition(middle.line, 0), ScrollType.CENTER)
        } finally {
            editor.scrollingModel.enableAnimation()
        }
    }

    /**
     * Hides [editor]'s caret and the highlight of its row for a picture; the returned function puts them back as they
     * were.
     */
    fun hideCaret(editor: Editor): () -> Unit {
        val rowShown = editor.settings.isCaretRowShown
        editor.settings.isCaretRowShown = false
        // setCaretEnabled answers the state it replaced, so a caret that was off stays off.
        val caretEnabled = (editor as? EditorEx)?.setCaretEnabled(false)
        return {
            editor.settings.isCaretRowShown = rowShown
            caretEnabled?.let { (editor as EditorEx).setCaretEnabled(it) }
        }
    }

    /** Pixels right of the caret a click goes, so it lands on the character after it rather than on the caret's edge. */
    private const val CARET_NUDGE = 2
}
