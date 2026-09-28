/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScrollAlignTest {
    @Test
    fun `top puts the area at the top of the view`() {
        assertEquals(500, UiScrollAlign.viewY(500, 20, 300, 2000, "top"))
    }

    @Test
    fun `center puts the area in the middle of the view`() {
        assertEquals(360, UiScrollAlign.viewY(500, 20, 300, 2000, "center"))
    }

    @Test
    fun `a report's screen bounds read back as written`() {
        val r = java.awt.Rectangle(-120, 340, 200, 24)
        val report = "scrolled JCheckBox \"Smart tabs\" into view; ${UiScrollAlign.boundsNote(r)}; focus: MyTree"
        assertEquals(r, UiScrollAlign.parseBounds(report))
        assertEquals(null, UiScrollAlign.parseBounds("scrolled into view"))
    }

    @Test
    fun `a report names what it scrolled, row included, without the other side's ref`() {
        val bounds = UiScrollAlign.boundsNote(java.awt.Rectangle(1, 2, 3, 4))
        assertEquals(
            "row #70 \"Java > Probable bugs > Nullability problems\" of InspectionsConfigTreeTable",
            UiScrollAlign.parseWhat("scrolled row #70 \"Java > Probable bugs > Nullability problems\" of InspectionsConfigTreeTable [ref=e12] to the middle of its view; 3 of 9 pages; $bounds"),
        )
        assertEquals("JCheckBox \"Smart tabs\"", UiScrollAlign.parseWhat("JCheckBox \"Smart tabs\" [ref=e3] is in no scroll pane, so it stays where it is; $bounds"))
        assertEquals(null, UiScrollAlign.parseWhat("scrolled into view"))
    }

    @Test
    fun `a control larger than its view is outlined as far as it shows, and never scrolled`() {
        val tree = java.awt.Dimension(300, 1400)
        val shown = java.awt.Rectangle(0, 500, 300, 600)
        assertEquals(shown to false, UiScrollAlign.wholeArea(tree, shown, java.awt.Dimension(300, 600)))
    }

    @Test
    fun `a control that fits its view is outlined whole, and scrolled when part of it is out of view`() {
        val box = java.awt.Dimension(200, 24)
        assertEquals(java.awt.Rectangle(0, 0, 200, 24) to false, UiScrollAlign.wholeArea(box, java.awt.Rectangle(0, 0, 200, 24), java.awt.Dimension(900, 600)))
        assertEquals(java.awt.Rectangle(0, 0, 200, 24) to true, UiScrollAlign.wholeArea(box, java.awt.Rectangle(0, 0, 200, 10), java.awt.Dimension(900, 600)))
        assertEquals(java.awt.Rectangle(0, 0, 200, 24) to false, UiScrollAlign.wholeArea(box, java.awt.Rectangle(0, 0, 200, 24), null))
    }

    @Test
    fun `the view stays within the content`() {
        assertEquals(1700, UiScrollAlign.viewY(1990, 10, 300, 2000, "top"))
        assertEquals(0, UiScrollAlign.viewY(10, 10, 300, 2000, "center"))
        assertEquals(0, UiScrollAlign.viewY(100, 10, 300, 200, "top"))
    }
}
