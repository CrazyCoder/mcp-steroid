/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.SwingUtilities

/** Scrolls a control to a place in its view, for a picture that shows what is around it. */
object UiScrollAlign {
    /**
     * The view position that puts an area at [areaY] of [areaHeight] at the top of a view [extent] high ("top"), or
     * in its middle ("center"), kept within content [viewHeight] high.
     */
    fun viewY(areaY: Int, areaHeight: Int, extent: Int, viewHeight: Int, align: String): Int {
        val y = if (align == "top") areaY else areaY - (extent - areaHeight) / 2
        return y.coerceIn(0, maxOf(0, viewHeight - extent))
    }

    /**
     * Scrolls the viewport around [c] so that [area], in [c]'s coordinates, sits as [align] says, and returns the
     * viewport, or null when [c] is in none. EDT.
     */
    fun scroll(c: Component, area: Rectangle, align: String): JViewport? {
        val port = SwingUtilities.getAncestorOfClass(JViewport::class.java, c) as? JViewport ?: return null
        val view = port.view ?: return null
        val inView = SwingUtilities.convertRectangle(c, area, view)
        val y = viewY(inView.y, inView.height, port.extentSize.height, view.height, align)
        port.viewPosition = Point(port.viewPosition.x, y)
        return port
    }

    /**
     * The area of a whole control of [size] to outline, in its coordinates, and whether to scroll it to the middle of
     * its view first. A control larger than its view [extent], such as a tree that fills its scroll pane, is outlined
     * as far as it [shown]s and never scrolled: its outline would run past the picture. One that fits is outlined whole,
     * and scrolled when part of it is out of view. [extent] is null for a control in no scroll pane.
     */
    fun wholeArea(size: Dimension, shown: Rectangle, extent: Dimension?): Pair<Rectangle, Boolean> {
        val whole = Rectangle(0, 0, size.width, size.height)
        if (extent != null && (size.width > extent.width || size.height > extent.height) && !shown.isEmpty) return Rectangle(shown) to false
        return whole to (extent != null && !shown.contains(whole))
    }

    /** [c]'s size, the part of it its viewport shows, and the viewport's extent, for [wholeArea]. EDT. */
    fun wholeAreaOf(c: Component): Pair<Rectangle, Boolean> {
        val port = SwingUtilities.getAncestorOfClass(JViewport::class.java, c) as? JViewport
        val shown = (c as? JComponent)?.visibleRect ?: Rectangle(0, 0, c.width, c.height)
        return wholeArea(c.size, shown, port?.extentSize)
    }

    /**
     * The screen bounds a scroll report ends with, such as `screen 120,340 200x24`: where a JetBrains Client draws the
     * outline of a control of a host Settings page, which exists only on the backend.
     */
    fun boundsNote(r: Rectangle): String = "screen ${r.x},${r.y} ${r.width}x${r.height}"

    /** The screen bounds in a scroll [report], or null when it has none. */
    fun parseBounds(report: String): Rectangle? = BOUNDS.find(report)?.destructured?.let { (x, y, w, h) ->
        Rectangle(x.toInt(), y.toInt(), w.toInt(), h.toInt())
    }

    private val BOUNDS = Regex("""screen (-?\d+),(-?\d+) (\d+)x(\d+)""")

    /** Whether [area], in [c]'s coordinates, is all in the visible part of [c]'s viewport, or [c] is in none. EDT. */
    fun inView(c: Component, area: Rectangle): Boolean {
        val port = SwingUtilities.getAncestorOfClass(JViewport::class.java, c) as? JViewport ?: return true
        val view = port.view ?: return true
        return port.viewRect.contains(SwingUtilities.convertRectangle(c, area, view))
    }
}
