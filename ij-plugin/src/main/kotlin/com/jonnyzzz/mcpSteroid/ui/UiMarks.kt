/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.components.service
import com.intellij.ui.components.GradientViewport
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JTabbedPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Ref labels drawn over a screenshot, so a control seen in the picture can be addressed by its ref. */
object UiMarks {
    /**
     * A labelled area. A [row] of a list, tree or table spans the row in view and is labelled at its right end,
     * unoutlined; [content] is the part of a tree row its renderer paints, where its text is.
     */
    data class Mark(val ref: String, val bounds: Rectangle, val row: Boolean = false, val content: Rectangle? = null)

    @Suppress("UseJBColor")
    private val OUTLINE = Color(0xE0, 0x1B, 0x84)

    /**
     * The interactive controls of [captured]'s snapshot and the rows and tabs in view, in image pixels. A row's ref is
     * its control's ref and its index, such as `e12#3`; a combo box's open popup labels its items with the combo
     * box's ref. A control that a popup covers is left out. [scale] is image width over window width. EDT.
     */
    fun marks(captured: Component, scale: Double): List<Mark> {
        if (!captured.isShowing) return emptyList()
        val origin = captured.locationOnScreen
        val registry = service<UiRefs>().registry
        fun onScreen(c: Component, r: Rectangle): Rectangle = Rectangle(r).apply { translate(c.locationOnScreen.x, c.locationOnScreen.y) }
        return UiModel.build(captured).root.walk()
            .filter { it.interactive && it.component.isShowing && it.component !== captured }
            .flatMap { node ->
                val c = node.component
                val shown = visiblePart(c) ?: return@flatMap emptySequence()
                val ref = registry.refFor(c)
                val covers = coveringPopups(c)
                val overlays = viewportHeaders(c)
                val own = if (c is JTabbedPane) emptyList() else listOf(Mark(ref, onScreen(c, shown)))
                val rows = rowMarks(c, UiRows.comboOf(c)?.let(registry::refFor) ?: ref, shown)
                    .map { it.copy(bounds = onScreen(c, it.bounds), content = it.content?.let { r -> onScreen(c, r) }) }
                    .filterNot { mark -> overlays.any { covered(mark.bounds, it) } }
                (own + rows).filterNot { mark -> covers.any { covered(mark.bounds, it) } }.asSequence()
            }
            .map { mark ->
                fun toImage(r: Rectangle) = scale(Rectangle(r).apply { translate(-origin.x, -origin.y) }, scale)
                mark.copy(bounds = toImage(mark.bounds), content = mark.content?.let(::toImage))
            }
            .toList()
    }

    /** The marks of the rows and tabs of [c] in view, in [c]'s coordinates, labelled [ref]`#`index. */
    private fun rowMarks(c: Component, ref: String, shown: Rectangle): List<Mark> {
        val view = UiRows.view(c, MAX_ROW_MARKS) ?: return emptyList()
        return view.rows.mapNotNull { row ->
            val at = UiRows.bounds(c, row.index) ?: return@mapNotNull null
            val area = if (c is JTabbedPane) at else Rectangle(shown.x, at.y, shown.width, at.height)
            // A tree row's text can be told apart from its row; a list's and a table's cells span the row.
            val content = (c as? JTree)?.let { UiRows.rowText(it, row.index) }
            area.intersection(shown).takeUnless { it.isEmpty }?.let { Mark("$ref#${row.index}", it, row = c !is JTabbedPane, content = content) }
        }
    }

    /**
     * The screen bounds of the popups drawn inside [c]'s window above it, such as a combo box's list. A component as
     * large as the window's layered pane is an overlay rather than a popup, and covers nothing.
     */
    private fun coveringPopups(c: Component): List<Rectangle> {
        val layered = SwingUtilities.getRootPane(c)?.layeredPane ?: return emptyList()
        val top = generateSequence(c) { it.parent }.firstOrNull { it.parent === layered } ?: return emptyList()
        val layer = layered.getLayer(top)
        return layered.components
            .filter { it !== top && it.isShowing && layered.getLayer(it) > layer }
            .filterNot { it.width >= layered.width && it.height >= layered.height }
            .map { Rectangle(it.locationOnScreen, it.size) }
    }

    /**
     * The screen bounds of the header a [GradientViewport] paints over the top of its view, such as the group name
     * the Settings tree keeps in view while it scrolls, which hides the row under it. The header is not a component:
     * the viewport paints the one its protected `getHeader()` returns.
     */
    private fun viewportHeaders(c: Component): List<Rectangle> {
        val port = SwingUtilities.getAncestorOfClass(GradientViewport::class.java, c) as? GradientViewport ?: return emptyList()
        val header = runCatching {
            GradientViewport::class.java.getDeclaredMethod("getHeader").apply { isAccessible = true }.invoke(port) as? Component
        }.getOrNull() ?: return emptyList()
        return listOf(Rectangle(port.locationOnScreen, java.awt.Dimension(port.width, header.preferredSize.height)))
    }

