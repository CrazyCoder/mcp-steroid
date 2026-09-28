/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiSteps
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dialog
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import com.intellij.util.ui.UIUtil
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JMenu
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Where a screenshot step with `out` writes its picture, and in which format. */
object UiCapturePaths {
    /**
     * [out] as given when absolute; relative to [scenarioDir], the replayed scenario file's folder, otherwise. A path
     * without an extension gets `.png`: pictures are PNG unless the path asks for a JPEG.
     */
    fun resolve(out: String, scenarioDir: Path?): Path {
        val named = if (UiSteps.pictureExtension(out) == null) "$out.png" else out
        val path = Path.of(named)
        if (path.isAbsolute) return path.normalize()
        val dir = scenarioDir ?: throw UiStepFailure("out \"$out\" is a relative path; in a call with steps, give an absolute path")
        return dir.resolve(path).normalize()
    }

    /** The ImageIO format of the picture [file] names: `jpg` for a .jpg or .jpeg, `png` otherwise. */
    fun format(file: Path): String = if (UiSteps.pictureExtension(file.fileName.toString()) in setOf("jpg", "jpeg")) "jpg" else "png"
}

/**
 * The pictures of a screenshot step: a window painted with the popups open above it, cropped to a part of it, with
 * the controls that matter outlined, and numbered when they are steps. Everything is in screen coordinates in logical pixels; a [Canvas]
 * maps them to its image's pixels by its scale.
 */
object UiCapture {
    /** A picture and where its top left corner is on screen, in logical pixels; [scale] is image pixels per logical pixel. */
    class Canvas(val image: BufferedImage, val origin: Point, val scale: Double) {
        /** The screen area the picture shows, in logical pixels. */
        val bounds: Rectangle get() = Rectangle(origin.x, origin.y, (image.width / scale).roundToInt(), (image.height / scale).roundToInt())
    }

    /**
     * A highlight: its number, its screen bounds, and the text drawn beside it. A [pointer] mark is a point, the
     * top left corner of [bounds], drawn as a mouse pointer: where a click goes. A mark not [numbered] has no badge,
     * for a picture of one area, or of areas with no order to follow; its label sits beside the outline.
     */
    data class Mark(val number: Int, val bounds: Rectangle, val label: String?, val pointer: Boolean = false, val numbered: Boolean = true)

