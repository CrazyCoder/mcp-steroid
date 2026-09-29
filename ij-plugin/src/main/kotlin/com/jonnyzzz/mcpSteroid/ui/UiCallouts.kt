/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiArrow
import com.jonnyzzz.mcpSteroid.server.UiArrowHead
import com.jonnyzzz.mcpSteroid.server.UiArrowSide
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The marks drawn over a picture: an outline around each highlight, or a mouse pointer at a click point, with a number
 * badge and a label placed where they cover the least of other text, or at the tail of an arrow, on empty space.
 */
object UiCallouts {
    /**
     * A highlight: its number, its screen bounds, and the text drawn beside it. A [pointer] mark is a point, the
     * top left corner of [bounds], drawn as a mouse pointer: where a click goes. A mark not [numbered] has no badge,
     * for a picture of one area, or of areas with no order to follow; its label sits beside the outline. A [joined]
     * pointer is part of the step whose outline it touches, and has neither badge nor label.
     *
     * A mark with an [arrow] has its badge and label at the arrow's tail, away from the target, and without an
     * [outline] only the arrow marks it. A mark that is not [numberable] takes no number whatever the picture's
     * numbering. [color] and [width] are its outline's, arrow's, badge's and label's.
     */
    data class Mark(
        val number: Int, val bounds: Rectangle, val label: String?, val pointer: Boolean = false, val numbered: Boolean = true,
        val joined: Boolean = false, val arrow: UiArrow? = null, val outline: Boolean = true, val numberable: Boolean = true,
        val color: Color = OUTLINE, val width: Float = OUTLINE_WIDTH,
    )

    /**
     * [marks] as the steps of a picture. A click point on or at the edge of another mark's outline, such as the word a
     * right click went to, belongs to that mark's step: it is drawn as a bare pointer, without a badge, a label or an
     * arrow. The other marks that are numberable are numbered 1, 2, 3 when [numbered] says so, or by default when there
     * are several.
     */
    fun steps(marks: List<Mark>, numbered: Boolean?): List<Mark> {
        val boxes = marks.filterNot { it.pointer }.map { outlined(it.bounds).apply { grow(JOIN, JOIN) } }
        val joined = marks.map { m -> m.pointer && boxes.any { it.contains(m.bounds.location) } }
        val count = marks.indices.count { !joined[it] && marks[it].numberable }
        val number = (numbered ?: (count > 1)) && count > 0
        var next = 0
        return marks.mapIndexed { i, m ->
            when {
                joined[i] -> m.copy(label = null, numbered = false, joined = true, arrow = null)
                !m.numberable -> m.copy(numbered = false)
                else -> m.copy(number = ++next, numbered = number)
            }
        }
    }

    /**
     * Where the number badge of a mark at [mark] goes, a square of [size]: just left of it, centred on it for a mark of
     * ordinary height, level with its top for a tall one such as a list, so the badge points at the mark without
     * covering what lies above it. With no room on the left, an ordinary mark's badge goes right of it the same way,
     * and a tall mark's inside its top left corner: right of a tall mark, such as a list at a window's edge, lies the
     * next panel's content. It stays inside [within] and moves further out past [placed] badges and labels.
     *
     * [obstacles] are the text of other controls, such as the neighbouring tabs of a tab row: a spot beside the mark
     * that would cover one gives way to one below the mark's left edge, then above it. With no free spot, the rule
     * above decides.
     */
    fun badgeBounds(mark: Rectangle, size: Int, within: Rectangle, placed: List<Rectangle>, obstacles: List<Rectangle> = emptyList()): Rectangle {
        val tall = mark.height >= TALL * size
        val y = if (tall) mark.y else mark.y + (mark.height - size) / 2
        val left = Rectangle(mark.x - GAP - size, y, size, size)
        val right = Rectangle(mark.x + mark.width + GAP, y, size, size)
        val inside = Rectangle(mark.x + GAP, y, size, size)
        val spots = if (tall) listOf(left) else listOf(left, right)
        if (obstacles.isNotEmpty()) {
            val free = (spots + below(mark, size) + above(mark, size)).firstOrNull { spot ->
                within.contains(spot) && placed.none { it.intersects(spot) } && obstacles.none { it.intersects(spot) }
            }
            if (free != null) return free
        }
        val spot = Rectangle(spots.firstOrNull { within.contains(it) } ?: inside)
        spot.y = spot.y.coerceIn(within.y, maxOf(within.y, within.y + within.height - size))
        val step = if (spot.x < mark.x) -1 else 1
        repeat(MAX_SHIFTS) {
            val hit = placed.firstOrNull { it.intersects(spot) } ?: return spot
            val next = if (step < 0) hit.x - GAP - size else hit.x + hit.width + GAP
            if (next < within.x || next + size > within.x + within.width) return spot
            spot.x = next
        }
        return spot
    }

