/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiArrow
import com.jonnyzzz.mcpSteroid.server.UiArrowSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage

class UiCalloutsTest {
    /** A white canvas of [w]x[h] logical pixels whose top left corner is at (100, 50) on screen. */
    private fun canvas(w: Int, h: Int, scale: Double = 1.0): UiCapture.Canvas {
        val image = BufferedImage((w * scale).toInt(), (h * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, image.width, image.height); dispose() }
        return UiCapture.Canvas(image, Point(100, 50), scale)
    }

    @Test
    fun `a mark that takes no number is skipped by the numbering`() {
        val marks = UiCallouts.steps(listOf(
            UiCallouts.Mark(1, Rectangle(0, 0, 10, 10), null, numberable = false),
            UiCallouts.Mark(2, Rectangle(0, 50, 10, 10), null),
            UiCallouts.Mark(3, Rectangle(0, 100, 10, 10), null),
        ), null)
        assertEquals(listOf(false, true, true), marks.map { it.numbered })
        assertEquals(listOf(1, 2), marks.filter { it.numbered }.map { it.number })
    }

    @Test
    fun `a picture of marks that take no number has no badges`() {
        val marks = UiCallouts.steps(listOf(
            UiCallouts.Mark(1, Rectangle(0, 0, 10, 10), null, numberable = false),
            UiCallouts.Mark(2, Rectangle(0, 50, 10, 10), null, numberable = false),
        ), null)
        assertTrue(marks.none { it.numbered })
    }

    @Test
    fun `a joined pointer drops its arrow`() {
        val marks = UiCallouts.steps(listOf(
            UiCallouts.Mark(1, Rectangle(100, 100, 60, 16), null),
            UiCallouts.Mark(2, Rectangle(162, 110, 1, 1), null, pointer = true, arrow = UiArrow()),
        ), null)
        assertTrue(marks[1].joined)
        assertEquals(null, marks[1].arrow)
    }

    @Test
    fun `the mark area holds an arrow and its callout`() {
        val c = canvas(800, 600)
        val mark = UiCallouts.Mark(1, Rectangle(400, 300, 60, 16), "x", numbered = false, arrow = UiArrow(UiArrowSide.LEFT, 120))
        val area = UiCallouts.markArea(c, listOf(mark))
        assertTrue(area.toString(), area.x <= 400 - 3 - 3 - 120 - 5)
    }

    @Test
    fun `an outline in another color paints in that color`() {
        val c = canvas(300, 200)
        val painted = UiCallouts.highlight(c, listOf(UiCallouts.Mark(1, Rectangle(150, 100, 60, 20), null, numbered = false, color = Color(0x2F6FEB))))
        // The outline's left edge crosses row 60 of the picture, whose origin is at (100, 50) on screen.
        val row = (0 until 80).map { painted.image.getRGB(it, 60) and 0xFFFFFF }
        assertTrue(row.map { Integer.toHexString(it) }.toString(), 0x2F6FEB in row)
    }

    @Test
    fun `a mark without an outline paints no outline`() {
        val c = canvas(300, 200)
        val painted = UiCallouts.highlight(c, listOf(UiCallouts.Mark(1, Rectangle(150, 100, 60, 20), null, numbered = false, outline = false,
            arrow = UiArrow(UiArrowSide.RIGHT, 40))))
        // Left of the target, where only an outline could paint, the picture stays white.
        val row = (0 until 80).map { painted.image.getRGB(it, 60) and 0xFFFFFF }
        assertTrue(row.all { it == 0xFFFFFF })
    }

    @Test
    fun `a badge sits left of a normal-height mark, centred on it`() {
        val within = Rectangle(0, 0, 500, 500)
        assertEquals(Rectangle(78, 101, 18, 18), UiCallouts.badgeBounds(Rectangle(100, 100, 80, 20), 18, within, emptyList()))
    }

    @Test
    fun `a badge sits left of a tall mark, level with its top`() {
        val within = Rectangle(0, 0, 500, 500)
        assertEquals(Rectangle(78, 100, 18, 18), UiCallouts.badgeBounds(Rectangle(100, 100, 200, 300), 18, within, emptyList()))
    }

