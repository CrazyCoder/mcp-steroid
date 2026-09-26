/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.components.service
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/** Ref labels drawn over a screenshot, so a control seen in the picture can be addressed by its ref. */
object UiMarks {
    data class Mark(val ref: String, val bounds: Rectangle)

    @Suppress("UseJBColor")
    private val OUTLINE = Color(0xE0, 0x1B, 0x84)

    /** The interactive controls of [captured]'s snapshot, in image pixels. [scale] is image width over window width. EDT. */
    fun marks(captured: Component, scale: Double): List<Mark> {
        if (!captured.isShowing) return emptyList()
        val origin = captured.locationOnScreen
        val registry = service<UiRefs>().registry
        return UiModel.build(captured).root.walk()
            .filter { it.interactive && it.component.isShowing && it.component !== captured }
            .map { node ->
                val at = node.component.locationOnScreen
                Mark(registry.refFor(node.component), scale(Rectangle(at.x - origin.x, at.y - origin.y, node.component.width, node.component.height), scale))
            }
            .toList()
    }

    fun scale(r: Rectangle, scale: Double) = Rectangle(
        (r.x * scale).roundToInt(), (r.y * scale).roundToInt(), (r.width * scale).roundToInt(), (r.height * scale).roundToInt(),
    )

    /**
     * A copy of [image] with each mark outlined and labelled with its ref. A label goes above its control when there
     * is room, else below it, and moves right past labels already drawn; it is translucent, so the text under it stays
     * readable. A control that covers a large part of the image, such as the editor, gets its label but no outline.
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
                if (b.width.toLong() * b.height < large) {
                    g.color = OUTLINE
                    g.drawRect(b.x, b.y, b.width, b.height)
                }
                val size = Rectangle(0, 0, metrics.stringWidth(mark.ref) + 4, metrics.height)
                val label = labelSpot(b, size, image.width, image.height, placed)
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

    /** A control larger than this fraction of the image, as 1/n, is not outlined. */
    private const val LARGE_FRACTION = 5
    private const val MAX_SHIFTS = 8

    @Suppress("UseJBColor")
    private val LABEL = Color(0xE0, 0x1B, 0x84, 190)
}