    private fun below(mark: Rectangle, size: Int) = Rectangle(mark.x, mark.y + mark.height + GAP, size, size)
    private fun above(mark: Rectangle, size: Int) = Rectangle(mark.x, mark.y - GAP - size, size, size)

    /**
     * A copy of [canvas] with each mark outlined, or drawn as a pointer, its number in a round badge beside it, and its
     * label, or both at the tail of its arrow; badges and labels keep off [obstacles], the text of other controls, where
     * there is room. [canvas] is the window as painted, before any mark: an arrow's callout looks for empty space in it.
     */
    fun highlight(canvas: UiCapture.Canvas, marks: List<Mark>, obstacles: List<Rectangle> = emptyList()): UiCapture.Canvas {
        val copy = BufferedImage(canvas.image.width, canvas.image.height, BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        try {
            g.drawImage(canvas.image, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.scale(canvas.scale, canvas.scale)
            g.translate(-canvas.origin.x, -canvas.origin.y)
            for ((mark, parts) in layout(canvas, marks, g, obstacles)) {
                val b = parts.outline
                if (mark.pointer) {
                    val arrow = pointer(b.x, b.y)
                    g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.color = EDGE
                    g.draw(arrow)
                    g.color = mark.color
                    g.fill(arrow)
                } else if (mark.outline) {
                    val outline = RoundRectangle2D.Float(b.x.toFloat(), b.y.toFloat(), b.width.toFloat(), b.height.toFloat(), ARC, ARC)
                    g.stroke = BasicStroke(mark.width + 2f)
                    g.color = EDGE
                    g.draw(outline)
                    g.stroke = BasicStroke(mark.width)
                    g.color = mark.color
                    g.draw(outline)
                }
                parts.arrow?.let { drawArrow(g, it, mark) }
                val text = UiArrows.textOn(mark.color)
                parts.badge?.let { badge ->
                    g.color = EDGE
                    g.fill(Ellipse2D.Float(badge.x - 1f, badge.y - 1f, badge.width + 2f, badge.height + 2f))
                    g.color = mark.color
                    g.fill(Ellipse2D.Float(badge.x.toFloat(), badge.y.toFloat(), badge.width.toFloat(), badge.height.toFloat()))
                    g.color = text
                    g.font = BADGE_FONT
                    val number = mark.number.toString()
                    val m = g.fontMetrics
                    g.drawString(number, badge.x + (badge.width - m.stringWidth(number)) / 2f, baseline(g, number, badge))
                }
                parts.label?.let { box ->
                    g.color = mark.color
                    g.fill(RoundRectangle2D.Float(box.x.toFloat(), box.y.toFloat(), box.width.toFloat(), box.height.toFloat(), ARC, ARC))
                    g.color = text
                    g.font = LABEL_FONT
                    g.drawString(mark.label!!, box.x + LABEL_PAD.toFloat(), baseline(g, mark.label, box))
                }
            }
        } finally {
            g.dispose()
        }
        return UiCapture.Canvas(copy, Point(canvas.origin), canvas.scale)
    }

    /**
     * The baseline that puts the glyphs of [text] in the vertical middle of [box], in the font [g] draws with. The
     * font's ascent and leading differ from one system font to another: centring its line height left the text of
     * the IDE's sans serif font low, touching the bottom of a label.
     */
    private fun baseline(g: Graphics2D, text: String, box: Rectangle): Float {
        val glyphs = g.font.createGlyphVector(g.fontRenderContext, text).visualBounds
        return (box.y + box.height / 2.0 - (glyphs.y + glyphs.height / 2)).toFloat()
    }

    /** An arrow's shaft and head in [mark]'s color and width, over a white edge as an outline has. */
    private fun drawArrow(g: Graphics2D, plan: ArrowPlan, mark: Mark) {
        val head = mark.arrow?.head ?: UiArrowHead.FILLED
        val (shaft, tip) = UiArrows.shapes(plan.tail, plan.head, head, mark.width)
        fun pass(color: Color, width: Float) {
            g.color = color
            g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(shaft)
            when (head) {
                UiArrowHead.FILLED -> { g.draw(tip!!); g.fill(tip) }
                UiArrowHead.OPEN -> g.draw(tip!!)
                UiArrowHead.NONE -> Unit
            }
        }
        pass(EDGE, mark.width + 2f)
        pass(mark.color, mark.width)
    }

    /** The screen area of [marks] with their badges, labels and arrows, placed as [highlight] places them. */
    fun markArea(canvas: UiCapture.Canvas, marks: List<Mark>, obstacles: List<Rectangle> = emptyList()): Rectangle {
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            return UiCapture.union(layout(canvas, marks, g, obstacles).flatMap { (mark, parts) ->
                listOfNotNull(parts.outline, parts.badge, parts.label, parts.arrow?.let { arrowBounds(it, mark) })
            })
        } finally {
            g.dispose()
        }
    }

    /** Where [marks]' arrows went, null for a mark without one, placed as [highlight] places them. */
    fun arrows(canvas: UiCapture.Canvas, marks: List<Mark>, obstacles: List<Rectangle> = emptyList()): List<ArrowPlan?> {
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            return layout(canvas, marks, g, obstacles).map { (_, parts) -> parts.arrow }
        } finally {
            g.dispose()
        }
    }

