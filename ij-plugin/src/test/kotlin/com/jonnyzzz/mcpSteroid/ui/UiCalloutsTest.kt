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

    private fun arrowOf(c: UiCapture.Canvas, mark: UiCallouts.Mark, obstacles: List<Rectangle> = emptyList()) =
        UiCallouts.arrows(c, listOf(mark), obstacles).single()!!

    @Test
    fun `auto points from the right when nothing is there`() {
        val p = arrowOf(canvas(800, 600), UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "x", numbered = false, arrow = UiArrow()))
        assertEquals(UiArrowSide.RIGHT, p.side)
        assertEquals(60, p.length)
    }

    @Test
    fun `auto passes over a side whose callout covers text`() {
        val text = Rectangle(360, 280, 200, 60)
        val p = arrowOf(canvas(800, 600), UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "x", numbered = false, arrow = UiArrow()), listOf(text))
        assertEquals(UiArrowSide.LEFT, p.side)
    }

    @Test
    fun `auto tries a longer arrow before a worse spot`() {
        // Text all around the target, reaching past every callout at 60 px, and clear of the one at 90 px on the right.
        val around = Rectangle(200, 200, 250, 220)
        val p = arrowOf(canvas(800, 600), UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "x", numbered = false, arrow = UiArrow()), listOf(around))
        assertEquals(UiArrowSide.RIGHT, p.side)
        assertEquals(90, p.length)
    }

    /** Lines of text, as a tree's rows, [x] to [x] + [w] wide, every 30 px from [top] to [bottom]. */
    private fun rows(x: Int, w: Int, top: Int, bottom: Int) = (top..bottom step 30).map { Rectangle(x, it, w, 14) }

    @Test
    fun `auto reaches empty space past the nearby text before it covers text`() {
        // Rows of text above and below the target, up to y 480, and beside it; the picture is empty below them.
        val text = rows(150, 500, 150, 480).filterNot { it.y == 300 } + listOf(Rectangle(150, 300, 140, 14), Rectangle(370, 300, 280, 14))
        val p = arrowOf(canvas(800, 600), UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "a label", numbered = false, arrow = UiArrow()), text)
        val callout = p.callout!!
        assertTrue("$callout covers text", text.none { it.intersects(callout) })
    }

    @Test
    fun `a callout keeps off an earlier arrow's shaft`() {
        // The first arrow runs down at x 330. Text right of the second mark leaves its left side, whose label would lie
        // across that shaft.
        val first = UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), null, numbered = false, arrow = UiArrow(UiArrowSide.BELOW, 200))
        val second = UiCallouts.Mark(2, Rectangle(400, 400, 40, 16), "label", numbered = false, arrow = UiArrow())
        val (a, b) = UiCallouts.arrows(canvas(800, 600), listOf(first, second), listOf(Rectangle(440, 380, 360, 60))).map { it!! }
        assertFalse("${b.callout} lies on the first shaft", java.awt.geom.Line2D.Double(a.tail, a.head).intersects(b.callout!!))
    }

    @Test
    fun `with no empty spot a callout still keeps off an earlier arrow's shaft`() {
        // Text over the whole picture, so every spot covers as much; the first arrow runs down at x 330, where the second
        // mark's callout on the right would lie.
        val everywhere = listOf(Rectangle(100, 50, 800, 600))
        val first = UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), null, numbered = false, arrow = UiArrow(UiArrowSide.BELOW, 200))
        val second = UiCallouts.Mark(2, Rectangle(220, 400, 40, 16), "label", numbered = false, arrow = UiArrow())
        val (a, b) = UiCallouts.arrows(canvas(800, 600), listOf(first, second), everywhere).map { it!! }
        assertFalse("${b.callout} lies on the first shaft", java.awt.geom.Line2D.Double(a.tail, a.head).intersects(b.callout!!))
    }

    @Test
    fun `the picture draws each arrow where the report says, at any scale`() {
        // Text right of the target, at every distance: wherever the label's width decides the side, the drawing and the
        // report must decide alike.
        for (gap in 0..60 step 3) {
            val c = canvas(800, 600, scale = 1.5)
            val mark = UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "Find Actions by Shortcut", numbered = false, arrow = UiArrow())
            val text = listOf(Rectangle(363 + 60 + 150 + gap, 290, 100, 40))
            val reported = UiCallouts.arrows(c, listOf(mark), text).single()!!
            val drawn = UiCallouts.highlight(c, listOf(mark), text)
            val mid = Point((reported.head.x + reported.tail.x) / 2, (reported.head.y + reported.tail.y) / 2)
            val rgb = drawn.image.getRGB(((mid.x - 100) * 1.5).toInt(), ((mid.y - 50) * 1.5).toInt())
            assertNotEquals("gap $gap: no arrow at $mid, the middle of the reported ${reported.side} shaft", Color.WHITE.rgb, rgb)
        }
    }

    @Test
    fun `a forced side flips at the picture's edge`() {
        val p = arrowOf(canvas(400, 300), UiCallouts.Mark(1, Rectangle(120, 150, 40, 16), "label", numbered = false, arrow = UiArrow(UiArrowSide.LEFT, 80)))
        assertEquals(UiArrowSide.RIGHT, p.side)
        assertEquals(UiArrowSide.LEFT, p.flippedFrom)
    }

    @Test
    fun `a forced side that fits on neither side is shortened`() {
        val c = canvas(300, 200)
        val p = arrowOf(c, UiCallouts.Mark(1, Rectangle(200, 120, 60, 16), null, numbered = false, arrow = UiArrow(UiArrowSide.LEFT, 400)))
        assertEquals(UiArrowSide.LEFT, p.side)
        assertEquals(400, p.shortenedFrom)
        assertEquals(90, p.length)
        assertTrue(c.bounds.contains(p.tail))
    }

    @Test
    fun `a shaft does not cross another outline when a side is free`() {
        val other = UiCallouts.Mark(1, Rectangle(380, 300, 40, 16), null, numbered = false)
        val arrowed = UiCallouts.Mark(2, Rectangle(300, 300, 60, 16), null, numbered = false, arrow = UiArrow(length = 120))
        val p = UiCallouts.arrows(canvas(800, 600), listOf(other, arrowed)).last()!!
        assertNotEquals(UiArrowSide.RIGHT, p.side)
    }

    @Test
    fun `a shaft does not run through text when a side is free`() {
        // Text right of the target covers the callouts there; a label left of the target lies on the left shaft.
        val right = Rectangle(420, 280, 200, 60)
        val onShaft = Rectangle(250, 302, 30, 12)
        val p = arrowOf(canvas(800, 600), UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "x", numbered = false, arrow = UiArrow()), listOf(right, onShaft))
        assertEquals(UiArrowSide.BELOW, p.side)
    }

    /** [c] with the screen area [r] painted gray, as a control or an icon the picture shows. */
    private fun painted(c: UiCapture.Canvas, r: Rectangle): UiCapture.Canvas = c.also {
        it.image.createGraphics().apply { color = Color(0x80, 0x80, 0x80); fillRect(r.x - 100, r.y - 50, r.width, r.height); dispose() }
    }

    @Test
    fun `a callout goes where the picture is empty, off controls no text list names`() {
        val c = painted(canvas(800, 600), Rectangle(380, 290, 160, 36))
        val p = arrowOf(c, UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "x", numbered = false, arrow = UiArrow()))
        assertNotEquals(UiArrowSide.RIGHT, p.side)
    }

    @Test
    fun `a long label covers no glyph, however small a share of it the glyph is`() {
        // One glyph where the label on the right would lie, a small part of that long label's area.
        val c = painted(canvas(800, 600), Rectangle(500, 302, 4, 8))
        val p = arrowOf(c, UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "a much longer label that holds one glyph under it", numbered = false, arrow = UiArrow()))
        assertFalse("${p.callout} covers the glyph", p.callout!!.intersects(Rectangle(500, 302, 4, 8)))
    }

    @Test
    fun `a callout keeps a clear margin from painted content`() {
        // A line of text just under where the label on the right would lie.
        val line = Rectangle(420, 318, 200, 8)
        val c = painted(canvas(800, 600), line)
        val p = arrowOf(c, UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "a label", numbered = false, arrow = UiArrow()))
        val margin = Rectangle(p.callout!!).apply { grow(2, 2) }
        assertFalse("$margin touches $line", margin.intersects(line))
    }

    @Test
    fun `a shaft may cross painted content when every shaft does`() {
        // A narrow frame of content around the target: every shaft crosses it, and the callouts beyond are empty.
        val c = canvas(800, 600)
        for (r in listOf(Rectangle(270, 270, 120, 4), Rectangle(270, 342, 120, 4), Rectangle(270, 270, 4, 76), Rectangle(386, 270, 4, 76))) painted(c, r)
        val p = arrowOf(c, UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "x", numbered = false, arrow = UiArrow()))
        assertEquals(UiArrowSide.RIGHT, p.side)
        assertEquals(60, p.length)
    }

    @Test
    fun `a shaft over empty space beats one across painted text`() {
        // Text between the target and the callout on the right: only that shaft crosses it.
        val c = painted(canvas(800, 600), Rectangle(380, 300, 20, 16))
        val p = arrowOf(c, UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "x", numbered = false, arrow = UiArrow()))
        assertNotEquals(UiArrowSide.RIGHT, p.side)
    }

    @Test
    fun `auto with every side covered still draws inside the picture`() {
        val c = canvas(200, 120)
        val p = arrowOf(c, UiCallouts.Mark(1, Rectangle(180, 90, 40, 16), "long label here", numbered = false, arrow = UiArrow()), listOf(Rectangle(100, 50, 200, 120)))
        assertTrue(c.bounds.contains(p.tail))
    }

    @Test
    fun `a pointer's arrow stops short of its tip on the tail's side`() {
        val p = arrowOf(canvas(800, 600), UiCallouts.Mark(1, Rectangle(300, 300, 1, 1), null, pointer = true, numbered = false, arrow = UiArrow(UiArrowSide.LEFT, 50)))
        assertEquals(Point(297, 300), p.head)
        assertEquals(Point(247, 300), p.tail)
    }

    @Test
    fun `a crop grows to where a mark is drawn among all the marks, not where it would go alone`() {
        val c = canvas(800, 600)
        // Outside the crop: a mark whose callout takes the spot right of the mark inside it.
        val outside = UiCallouts.Mark(1, Rectangle(300, 300, 60, 16), "first label", numbered = false, arrow = UiArrow(UiArrowSide.RIGHT))
        val inside = UiCallouts.Mark(2, Rectangle(380, 300, 20, 16), "x", numbered = false, arrow = UiArrow())
        val drawn = UiCallouts.arrows(c, listOf(outside, inside)).last()!!
        assertNotEquals(UiArrowSide.RIGHT, drawn.side)
        val area = UiCallouts.withMarks(c, Rectangle(378, 298, 24, 20), listOf(outside, inside))
        assertTrue("$area holds ${drawn.tail}", area.contains(drawn.tail))
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

    /** The top and bottom rows of the white text pixels in columns [xs] and rows [ys] of [c]'s image. */
    private fun textRows(c: UiCapture.Canvas, xs: IntRange, ys: IntRange): IntRange {
        val rows = ys.filter { y -> xs.any { x -> Color(c.image.getRGB(x, y)).let { it.red > 200 && it.green > 200 && it.blue > 200 } } }
        return rows.first()..rows.last()
    }

    @Test
    fun `a label's text sits in the middle of its box`() {
        // Unnumbered, the label goes right of the outline: the box spans rows 101-118 on screen, 51-68 in the picture.
        val painted = UiCallouts.highlight(canvas(400, 200, scale = 2.0), listOf(UiCallouts.Mark(1, Rectangle(150, 100, 60, 20), "pick a theme", numbered = false)))
        val rows = textRows(painted, (2 * 117 + 12)..(2 * 117 + 40), (2 * 51)..(2 * 69 - 1))
        val middle = (rows.first + rows.last) / 2.0
        assertEquals(rows.toString(), 2 * 51 + 2 * 18 / 2.0, middle, 1.5)
    }

    @Test
    fun `a badge's number sits in the middle of its badge`() {
        val c = canvas(400, 200, scale = 2.0)
        val bounds = Rectangle(200, 100, 60, 20)
        val badge = UiCallouts.badgeBounds(UiCallouts.outlines(listOf(bounds)).single(), 18, c.bounds, emptyList())
        val painted = UiCallouts.highlight(c, listOf(UiCallouts.Mark(7, bounds, null, numbered = true)))
        val x0 = 2 * (badge.x - 100)
        val y0 = 2 * (badge.y - 50)
        val rows = textRows(painted, (x0 + 10)..(x0 + 26), (y0 + 4)..(y0 + 32))
        assertEquals(rows.toString(), y0 + 18.0, (rows.first + rows.last) / 2.0, 1.5)
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
