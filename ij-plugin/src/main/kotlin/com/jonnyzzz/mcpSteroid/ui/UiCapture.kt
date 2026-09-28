/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiSteps
import java.awt.Color
import java.awt.Dialog
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import com.intellij.util.ui.UIUtil
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JMenu
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
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
 * The pictures of a screenshot step: a window painted with the popups open above it, and cropped to a part of it;
 * [UiCallouts] draws the marks over them. Everything is in screen coordinates in logical pixels; a [Canvas] maps them
 * to its image's pixels by its scale.
 */
object UiCapture {
    /** A picture and where its top left corner is on screen, in logical pixels; [scale] is image pixels per logical pixel. */
    class Canvas(val image: BufferedImage, val origin: Point, val scale: Double) {
        /** The screen area the picture shows, in logical pixels. */
        val bounds: Rectangle get() = Rectangle(origin.x, origin.y, (image.width / scale).roundToInt(), (image.height / scale).roundToInt())
    }

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

    /** The number of pixels in which [a] and [b] differ, or [Int.MAX_VALUE] when their sizes differ. */
    fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        if (a.width != b.width || a.height != b.height) return Int.MAX_VALUE
        var count = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) count++
        return count
    }
}
