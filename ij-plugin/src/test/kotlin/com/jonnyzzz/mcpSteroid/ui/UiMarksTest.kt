/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage

class UiMarksTest {
    private fun white(): BufferedImage = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 200, 100); dispose() }
    }

    @Test
    fun `a mark outlines its bounds on a copy and leaves the original alone`() {
        val original = white()
        val marked = UiMarks.draw(original, listOf(UiMarks.Mark("e7", Rectangle(50, 40, 60, 30))))
        assertNotEquals(Color.WHITE.rgb, marked.getRGB(110, 70))
        assertEquals(Color.WHITE.rgb, marked.getRGB(80, 55))
        assertEquals(Color.WHITE.rgb, original.getRGB(110, 70))
    }

    @Test
    fun `a row mark is labelled at the row's right end and not outlined`() {
        val marked = UiMarks.draw(white(), listOf(UiMarks.Mark("e7#3", Rectangle(10, 40, 180, 30), row = true)))
        assertEquals(Color.WHITE.rgb, marked.getRGB(10, 55))
        assertEquals(Color.WHITE.rgb, marked.getRGB(100, 40))
        assertNotEquals(Color.WHITE.rgb, marked.getRGB(185, 55))
    }

    @Test
    fun `a row label moves past the row's text when the text reaches its right end`() {
        val row = Rectangle(10, 40, 140, 30)
        val text = Rectangle(10, 40, 135, 30)
        val marked = UiMarks.draw(white(), listOf(UiMarks.Mark("e7#3", row, row = true, content = text)))
        val tag = (0 until 200).filter { x -> (40 until 70).any { y -> marked.getRGB(x, y) != Color.WHITE.rgb } }
        assertTrue("tag at ${tag.firstOrNull()}..${tag.lastOrNull()}", tag.isNotEmpty() && tag.first() >= 145)
    }

    @Test
    fun `a mark is covered when a popup hides at least half of it`() {
        val mark = Rectangle(0, 0, 100, 20)
        assertTrue(UiMarks.covered(mark, Rectangle(50, 0, 200, 200)))
        assertFalse(UiMarks.covered(mark, Rectangle(60, 0, 200, 200)))
        assertFalse(UiMarks.covered(mark, Rectangle(0, 30, 100, 20)))
    }

    @Test
    fun `mark bounds scale from window pixels to image pixels`() {
        assertEquals(Rectangle(20, 40, 60, 30), UiMarks.scale(Rectangle(10, 20, 30, 15), 2.0))
    }
}