    /** The area an arrow paints: its shaft from tail to head, grown by its head's size. */
    private fun arrowBounds(plan: ArrowPlan, mark: Mark): Rectangle {
        val grow = (4 * mark.width + 8).toInt()
        return Rectangle(plan.head).union(Rectangle(plan.tail)).apply { grow(grow, grow) }
    }

    /** Where an arrow went: its head and tail, its side and length, and what placement changed of a forced side. */
    data class ArrowPlan(
        val head: Point, val tail: Point, val side: UiArrowSide, val length: Int,
        val flippedFrom: UiArrowSide? = null, val shortenedFrom: Int? = null,
    )

    /** [area] grown to hold the outline, badge, label and arrow of each of [marks] that lies in it, so a crop cuts none of them. */
    fun withMarks(canvas: UiCapture.Canvas, area: Rectangle, marks: List<Mark>, obstacles: List<Rectangle> = emptyList()): Rectangle {
        val inside = marks.filter { area.intersects(it.bounds) }
        return if (inside.isEmpty()) area else area.union(markArea(canvas, inside, obstacles))
    }

    /** A mouse pointer with its tip at ([x], [y]), [POINTER_W] x [POINTER_H] logical pixels. */
    private fun pointer(x: Int, y: Int): Path2D.Float {
        // The classic arrow: down the left edge, in to the tail, the tail, and back up to the tip along the slant.
        val shape = listOf(0 to 0, 0 to 16, 4 to 12, 7 to 18, 9 to 17, 6 to 11, 11 to 11)
        val sx = POINTER_W / 11f
        val sy = POINTER_H / 18f
        return Path2D.Float().apply {
            shape.forEachIndexed { i, (px, py) -> if (i == 0) moveTo(x + px * sx, y + py * sy) else lineTo(x + px * sx, y + py * sy) }
            closePath()
        }
    }

    private class Parts(val outline: Rectangle, val badge: Rectangle?, val label: Rectangle?, val arrow: ArrowPlan? = null)

    private class Placed(val plan: ArrowPlan, val badge: Rectangle?, val label: Rectangle?)

