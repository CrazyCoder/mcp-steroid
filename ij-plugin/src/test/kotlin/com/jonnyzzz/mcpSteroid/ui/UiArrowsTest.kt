/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiArrowHead
import com.jonnyzzz.mcpSteroid.server.UiArrowSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle

class UiArrowsTest {
    private val box = Rectangle(100, 100, 80, 20)

    @Test
    fun `the head stops a gap short of the edge that faces the tail`() {
        assertEquals(Point(97, 110), UiArrows.head(box, UiArrowSide.LEFT))
        assertEquals(Point(183, 110), UiArrows.head(box, UiArrowSide.RIGHT))
        assertEquals(Point(140, 97), UiArrows.head(box, UiArrowSide.ABOVE))
        assertEquals(Point(140, 123), UiArrows.head(box, UiArrowSide.BELOW))
        assertEquals(Point(97, 123), UiArrows.head(box, UiArrowSide.BELOW_LEFT))
    }

    @Test
    fun `the tail lies the length away, a diagonal at 45 degrees`() {
        assertEquals(Point(37, 110), UiArrows.tail(Point(97, 110), UiArrowSide.LEFT, 60))
        assertEquals(Point(55, 165), UiArrows.tail(Point(97, 123), UiArrowSide.BELOW_LEFT, 60))
    }

    @Test
    fun `a callout reads away from the target`() {
        assertEquals(Rectangle(28, 101, 18, 18) to Rectangle(-26, 101, 50, 18), UiArrows.callout(Point(37, 110), UiArrowSide.LEFT, 18, 50, 18))
        assertEquals(Rectangle(228, 101, 18, 18) to Rectangle(250, 101, 50, 18), UiArrows.callout(Point(237, 110), UiArrowSide.RIGHT, 18, 50, 18))
    }

    @Test
    fun `a label alone starts at the tail and a bare arrow has no callout`() {
        assertEquals(null to Rectangle(-13, 101, 50, 18), UiArrows.callout(Point(37, 110), UiArrowSide.LEFT, null, 50, 18))
        assertEquals(null to Rectangle(115, 32, 50, 18), UiArrows.callout(Point(140, 50), UiArrowSide.ABOVE, null, 50, 18))
        assertEquals(null to null, UiArrows.callout(Point(37, 110), UiArrowSide.LEFT, null, null, 18))
    }

    @Test
    fun `on a diagonal, the label's corner nearest the target sits at the tail`() {
        assertEquals(null to Rectangle(140, 50, 50, 18), UiArrows.callout(Point(140, 50), UiArrowSide.BELOW_RIGHT, null, 50, 18))
        assertEquals(null to Rectangle(140, 32, 50, 18), UiArrows.callout(Point(140, 50), UiArrowSide.ABOVE_RIGHT, null, 50, 18))
        assertEquals(null to Rectangle(90, 50, 50, 18), UiArrows.callout(Point(140, 50), UiArrowSide.BELOW_LEFT, null, 50, 18))
        assertEquals(null to Rectangle(90, 32, 50, 18), UiArrows.callout(Point(140, 50), UiArrowSide.ABOVE_LEFT, null, 50, 18))
    }

    @Test
    fun `text is white on red, green and blue, and black on yellow and orange`() {
        for (fill in listOf(0xE52B50, 0x2A9D5B, 0x2F6FEB, 0x202020)) assertEquals(Integer.toHexString(fill), Color.WHITE, UiArrows.textOn(Color(fill)))
        for (fill in listOf(0xFFD60A, 0xFF9F1C)) assertEquals(Integer.toHexString(fill), Color.BLACK, UiArrows.textOn(Color(fill)))
    }

    @Test
    fun `a filled head has an area, an open head is a stroke, and no head is none`() {
        val (_, filled) = UiArrows.shapes(Point(0, 0), Point(60, 0), UiArrowHead.FILLED, 2.5f)
        val (_, open) = UiArrows.shapes(Point(0, 0), Point(60, 0), UiArrowHead.OPEN, 2.5f)
        val (shaft, none) = UiArrows.shapes(Point(0, 0), Point(60, 0), UiArrowHead.NONE, 2.5f)
        assertTrue(filled!!.bounds2D.width > 0 && filled.bounds2D.height > 0)
        assertTrue(open!!.bounds2D.height > 0)
        assertNull(none)
        assertEquals(60.0, shaft.bounds2D.maxX, 0.01)
    }

    @Test
    fun `a filled head's shaft stops at the head's base`() {
        val (shaft, _) = UiArrows.shapes(Point(0, 0), Point(60, 0), UiArrowHead.FILLED, 2.5f)
        assertTrue(shaft.bounds2D.maxX.toString(), shaft.bounds2D.maxX < 60)
    }
}