    /** A picture of the screen [area] at [scale], filled with [background], for windows to paint into. */
    fun blankCanvas(area: Rectangle, scale: Double, background: Color): Canvas {
        val image = BufferedImage(
            ceil(area.width * scale).toInt().coerceAtLeast(1), ceil(area.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB,
        )
        val g = image.createGraphics()
        try {
            g.color = background
            g.fillRect(0, 0, image.width, image.height)
        } finally {
            g.dispose()
        }
        return Canvas(image, area.location, scale)
    }

    /**
     * [window] painted at its screen's scale, with the popup windows showing above it and owned by it, such as menus,
     * list popups and completion, each at its place. A popup that reaches past the window grows the picture, and the
     * part of it no window covers takes the window's background. The windows paint themselves, so nothing else on the
     * screen shows through. EDT.
     */
    fun paint(window: Window): Canvas {
        val windows = listOf(window) + popupsOf(window)
        val area = union(windows.map { Rectangle(it.locationOnScreen, it.size) })
        val scale = window.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
        val canvas = blankCanvas(area, scale, window.background ?: UIUtil.getPanelBackground())
        val g = canvas.image.createGraphics()
        try {
            g.scale(scale, scale)
            for (w in windows) {
                val at = w.locationOnScreen
                val part = g.create(at.x - area.x, at.y - area.y, w.width, w.height) as Graphics2D
                try {
                    w.printAll(part)
                } finally {
                    part.dispose()
                }
            }
        } finally {
            g.dispose()
        }
        return canvas
    }

    /**
     * The windows showing above [window] that it owns, and those they own, in the order to paint them. A dialog is a
     * window of its own that a screenshot of it would name, not a popup. EDT.
     */
    fun popupsOf(window: Window): List<Window> =
        window.ownedWindows.filter { it.isShowing && it !is Dialog }.flatMap { listOf(it) + popupsOf(it) }

    /**
     * The window a picture of [window] shows: [window] itself, or for a popup, such as an open menu, the frame or dialog
     * it was opened from, whose picture holds the popup at its place. EDT.
     */
    fun pictured(window: Window): Window = generateSequence(window) { it.owner }.firstOrNull { it is Frame || it is Dialog } ?: window

    /**
     * The screen area of the popups open above [window], with the menu of the menu bar they were opened from, or null
     * when none is open. EDT.
     */
    fun popupArea(window: Window): Rectangle? {
        val popups = popupsOf(window).map { Rectangle(it.locationOnScreen, it.size) }
        if (popups.isEmpty()) return null
        val anchor = MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<JMenu>().firstOrNull()
            ?.takeIf { it.isShowing && SwingUtilities.getWindowAncestor(it) === window }?.let { Rectangle(it.locationOnScreen, it.size) }
        return union(popups + listOfNotNull(anchor))
    }

    fun union(rects: List<Rectangle>): Rectangle = rects.reduce { a, b -> a.union(b) }

    /** [area] grown by [margin] on each side, kept inside [within]. */
    fun cropArea(area: Rectangle, margin: Int, within: Rectangle): Rectangle =
        Rectangle(area.x - margin, area.y - margin, area.width + 2 * margin, area.height + 2 * margin).intersection(within)

    /** The part of [canvas] that shows [screenArea], as a canvas of its own; the part outside the picture is left out. */
    fun crop(canvas: Canvas, screenArea: Rectangle): Canvas {
        val x0 = floor((screenArea.x - canvas.origin.x) * canvas.scale).toInt().coerceIn(0, canvas.image.width - 1)
        val y0 = floor((screenArea.y - canvas.origin.y) * canvas.scale).toInt().coerceIn(0, canvas.image.height - 1)
        val x1 = ceil((screenArea.x + screenArea.width - canvas.origin.x) * canvas.scale).toInt().coerceIn(x0 + 1, canvas.image.width)
        val y1 = ceil((screenArea.y + screenArea.height - canvas.origin.y) * canvas.scale).toInt().coerceIn(y0 + 1, canvas.image.height)
        val copy = BufferedImage(x1 - x0, y1 - y0, canvas.image.type.takeIf { it != BufferedImage.TYPE_CUSTOM } ?: BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        try {
            g.drawImage(canvas.image, -x0, -y0, null)
        } finally {
            g.dispose()
        }
        val origin = Point(canvas.origin.x + (x0 / canvas.scale).roundToInt(), canvas.origin.y + (y0 / canvas.scale).roundToInt())
        return Canvas(copy, origin, canvas.scale)
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
     * label; badges and labels keep off [obstacles], the text of other controls, where there is room.
     */
    fun highlight(canvas: Canvas, marks: List<Mark>, obstacles: List<Rectangle> = emptyList()): Canvas {
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
                    g.color = OUTLINE
                    g.fill(arrow)
                } else {
                    val outline = RoundRectangle2D.Float(b.x.toFloat(), b.y.toFloat(), b.width.toFloat(), b.height.toFloat(), ARC, ARC)
                    g.stroke = BasicStroke(OUTLINE_WIDTH + 2f)
                    g.color = EDGE
                    g.draw(outline)
                    g.stroke = BasicStroke(OUTLINE_WIDTH)
                    g.color = OUTLINE
                    g.draw(outline)
                }
                parts.badge?.let { badge ->
                    g.color = EDGE
                    g.fill(Ellipse2D.Float(badge.x - 1f, badge.y - 1f, badge.width + 2f, badge.height + 2f))
                    g.color = OUTLINE
                    g.fill(Ellipse2D.Float(badge.x.toFloat(), badge.y.toFloat(), badge.width.toFloat(), badge.height.toFloat()))
                    g.color = Color.WHITE
                    g.font = BADGE_FONT
                    val text = mark.number.toString()
                    val m = g.fontMetrics
                    g.drawString(text, badge.x + (badge.width - m.stringWidth(text)) / 2f, badge.y + (badge.height - m.height) / 2f + m.ascent)
                }
                parts.label?.let { box ->
                    g.color = OUTLINE
                    g.fill(RoundRectangle2D.Float(box.x.toFloat(), box.y.toFloat(), box.width.toFloat(), box.height.toFloat(), ARC, ARC))
                    g.color = Color.WHITE
                    g.font = LABEL_FONT
                    val lm = g.fontMetrics
                    g.drawString(mark.label!!, box.x + LABEL_PAD.toFloat(), box.y + (box.height - lm.height) / 2f + lm.ascent)
                }
            }
        } finally {
            g.dispose()
        }
        return Canvas(copy, Point(canvas.origin), canvas.scale)
    }