    @Test
    fun `a tall mark at the left edge gets its badge inside its top corner, not over its neighbour`() {
        val within = Rectangle(0, 0, 500, 500)
        assertEquals(Rectangle(6, 2, 18, 18), UiCallouts.badgeBounds(Rectangle(2, 2, 150, 400), 18, within, emptyList()))
    }

    @Test
    fun `a badge goes right of a mark at the left edge, and moves past placed badges`() {
        val within = Rectangle(0, 0, 500, 500)
        assertEquals(Rectangle(86, 3, 18, 18), UiCallouts.badgeBounds(Rectangle(2, 2, 80, 20), 18, within, emptyList()))
        val first = UiCallouts.badgeBounds(Rectangle(100, 100, 80, 20), 18, within, emptyList())
        val second = UiCallouts.badgeBounds(Rectangle(100, 100, 80, 20), 18, within, listOf(first))
        assertFalse(second.intersects(first))
        assertTrue(within.contains(second))
    }

    @Test
    fun `a badge that would cover a neighbour's text goes below the outline`() {
        val within = Rectangle(0, 0, 400, 200)
        val mark = Rectangle(100, 20, 80, 24)
        val neighbours = listOf(Rectangle(20, 20, 76, 24), Rectangle(184, 20, 80, 24))
        val b = UiCallouts.badgeBounds(mark, 18, within, emptyList(), neighbours)
        assertTrue("below the mark: $b", b.y >= mark.y + mark.height)
        assertTrue(neighbours.none { it.intersects(b) })
    }

    @Test
    fun `with no free spot a badge still lands inside the picture`() {
        val within = Rectangle(0, 0, 80, 40)
        val b = UiCallouts.badgeBounds(Rectangle(10, 10, 50, 20), 18, within, emptyList(), listOf(within))
        assertTrue(within.contains(b))
    }

    @Test
    fun `a label that would cover a neighbour's text goes below with its badge`() {
        val c = canvas(600, 200)
        val mark = Rectangle(300, 80, 80, 24)
        val neighbours = listOf(Rectangle(210, 80, 86, 24), Rectangle(384, 80, 90, 24))
        val area = UiCallouts.markArea(c, listOf(UiCallouts.Mark(1, mark, "console")), neighbours)
        assertTrue("the label hangs below: $area", area.y + area.height > mark.y + mark.height + 10)
        assertTrue("and covers no neighbour sideways: $area", area.x >= 210 + 86 - 4)
    }

    @Test
    fun `a mark without a number is outlined with no badge, and its label sits beside the outline`() {
        val mark = Rectangle(200, 100, 80, 20)
        val c = UiCallouts.highlight(canvas(400, 300), listOf(UiCallouts.Mark(1, mark, null, numbered = false)))
        // Where a numbered mark's badge goes, left of the outline and level with its middle: nothing is drawn.
        assertEquals(Color.WHITE.rgb, c.image.getRGB(200 - 100 - 13, 110 - 50))
        val numbered = UiCallouts.markArea(canvas(400, 300), listOf(UiCallouts.Mark(1, mark, "Turn this on")))
        val bare = UiCallouts.markArea(canvas(400, 300), listOf(UiCallouts.Mark(1, mark, "Turn this on", numbered = false)))
        assertTrue("no badge left of the outline: $bare", bare.x > numbered.x)
        assertTrue("the label still widens the area: $bare", bare.x + bare.width > mark.x + mark.width + 20)
    }

    @Test
    fun `a pointer's badge and label go left of it when a menu's text lies right, above and below`() {
        val c = canvas(800, 600)
        // A context menu opened at the click: its items reach from just right of the pointer, above and below it.
        val menu = Rectangle(412, 190, 300, 300)
        val area = UiCallouts.markArea(c, listOf(UiCallouts.Mark(1, Rectangle(400, 200, 1, 1), "right-click", pointer = true)), listOf(menu))
        assertTrue("nothing reaches into the menu: $area", area.x + area.width <= menu.x)
    }