    /** Whether [cover] hides at least half of [mark]. */
    fun covered(mark: Rectangle, cover: Rectangle): Boolean {
        val hidden = mark.intersection(cover).takeUnless { it.isEmpty } ?: return false
        return hidden.width.toLong() * hidden.height * 2 >= mark.width.toLong() * mark.height
    }

    /**
     * The part of [c] its scroll panes show, in its own coordinates, or null when none is: a control scrolled out of
     * view would otherwise be outlined over whatever the image shows there, such as a dialog's buttons.
     */
    private fun visiblePart(c: Component): Rectangle? {
        val shown = (c as? JComponent)?.visibleRect ?: Rectangle(0, 0, c.width, c.height)
        return shown.takeUnless { it.isEmpty }
    }

    fun scale(r: Rectangle, scale: Double) = Rectangle(
        (r.x * scale).roundToInt(), (r.y * scale).roundToInt(), (r.width * scale).roundToInt(), (r.height * scale).roundToInt(),
    )

    /**
     * A copy of [image] with each mark outlined and labelled with its ref. A label goes above its control when there
     * is room, else below it, and moves right past labels already drawn; it is translucent, so the text under it stays
     * readable. A control that covers a large part of the image, such as the editor, gets its label but no outline.
     * A row's label goes inside the row at its right end, or just past a tree row's text that reaches there.
     */
    fun draw(image: BufferedImage, marks: List<Mark>): BufferedImage {
        val copy = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = copy.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, maxOf(9, image.height / 130))
            g.stroke = BasicStroke(1.5f)
            val metrics = g.fontMetrics
            val placed = mutableListOf<Rectangle>()
            val large = image.width.toLong() * image.height / LARGE_FRACTION
            for (mark in marks) {
                val b = mark.bounds
                if (!mark.row && b.width.toLong() * b.height < large) {
                    g.color = OUTLINE
                    g.drawRect(b.x, b.y, b.width, b.height)
                }
                val size = Rectangle(0, 0, metrics.stringWidth(mark.ref) + 4, metrics.height)
                val label = if (mark.row) rowLabelSpot(b, mark.content, size, image.width) else labelSpot(b, size, image.width, image.height, placed)
                placed += label
                g.color = LABEL
                g.fillRect(label.x, label.y, label.width, label.height)
                g.color = Color.WHITE
                g.drawString(mark.ref, label.x + 2, label.y + metrics.ascent)
            }
        } finally {
            g.dispose()
        }
        return copy
    }

    /** Where a label of [size] goes for a control at [b]: above it, else below, moved right past [placed] labels. */
    private fun labelSpot(b: Rectangle, size: Rectangle, width: Int, height: Int, placed: List<Rectangle>): Rectangle {
        val y = if (b.y - size.height >= 0) b.y - size.height else minOf(b.y + b.height, height - size.height)
        val spot = Rectangle(b.x.coerceIn(0, maxOf(0, width - size.width)), y.coerceAtLeast(0), size.width, size.height)
        repeat(MAX_SHIFTS) {
            val hit = placed.firstOrNull { it.intersects(spot) } ?: return spot
            spot.x = hit.x + hit.width + 1
            if (spot.x + spot.width > width) return spot
        }
        return spot
    }

    /**
     * Where a label of [size] goes for a row at [b]: inside it at its right end, centred on the row. Where the row's
     * [content], such as a long tree row's text, reaches under that spot, the label goes just past the content instead,
     * out of the row if need be, so that it hides none of the text.
     */
    private fun rowLabelSpot(b: Rectangle, content: Rectangle?, size: Rectangle, width: Int): Rectangle {
        val y = b.y + (b.height - size.height) / 2
        val x = maxOf(b.x, b.x + b.width - size.width - 2)
        val end = content?.let { it.x + it.width } ?: return Rectangle(x, y, size.width, size.height)
        return Rectangle(if (end <= x) x else minOf(end + 2, width - size.width), y, size.width, size.height)
    }

    /** A control larger than this fraction of the image, as 1/n, is not outlined. */
    private const val LARGE_FRACTION = 5
    private const val MAX_SHIFTS = 8

    /** The most rows of one list, tree or table that get a mark: all that a window shows. */
    private const val MAX_ROW_MARKS = 500

    @Suppress("UseJBColor")
    private val LABEL = Color(0xE0, 0x1B, 0x84, 190)
}
