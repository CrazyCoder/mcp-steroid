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

    /** A copy of [image] with each mark outlined and labelled with its ref. */
    fun draw(image: BufferedImage, marks: List<Mark>): BufferedImage {
        val copy = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = copy.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, maxOf(10, image.height / 90))
            g.stroke = BasicStroke(2f)
            val metrics = g.fontMetrics
            for (mark in marks) {
                val b = mark.bounds
                g.color = OUTLINE
                g.drawRect(b.x, b.y, b.width, b.height)
                val labelWidth = metrics.stringWidth(mark.ref) + 4
                val labelHeight = metrics.height
                val labelY = maxOf(0, b.y - labelHeight)
                g.fillRect(b.x, labelY, labelWidth, labelHeight)
                g.color = Color.WHITE
                g.drawString(mark.ref, b.x + 2, labelY + metrics.ascent)
            }
        } finally {
            g.dispose()
        }
        return copy
    }
}