    /** The screen area of [marks] with their badges and labels, placed as [highlight] places them. */
    fun markArea(canvas: Canvas, marks: List<Mark>, obstacles: List<Rectangle> = emptyList()): Rectangle {
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            return union(layout(canvas, marks, g, obstacles).flatMap { (_, parts) -> listOfNotNull(parts.outline, parts.badge, parts.label) })
        } finally {
            g.dispose()
        }
    }

    /** [area] grown to hold the outline, badge and label of each of [marks] that lies in it, so a crop cuts none of them. */
    fun withMarks(canvas: Canvas, area: Rectangle, marks: List<Mark>, obstacles: List<Rectangle> = emptyList()): Rectangle {
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

    /** The number of pixels in which [a] and [b] differ, or [Int.MAX_VALUE] when their sizes differ. */
    fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        if (a.width != b.width || a.height != b.height) return Int.MAX_VALUE
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) count++
        return count
    }

    private class Parts(val outline: Rectangle, val badge: Rectangle?, val label: Rectangle?)

    /**
     * Where each mark's badge and label go, badges placed in order so that none covers another. A label goes right of
     * its mark on the badge's line, "(1) [control] label", or left of the badge when the picture has no room there.
     * Where that label would cover the text of another control, one of [obstacles], badge and label move below the
     * mark, or above it. A pointer mark's badge goes right of the pointer.
     */
    private fun layout(canvas: Canvas, marks: List<Mark>, g: Graphics2D, obstacles: List<Rectangle>): List<Pair<Mark, Parts>> {
        val placed = mutableListOf<Rectangle>()
        val labelMetrics = g.getFontMetrics(LABEL_FONT)
        val within = canvas.bounds
        val boxes = outlines(marks.map { if (it.pointer) Rectangle(it.bounds.x, it.bounds.y, POINTER_W, POINTER_H) else it.bounds })
        return marks.mapIndexed { i, mark ->
            val outline = if (mark.pointer) Rectangle(mark.bounds.x, mark.bounds.y, POINTER_W, POINTER_H) else boxes[i]
            // A control's own text lies inside its outline and is no obstacle to its own badge.
            val others = obstacles.filterNot { outline.contains(it) }
            fun free(r: Rectangle) = within.contains(r) && placed.none { it.intersects(r) } && others.none { it.intersects(r) }
            fun labelFor(badge: Rectangle, text: String): Rectangle {
                val width = labelMetrics.stringWidth(text) + 2 * LABEL_PAD
                val besideBadge = if (badge.x < outline.x) outline.x + outline.width + GAP else badge.x + badge.width + GAP
                val x = if (besideBadge + width <= within.x + within.width) besideBadge else badge.x - GAP - width
                return Rectangle(x, badge.y, width, badge.height)
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
            var badge = if (mark.pointer) {
                Rectangle(outline.x + outline.width + GAP, outline.y + (outline.height - BADGE) / 2, BADGE, BADGE).takeIf(::free)
                    ?: badgeBounds(outline, BADGE, within, placed, others)
            } else badgeBounds(outline, BADGE, within, placed, others)
            var label = mark.label?.let { labelFor(badge, it) }
            if (label != null && others.any { it.intersects(label!!) }) {
                listOf(below(outline, BADGE), above(outline, BADGE)).firstOrNull { spot -> free(spot) && free(labelFor(spot, mark.label!!)) }?.let { spot ->
                    badge = spot
                    label = labelFor(spot, mark.label!!)
                }
            }
            placed += badge
            label?.let { placed += it }
            mark to Parts(outline, badge, label)
        }
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
    private val BADGE_FONT = Font(Font.SANS_SERIF, Font.BOLD, 11)
    private val LABEL_FONT = Font(Font.SANS_SERIF, Font.BOLD, 12)
}
