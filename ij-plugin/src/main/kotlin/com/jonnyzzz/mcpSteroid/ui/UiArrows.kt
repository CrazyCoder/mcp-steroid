/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiArrowHead
import com.jonnyzzz.mcpSteroid.server.UiArrowSide
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.Shape
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Arrow geometry: where a head and a tail go, where the callout sits, and the shapes to draw. Screen coordinates in logical pixels. */
object UiArrows {
    /** The space between an arrow's head and the outline it points at, as a diagram tool leaves between a bound arrow and its shape. */
    const val HEAD_GAP = 3

    /** The space between a badge and its label. */
    private const val GAP = 4

    /** White text on a fill whose contrast with white is under this ratio reads poorly: such a light fill takes black text. */
    private const val MIN_WHITE_CONTRAST = 3.0

    /** Where the head goes on [box], the outline's box, for a tail on [side]: an edge's middle, or a corner for a diagonal. */
    fun head(box: Rectangle, side: UiArrowSide): Point = Point(
        when (side.dx) {
            -1 -> box.x - HEAD_GAP
            1 -> box.x + box.width + HEAD_GAP
            else -> box.x + box.width / 2
        },
        when (side.dy) {
            -1 -> box.y - HEAD_GAP
            1 -> box.y + box.height + HEAD_GAP
            else -> box.y + box.height / 2
        },
    )

    /** Where the tail goes, [length] from [head] toward [side]; a diagonal goes at 45 degrees. */
    fun tail(head: Point, side: UiArrowSide, length: Int): Point {
        val step = if (side.dx != 0 && side.dy != 0) length / sqrt(2.0) else length.toDouble()
        return Point(head.x + (side.dx * step).roundToInt(), head.y + (side.dy * step).roundToInt())
    }

    /**
     * The badge, a square of [badge], and the label, [labelWidth] wide, at [tail], reading away from the target on
     * [side]: the badge centred on the tail and the label past it, or the label alone starting at the tail. Either is
     * null when the mark has none; a label alone is [labelHeight] high.
     */
    fun callout(tail: Point, side: UiArrowSide, badge: Int?, labelWidth: Int?, labelHeight: Int): Pair<Rectangle?, Rectangle?> {
        val b = badge?.let { Rectangle(tail.x - it / 2, tail.y - it / 2, it, it) }
        val w = labelWidth ?: return b to null
        val h = b?.height ?: labelHeight
        val y = b?.y ?: (tail.y - h / 2)
        val label = when {
            b != null && side.dx < 0 -> Rectangle(b.x - GAP - w, y, w, h)
            b != null -> Rectangle(b.x + b.width + GAP, y, w, h)
            side.dx < 0 -> Rectangle(tail.x - w, y, w, h)
            side.dx > 0 -> Rectangle(tail.x, y, w, h)
            else -> Rectangle(tail.x - w / 2, if (side.dy < 0) tail.y - h else tail.y, w, h)
        }
        return b to label
    }

    /** The shaft and the head of an arrow from [tail] to [head] for a line [width] wide; no head for [UiArrowHead.NONE]. */
    fun shapes(tail: Point, head: Point, headStyle: UiArrowHead, width: Float): Pair<Shape, Shape?> {
        val angle = atan2((head.y - tail.y).toDouble(), (head.x - tail.x).toDouble())
        val length = 4.0 * width + 6
        val half = 1.5 * width + 2
        // [back] along the shaft from the head, and [across] it to one side.
        fun at(back: Double, across: Double) = Point2D.Double(
            head.x - back * cos(angle) - across * sin(angle), head.y - back * sin(angle) + across * cos(angle),
        )
        // A filled head covers the shaft's end: the shaft stops inside it, so its round cap does not poke out of the tip.
        val end = if (headStyle == UiArrowHead.FILLED) at(length * 0.8, 0.0) else Point2D.Double(head.x.toDouble(), head.y.toDouble())
        val shaft = Line2D.Double(tail.x.toDouble(), tail.y.toDouble(), end.x, end.y)
        val left = at(length, half)
        val right = at(length, -half)
        val shape = when (headStyle) {
            UiArrowHead.NONE -> null
            UiArrowHead.OPEN -> Path2D.Double().apply { moveTo(left.x, left.y); lineTo(head.x.toDouble(), head.y.toDouble()); lineTo(right.x, right.y) }
            UiArrowHead.FILLED -> Path2D.Double().apply {
                moveTo(head.x.toDouble(), head.y.toDouble()); lineTo(left.x, left.y); lineTo(right.x, right.y); closePath()
            }
        }
        return shaft to shape
    }

    /** White, or black on a fill so light that white text on it reads poorly, such as yellow or orange. */
    fun textOn(fill: Color): Color {
        fun channel(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val luminance = 0.2126 * channel(fill.red) + 0.7152 * channel(fill.green) + 0.0722 * channel(fill.blue)
        return if (1.05 / (luminance + 0.05) >= MIN_WHITE_CONTRAST) Color.WHITE else Color.BLACK
    }
}