    @Test
    fun `with no free spot a label takes the one that covers the least text`() {
        val c = canvas(800, 600)
        val mark = Rectangle(300, 200, 80, 24)
        // Text all around the mark, but only a sliver of it where the label would go below.
        val around = listOf(Rectangle(100, 150, 700, 48), Rectangle(100, 200, 198, 24), Rectangle(382, 200, 418, 24), Rectangle(100, 230, 700, 2))
        val area = UiCallouts.markArea(c, listOf(UiCallouts.Mark(1, mark, "the setting")), around)
        assertTrue("the label hangs below, over the sliver: $area", area.y + area.height > mark.y + mark.height + 4)
    }

    @Test
    fun `a click point on another outline joins that step, and the steps are numbered without it`() {
        val word = UiCallouts.Mark(1, Rectangle(300, 200, 90, 18), null)
        val point = UiCallouts.Mark(2, Rectangle(392, 208, 1, 1), "right-click", pointer = true)
        val item = UiCallouts.Mark(3, Rectangle(400, 400, 200, 24), null)
        val steps = UiCallouts.steps(listOf(word, point, item), numbered = null)
        assertEquals(listOf(1, 2), steps.filter { it.numbered }.map { it.number })
        val joined = steps[1]
        assertTrue("the point is drawn as a bare pointer", joined.joined && !joined.numbered && joined.label == null)
        // A point away from every outline stays a step of its own, with its label.
        val alone = UiCallouts.steps(listOf(word, point.copy(bounds = Rectangle(600, 300, 1, 1)), item), numbered = null)
        assertEquals(listOf(1, 2, 3), alone.map { it.number })
        assertEquals("right-click", alone[1].label)
        assertTrue(alone.none { it.joined })
        // Two steps, the word with its point and the item: numbered by default, and not when asked.
        assertTrue(UiCallouts.steps(listOf(word, point, item), numbered = false).none { it.numbered })
        // The word with its point alone is one step: outlined, not numbered.
        assertTrue(UiCallouts.steps(listOf(word, point), numbered = null).none { it.numbered })
    }

    @Test
    fun `a pointer at a word's end leaves the word's outline its padding`() {
        val word = Rectangle(300, 200, 98, 19)
        val pointer = UiCallouts.Mark(2, Rectangle(word.x + word.width + 2, 209, 1, 1), null, pointer = true)
        val boxes = UiCallouts.outlineBoxes(listOf(UiCallouts.Mark(1, word, null), pointer))
        assertEquals("the word keeps the outline it has alone", UiCallouts.outlines(listOf(word)).single(), boxes[0])
        assertEquals(Rectangle(pointer.bounds.x, pointer.bounds.y, 12, 19), boxes[1])
    }

    @Test
    fun `a pointer mark draws an arrow at the point, not a box`() {
        val c = UiCallouts.highlight(canvas(400, 300), listOf(UiCallouts.Mark(1, Rectangle(200, 100, 1, 1), null, pointer = true)))
        // The tip is at (200, 100) on screen, (100, 50) in the picture: the arrow's body runs down and right of it.
        assertNotEquals(Color.WHITE.rgb, c.image.getRGB(103, 60))
        // A box around the point would reach left of the tip; the arrow does not.
        assertEquals(Color.WHITE.rgb, c.image.getRGB(92, 55))
    }

    @Test
    fun `a label goes right of its mark, on its badge's line`() {
        val c = canvas(800, 600)
        val mark = Rectangle(300, 200, 100, 20)
        val area = UiCallouts.markArea(c, listOf(UiCallouts.Mark(1, mark, "Turn this on")))
        // The badge on the left and the label on the right widen the area on both sides, on the mark's own line.
        assertTrue(area.x < mark.x && area.x + area.width > mark.x + mark.width + 20)
        assertTrue(area.y >= mark.y - 4 && area.y + area.height <= mark.y + mark.height + 4)
    }

    @Test
    fun `highlighting outlines the mark just outside it, at the scale, on a copy`() {
        val plain = canvas(400, 300, scale = 2.0)
        val c = UiCallouts.highlight(plain, listOf(UiCallouts.Mark(1, Rectangle(200, 100, 100, 40), null)))
        // The mark's left edge is 100 logical pixels right of the origin, 200 image pixels; the outline runs 3 logical
        // pixels outside it, so the control's own edge stays visible.
        assertNotEquals(Color.WHITE.rgb, c.image.getRGB(194, 150))
        assertEquals(Color.WHITE.rgb, c.image.getRGB(201, 150))
        assertEquals(Color.WHITE.rgb, c.image.getRGB(260, 150))
        assertEquals(Color.WHITE.rgb, plain.image.getRGB(194, 150))
    }

