/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiSteps
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dialog
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Path
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
 * the controls that matter outlined and numbered. Everything is in screen coordinates in logical pixels; a [Canvas]
 * maps them to its image's pixels by its scale.
 */
object UiCapture {
    /** A picture and where its top left corner is on screen, in logical pixels; [scale] is image pixels per logical pixel. */
    class Canvas(val image: BufferedImage, val origin: Point, val scale: Double) {
        /** The screen area the picture shows, in logical pixels. */
        val bounds: Rectangle get() = Rectangle(origin.x, origin.y, (image.width / scale).roundToInt(), (image.height / scale).roundToInt())
    }

    /** A highlight: its number, its screen bounds, and the text drawn beside its number. */
    data class Mark(val number: Int, val bounds: Rectangle, val label: String?)

    /**
     * [window] painted at its screen's scale, with the popup windows showing above it and owned by it, such as menus,
     * list popups and completion, each at its place. A popup that reaches past the window grows the picture. The
     * windows paint themselves, so nothing else on the screen shows through. EDT.
     */
    fun paint(window: Window): Canvas {
        val windows = listOf(window) + popupsOf(window)
        val area = union(windows.map { Rectangle(it.locationOnScreen, it.size) })
        val scale = window.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
        val image = BufferedImage(
            ceil(area.width * scale).toInt().coerceAtLeast(1), ceil(area.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB,
        )
        val g = image.createGraphics()
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
        return Canvas(image, area.location, scale)
    }

    /**
     * The windows showing above [window] that it owns, and those they own, in the order to paint them. A dialog is a
     * window of its own that a screenshot of it would name, not a popup. EDT.
     */
    fun popupsOf(window: Window): List<Window> =
        window.ownedWindows.filter { it.isShowing && it !is Dialog }.flatMap { listOf(it) + popupsOf(it) }

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
     * Where the number badge of a mark at [mark] goes, a square of [size]: at its top left corner, outside it, else at
     * its top right corner, else inside its top left corner; kept inside [within] and moved right past [placed] badges.
     */
    fun badgeBounds(mark: Rectangle, size: Int, within: Rectangle, placed: List<Rectangle>): Rectangle {
        val spots = listOf(
            Rectangle(mark.x - size, mark.y - size, size, size),
            Rectangle(mark.x + mark.width, mark.y - size, size, size),
            Rectangle(mark.x, mark.y, size, size),
        )
        val spot = Rectangle(spots.firstOrNull { within.contains(it) } ?: spots.last())
        spot.x = spot.x.coerceIn(within.x, maxOf(within.x, within.x + within.width - size))
        spot.y = spot.y.coerceIn(within.y, maxOf(within.y, within.y + within.height - size))
        repeat(MAX_SHIFTS) {
            val hit = placed.firstOrNull { it.intersects(spot) } ?: return spot
            spot.x = hit.x + hit.width + 2
        }
        return spot
    }

    /** A copy of [canvas] with each mark outlined, its number in a round badge and its label beside the badge. */
    fun highlight(canvas: Canvas, marks: List<Mark>): Canvas {
        val copy = BufferedImage(canvas.image.width, canvas.image.height, BufferedImage.TYPE_INT_ARGB)
        val g = copy.createGraphics()
        try {
            g.drawImage(canvas.image, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.scale(canvas.scale, canvas.scale)
            g.translate(-canvas.origin.x, -canvas.origin.y)
            for ((mark, parts) in layout(canvas, marks, g)) {
                val b = outlined(mark.bounds)
                val outline = RoundRectangle2D.Float(b.x.toFloat(), b.y.toFloat(), b.width.toFloat(), b.height.toFloat(), ARC, ARC)
                g.stroke = BasicStroke(OUTLINE_WIDTH + 2f)
                g.color = EDGE
                g.draw(outline)
                g.stroke = BasicStroke(OUTLINE_WIDTH)
                g.color = OUTLINE
                g.draw(outline)
                val badge = parts.badge
                g.color = EDGE
                g.fill(Ellipse2D.Float(badge.x - 1f, badge.y - 1f, badge.width + 2f, badge.height + 2f))
                g.color = OUTLINE
                g.fill(Ellipse2D.Float(badge.x.toFloat(), badge.y.toFloat(), badge.width.toFloat(), badge.height.toFloat()))
                g.color = Color.WHITE
                g.font = BADGE_FONT
                val text = mark.number.toString()
                val m = g.fontMetrics
                g.drawString(text, badge.x + (badge.width - m.stringWidth(text)) / 2f, badge.y + (badge.height - m.height) / 2f + m.ascent)
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

    /** The screen area of [marks] with their badges and labels. */
    fun markArea(canvas: Canvas, marks: List<Mark>): Rectangle {
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try {
            return union(layout(canvas, marks, g).flatMap { (mark, parts) -> listOfNotNull(outlined(mark.bounds), parts.badge, parts.label) })
        } finally {
            g.dispose()
        }
    }

    /** [area] grown to hold the outline, badge and label of each of [marks] that lies in it, so a crop cuts none of them. */
    fun withMarks(canvas: Canvas, area: Rectangle, marks: List<Mark>): Rectangle {
        val inside = marks.filter { area.intersects(it.bounds) }
        return if (inside.isEmpty()) area else area.union(markArea(canvas, inside))
    }

    /** The number of pixels in which [a] and [b] differ, or [Int.MAX_VALUE] when their sizes differ. */
    fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        if (a.width != b.width || a.height != b.height) return Int.MAX_VALUE
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) count++
        return count
    }

    private class Parts(val badge: Rectangle, val label: Rectangle?)

    /** Where each mark's badge and label go, badges placed in order so that none covers another. */
    private fun layout(canvas: Canvas, marks: List<Mark>, g: Graphics2D): List<Pair<Mark, Parts>> {
        val placed = mutableListOf<Rectangle>()
        val labelMetrics = g.getFontMetrics(LABEL_FONT)
        return marks.map { mark ->
            val badge = badgeBounds(outlined(mark.bounds), BADGE, canvas.bounds, placed)
            placed += badge
            val label = mark.label?.let {
                val box = Rectangle(badge.x + badge.width + 3, badge.y, labelMetrics.stringWidth(it) + 2 * LABEL_PAD, badge.height)
                placed += box
                box
            }
            mark to Parts(badge, label)
        }
    }

    /** The area an outline runs around: [b] grown by [PAD], so the control's own edge and text stay visible. */
    private fun outlined(b: Rectangle) = Rectangle(b.x - PAD, b.y - PAD, b.width + 2 * PAD, b.height + 2 * PAD)

    @Suppress("UseJBColor")
    private val OUTLINE = Color(0xE5, 0x2B, 0x50)
    private const val PAD = 3

    @Suppress("UseJBColor")
    private val EDGE = Color(255, 255, 255, 220)
    private const val OUTLINE_WIDTH = 2.5f
    private const val ARC = 8f
    private const val BADGE = 18
    private const val LABEL_PAD = 5
    private const val MAX_SHIFTS = 8
    private val BADGE_FONT = Font(Font.SANS_SERIF, Font.BOLD, 11)
    private val LABEL_FONT = Font(Font.SANS_SERIF, Font.BOLD, 12)
}
