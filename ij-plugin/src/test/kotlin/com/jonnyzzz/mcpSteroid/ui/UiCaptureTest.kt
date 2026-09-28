/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage

class UiCaptureTest {
    /** A white canvas of [w]x[h] logical pixels whose top left corner is at (100, 50) on screen. */
    private fun canvas(w: Int, h: Int, scale: Double = 1.0): UiCapture.Canvas {
        val image = BufferedImage((w * scale).toInt(), (h * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, image.width, image.height); dispose() }
        return UiCapture.Canvas(image, Point(100, 50), scale)
    }

    @Test
    fun `a crop area adds its margin and stays inside the picture`() {
        assertEquals(Rectangle(90, 40, 70, 40), UiCapture.cropArea(Rectangle(100, 50, 50, 20), 10, Rectangle(0, 0, 1000, 1000)))
        assertEquals(Rectangle(0, 0, 65, 35), UiCapture.cropArea(Rectangle(5, 5, 50, 20), 10, Rectangle(0, 0, 1000, 1000)))
    }

    @Test
    fun `a crop of a scaled canvas cuts image pixels at the scale`() {
        val c = UiCapture.crop(canvas(400, 300, scale = 2.0), Rectangle(150, 100, 100, 50))
        assertEquals(200, c.image.width)
        assertEquals(100, c.image.height)
        assertEquals(Point(150, 100), c.origin)
        assertEquals(2.0, c.scale, 0.0)
    }

    @Test
    fun `a crop past the picture keeps the part inside it`() {
        val c = UiCapture.crop(canvas(400, 300), Rectangle(450, 300, 200, 200))
        assertEquals(50, c.image.width)
        assertEquals(50, c.image.height)
        assertEquals(Point(450, 300), c.origin)
    }

    @Test
    fun `a badge sits at the top left outside its mark, and moves past placed badges`() {
        val within = Rectangle(0, 0, 500, 500)
        val first = UiCapture.badgeBounds(Rectangle(100, 100, 80, 20), 18, within, emptyList())
        assertEquals(Rectangle(82, 82, 18, 18), first)
        val second = UiCapture.badgeBounds(Rectangle(100, 100, 80, 20), 18, within, listOf(first))
        assertFalse(second.intersects(first))
        val atEdge = UiCapture.badgeBounds(Rectangle(2, 2, 80, 20), 18, within, emptyList())
        assertTrue(within.contains(atEdge))
    }

    @Test
    fun `highlighting outlines the mark where it is, at the scale, on a copy`() {
        val plain = canvas(400, 300, scale = 2.0)
        val c = UiCapture.highlight(plain, listOf(UiCapture.Mark(1, Rectangle(200, 100, 100, 40), null)))
        // The mark's left edge is 100 logical pixels right of the origin: 200 image pixels.
        assertNotEquals(Color.WHITE.rgb, c.image.getRGB(200, 150))
        assertEquals(Color.WHITE.rgb, c.image.getRGB(260, 150))
        assertEquals(Color.WHITE.rgb, plain.image.getRGB(200, 150))
    }

    @Test
    fun `the mark area holds the mark, its badge and its label`() {
        val c = canvas(400, 300)
        // A checkbox: narrower than its label, which reaches past it.
        val mark = Rectangle(200, 100, 16, 16)
        val area = UiCapture.markArea(c, listOf(UiCapture.Mark(1, mark, "Turn this on")))
        assertTrue(area.contains(mark))
        assertTrue(area.x < mark.x && area.y < mark.y)
        val bare = UiCapture.markArea(c, listOf(UiCapture.Mark(1, mark, null)))
        assertTrue(area.width > bare.width)
    }

    @Test
    fun `identical pictures differ in no pixel, and each changed pixel counts`() {
        val a = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        val b = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        assertEquals(0, UiCapture.differingPixels(a, b))
        b.setRGB(3, 3, 0xFF0000)
        assertEquals(1, UiCapture.differingPixels(a, b))
        assertEquals(Int.MAX_VALUE, UiCapture.differingPixels(a, BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB)))
    }
}