    @Test
    fun `the mark area holds the mark, its badge and its label`() {
        val c = canvas(400, 300)
        // A checkbox: narrower than its label, which reaches past it.
        val mark = Rectangle(200, 100, 16, 16)
        val area = UiCallouts.markArea(c, listOf(UiCallouts.Mark(1, mark, "Turn this on")))
        assertTrue(area.contains(mark))
        assertTrue(area.x < mark.x && area.y < mark.y)
        val bare = UiCallouts.markArea(c, listOf(UiCallouts.Mark(1, mark, null)))
        assertTrue(area.width > bare.width)
    }

    @Test
    fun `a crop grows to hold the badges of the marks inside it, and ignores the marks outside it`() {
        val c = canvas(800, 600)
        val page = Rectangle(300, 100, 400, 400)
        // A mark at the page's top left edge puts its badge outside the page.
        val inside = UiCallouts.Mark(1, Rectangle(302, 102, 100, 20), null)
        val outside = UiCallouts.Mark(2, Rectangle(120, 400, 50, 20), null)
        val area = UiCallouts.withMarks(c, page, listOf(inside, outside))
        assertTrue(area.contains(page))
        assertTrue(area.x < page.x && area.y < page.y)
        assertTrue(area.x > 170)
    }

    @Test
    fun `outlines of stacked rows split the gap between them instead of crossing`() {
        val rows = listOf(Rectangle(100, 100, 200, 30), Rectangle(100, 130, 180, 30), Rectangle(100, 160, 190, 30))
        val outlines = UiCallouts.outlines(rows)
        for (i in 0 until outlines.size - 1) {
            val gap = outlines[i + 1].y - (outlines[i].y + outlines[i].height)
            assertTrue("gap between outline $i and ${i + 1} is $gap", gap >= 5)
        }
        // A free edge keeps its padding.
        assertEquals(97, outlines[0].y)
        assertEquals(193, outlines[2].y + outlines[2].height)
    }

    @Test
    fun `rows a few pixels apart get a clear gap between their outlines`() {
        // The Appearance page: "Show line numbers:", "Show method separators", "Show indent guides", in screen pixels.
        val rows = listOf(Rectangle(1175, 708, 147, 25), Rectangle(1175, 740, 182, 25), Rectangle(1175, 769, 148, 25))
        val outlines = UiCallouts.outlines(rows)
        for (i in 0 until outlines.size - 1) {
            val gap = outlines[i + 1].y - (outlines[i].y + outlines[i].height)
            assertTrue("gap between outline $i and ${i + 1} is $gap", gap >= 5)
        }
    }

    @Test
    fun `rows whose bounds overlap a little still get separate outlines`() {
        // Kotlin UI DSL controls reach a few pixels past what they paint, into the next row.
        val (a, b) = UiCallouts.outlines(listOf(Rectangle(100, 100, 200, 34), Rectangle(100, 130, 200, 34)))
        assertTrue("gap ${b.y - (a.y + a.height)}", b.y - (a.y + a.height) >= 5)
    }

    @Test
    fun `a mark mostly inside another keeps its padding`() {
        val (outer, inner) = UiCallouts.outlines(listOf(Rectangle(100, 100, 300, 200), Rectangle(120, 120, 50, 20)))
        assertEquals(Rectangle(97, 97, 306, 206), outer)
        assertEquals(Rectangle(117, 117, 56, 26), inner)
    }

    @Test
    fun `outlines side by side split the gap too, and lone ones keep their padding`() {
        val (a, b) = UiCallouts.outlines(listOf(Rectangle(100, 100, 50, 20), Rectangle(152, 100, 50, 20)))
        assertTrue(b.x - (a.x + a.width) >= 5)
        assertEquals(listOf(Rectangle(97, 97, 56, 26)), UiCallouts.outlines(listOf(Rectangle(100, 100, 50, 20))))
    }
}