    /**
     * What a picture shows where: its background is its most common color, and a pixel that differs from it is content,
     * such as text, a control's frame, an icon or code. The picture must be the window as painted, before any mark.
     */
    private class Content(private val canvas: UiCapture.Canvas) {
        private val background: Int = run {
            val counts = HashMap<Int, Int>()
            val img = canvas.image
            for (y in 0 until img.height step SAMPLE) for (x in 0 until img.width step SAMPLE) counts.merge(img.getRGB(x, y) and 0xFFFFFF, 1, Int::plus)
            counts.maxByOrNull { it.value }?.key ?: 0xFFFFFF
        }

        /** About how many logical pixels of the screen area [r] show content, from a sample of its pixels. */
        fun busy(r: Rectangle): Long {
            val img = canvas.image
            val scale = canvas.scale
            val x0 = ((r.x - canvas.origin.x) * scale).toInt().coerceIn(0, img.width)
            val y0 = ((r.y - canvas.origin.y) * scale).toInt().coerceIn(0, img.height)
            val x1 = ((r.x + r.width - canvas.origin.x) * scale).toInt().coerceIn(0, img.width)
            val y1 = ((r.y + r.height - canvas.origin.y) * scale).toInt().coerceIn(0, img.height)
            var hits = 0L
            var seen = 0L
            for (y in y0 until y1 step SAMPLE) for (x in x0 until x1 step SAMPLE) {
                seen++
                if (differs(img.getRGB(x, y) and 0xFFFFFF, background)) hits++
            }
            return if (seen == 0L) 0L else hits * r.width.toLong() * r.height / seen
        }

