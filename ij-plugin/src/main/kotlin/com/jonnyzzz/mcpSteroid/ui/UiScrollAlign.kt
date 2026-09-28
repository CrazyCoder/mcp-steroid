/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
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