        private fun differs(a: Int, b: Int): Boolean =
            abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) > TOLERANCE ||
                abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) > TOLERANCE ||
                abs((a and 0xFF) - (b and 0xFF)) > TOLERANCE

        private companion object {
            /** Every how many image pixels, across and down, a pixel is looked at. */
            const val SAMPLE = 2
            /**
             * How far a channel may stray from the background's and still read as background. A field's white on a light
             * panel, #FFFFFF on #F7F8FA, is a control, not empty space: the tolerance sits below that difference.
             */
            const val TOLERANCE = 3
        }
    }

    /**
     * Where the arrow of [mark], the one at [index] with its [outline], goes. With `auto`, the sides in
     * [UiArrowSide.AUTO_ORDER] at the arrow's length, then at 1.5 and 2 times it: the first whose callout is in the
     * picture and covers nothing ([free]) and whose shaft crosses no other outline, no [text] and no placed shaft, else
     * the one in the picture that covers least, a crossing counting as much as a covered badge. A forced side flips to the
     * opposite side when its callout leaves the picture, and is shortened, down to the shortest length, when neither
     * side fits; text does not move it, as the caller chose the side.
     */
    private fun placeArrow(
        mark: Mark, arrow: UiArrow, outline: Rectangle, index: Int, boxes: List<Rectangle>, text: List<Rectangle>, within: Rectangle, shafts: List<Line2D>,
        labelMetrics: java.awt.FontMetrics, free: (Rectangle) -> Boolean, covered: (Array<Rectangle?>) -> Long,
    ): Placed {
        val badgeSize = if (mark.numbered) BADGE else null
        val labelWidth = mark.label?.let { labelMetrics.stringWidth(it) + 2 * LABEL_PAD }
        fun plan(side: UiArrowSide, length: Int): Placed {
            // A pointer's arrow stops short of its tip, on the tail's side; any other stops short of its outline.
            val head = if (mark.pointer) Point(mark.bounds.x + side.dx * UiArrows.HEAD_GAP, mark.bounds.y + side.dy * UiArrows.HEAD_GAP)
            else UiArrows.head(outline, side)
            val tail = UiArrows.tail(head, side, length)
            val (badge, label) = UiArrows.callout(tail, side, badgeSize, labelWidth, BADGE)
            return Placed(ArrowPlan(head, tail, side, length), badge, label)
        }
        fun inside(p: Placed) = within.contains(p.plan.tail) && listOfNotNull(p.badge, p.label).all { within.contains(it) }
        fun crossings(p: Placed): Int {
            val shaft = Line2D.Double(p.plan.tail, p.plan.head)
            return boxes.withIndex().count { (j, b) -> j != index && shaft.intersects(b) } + text.count { shaft.intersects(it) } +
                shafts.count { it.intersectsLine(shaft) }
        }
        if (arrow.from == UiArrowSide.AUTO) {
            val tries = AUTO_LENGTHS.flatMap { f -> UiArrowSide.AUTO_ORDER.map { plan(it, (arrow.length * f).roundToInt()) } }
            return tries.firstOrNull { p -> inside(p) && crossings(p) == 0 && listOfNotNull(p.badge, p.label).all(free) }
                ?: tries.filter(::inside).minByOrNull { p -> covered(arrayOf(p.badge, p.label)) + crossings(p) * BADGE.toLong() * BADGE }
                ?: plan(UiArrowSide.RIGHT, UiArrow.LENGTHS.first)
        }
        val forced = plan(arrow.from, arrow.length)
        if (inside(forced)) return forced
        val flipped = plan(arrow.from.opposite, arrow.length)
        if (inside(flipped)) return Placed(flipped.plan.copy(flippedFrom = arrow.from), flipped.badge, flipped.label)
        val shorter = generateSequence(arrow.length - SHORTEN_STEP) { it - SHORTEN_STEP }.takeWhile { it >= UiArrow.LENGTHS.first }
            .map { plan(arrow.from, it) }.firstOrNull(::inside) ?: plan(arrow.from, UiArrow.LENGTHS.first)
        return Placed(shorter.plan.copy(shortenedFrom = arrow.length), shorter.badge, shorter.label)
    }

    /**
     * Where each mark's badge and label go, badges placed in order so that none covers another. A label goes right of
     * its mark on the badge's line, "(1) [control] label", or left of the badge when the picture has no room there.
     * Where that label would cover the text of another control, one of [obstacles], badge and label move below the
     * mark, or above it. A pointer mark's badge goes right of the pointer, else left of it with the label further
     * left, below or above. With no spot that covers nothing, they take the one that covers the least. A mark with an
     * arrow has its badge and label at the arrow's tail, as [placeArrow] finds it.
     */
    private fun layout(canvas: UiCapture.Canvas, marks: List<Mark>, g: Graphics2D, obstacles: List<Rectangle>): List<Pair<Mark, Parts>> {
        val placed = mutableListOf<Rectangle>()
        val placedShafts = mutableListOf<Line2D>()
        val content by lazy { Content(canvas) }
        val labelMetrics = g.getFontMetrics(LABEL_FONT)
        val within = canvas.bounds
        val boxes = outlineBoxes(marks)
        return marks.mapIndexed { i, mark ->
            val outline = boxes[i]
            // A control's own text lies inside its outline and is no obstacle to its own badge.
            val others = obstacles.filterNot { outline.contains(it) }
            fun free(r: Rectangle) = within.contains(r) && placed.none { it.intersects(r) } && others.none { it.intersects(r) }
            fun labelFor(badge: Rectangle, text: String): Rectangle {
                val width = labelMetrics.stringWidth(text) + 2 * LABEL_PAD
                val besideBadge = if (badge.x < outline.x) outline.x + outline.width + GAP else badge.x + badge.width + GAP
                val x = if (besideBadge + width <= within.x + within.width) besideBadge else badge.x - GAP - width
                return Rectangle(x, badge.y, width, badge.height)
            }
            // The pixels of other text and of placed badges a spot covers; one past the picture counts as covering all.
            fun covered(vararg parts: Rectangle?): Long = parts.filterNotNull().sumOf { r ->
                if (!within.contains(r)) OFF_PICTURE
                else (others + placed).sumOf { o -> r.intersection(o).takeUnless { it.isEmpty }?.let { it.width.toLong() * it.height } ?: 0L }
            }
            mark.arrow?.let { arrow ->
                // A callout goes where the picture is empty: besides the text of other controls, it keeps off whatever
                // the window paints there, such as a combo box, a field, an icon or code. A shaft may cross painted
                // content, as its line hides little of it, but not text.
                fun calloutFree(r: Rectangle) = free(r) && content.busy(r) <= r.width.toLong() * r.height / EMPTY_SHARE
                fun calloutCovered(parts: Array<Rectangle?>): Long = covered(*parts) + parts.filterNotNull().sumOf { content.busy(it) }
                val chosen = placeArrow(mark, arrow, outline, i, boxes, others, within, placedShafts, labelMetrics, ::calloutFree, ::calloutCovered)
                placedShafts += Line2D.Double(chosen.plan.tail, chosen.plan.head)
                chosen.badge?.let { placed += it }
                chosen.label?.let { placed += it }
                return@mapIndexed mark to Parts(outline, chosen.badge, chosen.label, chosen.plan)
            }
            if (!mark.numbered) {
                // No badge: the label alone goes right of the outline, level with its middle or the top of a tall one,
                // else left of it, below it or above it, where it covers no other text.
                val label = mark.label?.let { text ->
                    val width = labelMetrics.stringWidth(text) + 2 * LABEL_PAD
                    val y = if (outline.height >= TALL * BADGE) outline.y else outline.y + (outline.height - BADGE) / 2
                    val spots = listOf(
                        Rectangle(outline.x + outline.width + GAP, y, width, BADGE),
                        Rectangle(outline.x - GAP - width, y, width, BADGE),
                        Rectangle(outline.x, outline.y + outline.height + GAP, width, BADGE),
                        Rectangle(outline.x, outline.y - GAP - BADGE, width, BADGE),
                    )
                    spots.firstOrNull(::free) ?: spots.firstOrNull { within.contains(it) } ?: spots.first()
                }
                label?.let { placed += it }
                return@mapIndexed mark to Parts(outline, null, label)
            }
            fun spot(badge: Rectangle, leftward: Boolean = false): Pair<Rectangle, Rectangle?> = badge to mark.label?.let { text ->
                if (!leftward) return@let labelFor(badge, text)
                val width = labelMetrics.stringWidth(text) + 2 * LABEL_PAD
                Rectangle(badge.x - GAP - width, badge.y, width, badge.height)
            }
            val spots = if (mark.pointer) {
                // A menu opened by the click reaches right of the pointer, and below and above it: the left stays clear.
                val y = outline.y + (outline.height - BADGE) / 2
                listOf(
                    spot(Rectangle(outline.x + outline.width + GAP, y, BADGE, BADGE)),
                    spot(Rectangle(outline.x - GAP - BADGE, y, BADGE, BADGE), leftward = true),
                    spot(below(outline, BADGE)), spot(above(outline, BADGE)),
                )
            } else {
                listOf(spot(badgeBounds(outline, BADGE, within, placed, others)), spot(below(outline, BADGE)), spot(above(outline, BADGE)))
            }
            // The first spot whose badge and label cover nothing, else the one that covers the least.
            val (badge, label) = spots.firstOrNull { (b, l) -> free(b) && (l == null || free(l)) } ?: spots.minBy { (b, l) -> covered(b, l) }
            placed += badge
            label?.let { placed += it }
            mark to Parts(outline, badge, label)
        }
    }

    /**
     * The box each mark is drawn in: a pointer's arrow, or an outline as [outlines] spaces them. A pointer is no
     * neighbour of an outline: at the end of a clicked word, it would pull the word's outline in over its last letter.
     */
    fun outlineBoxes(marks: List<Mark>): List<Rectangle> {
        val boxes = outlines(marks.filterNot { it.pointer }.map { it.bounds }).iterator()
        return marks.map { m -> if (m.pointer) Rectangle(m.bounds.x, m.bounds.y, POINTER_W, POINTER_H) else boxes.next() }
    }

    /** The area an outline runs around: [b] grown by [PAD], so the control's own edge and text stay visible. */
    private fun outlined(b: Rectangle) = Rectangle(b.x - PAD, b.y - PAD, b.width + 2 * PAD, b.height + 2 * PAD)

    /**
     * The outline of each mark: grown by [PAD], except where two neighbours, such as checkboxes on stacked rows, would
     * touch or cross. There both pull back from the middle of the space between the two controls, which leaves a clear
     * gap between the outlines. That holds for controls whose bounds overlap a little, as Kotlin UI DSL controls reach
     * past what they paint into the next row; a mark mostly inside another keeps its padding.
     */
    fun outlines(marks: List<Rectangle>): List<Rectangle> {
        // Outlines closer than the clear gap meet on screen, since each line is drawn centred on its edge with a white
        // edge around it: they count as touching.
        val padded = marks.map { outlined(it).apply { grow(HALF_GAP, HALF_GAP) } }
        val out = marks.map(::outlined)
        for (i in marks.indices) for (j in marks.indices) {
            if (i == j || !padded[i].intersects(padded[j]) || nested(marks[i], marks[j])) continue
            val a = marks[i]
            val b = marks[j]
            val o = out[i]
            // Neighbours along the axis on which their centres lie further apart, relative to their sizes.
            val dy = abs(a.centerY - b.centerY) / ((a.height + b.height) / 2.0)
            val dx = abs(a.centerX - b.centerX) / ((a.width + b.width) / 2.0)
            // Only a's edge that faces b moves here; the pass with i and j swapped moves b's.
            if (dy >= dx) {
                if (a.centerY < b.centerY) {
                    val bottom = minOf(o.y + o.height, (a.y + a.height + b.y) / 2 - HALF_GAP)
                    o.height = maxOf(1, bottom - o.y)
                } else {
                    val top = maxOf(o.y, (b.y + b.height + a.y + 1) / 2 + HALF_GAP)
                    o.height = maxOf(1, o.y + o.height - top)
                    o.y = top
                }
            } else {
                if (a.centerX < b.centerX) {
                    val right = minOf(o.x + o.width, (a.x + a.width + b.x) / 2 - HALF_GAP)
                    o.width = maxOf(1, right - o.x)
                } else {
                    val left = maxOf(o.x, (b.x + b.width + a.x + 1) / 2 + HALF_GAP)
                    o.width = maxOf(1, o.x + o.width - left)
                    o.x = left
                }
            }
        }
        return out
    }

    /** Whether the overlap of [a] and [b] covers at least half of the smaller one: one lies mostly inside the other. */
    private fun nested(a: Rectangle, b: Rectangle): Boolean {
        val overlap = a.intersection(b).takeUnless { it.isEmpty } ?: return false
        val smaller = minOf(a.width.toLong() * a.height, b.width.toLong() * b.height)
        return overlap.width.toLong() * overlap.height * 2 >= smaller
    }

    @Suppress("UseJBColor")
    private val OUTLINE = Color(0xE5, 0x2B, 0x50)
    private const val PAD = 3
    /** Half the clear space left between the outlines of two neighbouring marks. */
    private const val HALF_GAP = 3

    @Suppress("UseJBColor")
    private val EDGE = Color(255, 255, 255, 220)
    private const val OUTLINE_WIDTH = 2.5f
    private const val ARC = 8f
    private const val BADGE = 18
    private const val LABEL_PAD = 5
    private const val MAX_SHIFTS = 8
    /** The space between an outline and its badge or label. */
    private const val GAP = 4
    /** A mark this many badges high or taller is tall: its badge goes level with its top instead of its middle. */
    private const val TALL = 3
    /** The size of a pointer mark, as a mouse pointer shows at the IDE's scale. */
    private const val POINTER_W = 12
    private const val POINTER_H = 19
    /** How far past an outline a click point still belongs to it: the pointer's tip sits just past a clicked word. */
    private const val JOIN = 4
    /** What a spot past the picture's edge counts as covering: more than any spot inside it can. */
    private const val OFF_PICTURE = Long.MAX_VALUE / 16
    /** The lengths `auto` tries, as factors of the arrow's own: a longer arrow is better than a callout over text. */
    private val AUTO_LENGTHS = listOf(1.0, 1.5, 2.0)
    /** A callout spot is empty when at most one part in this many of its area shows content, as a stray pixel does. */
    private const val EMPTY_SHARE = 100
    /** How much shorter each try of a forced side that fits on neither side is. */
    private const val SHORTEN_STEP = 10
    private val BADGE_FONT = Font(Font.SANS_SERIF, Font.BOLD, 11)
    private val LABEL_FONT = Font(Font.SANS_SERIF, Font.BOLD, 12)
}
