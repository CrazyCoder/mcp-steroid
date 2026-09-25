/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Point
import java.awt.Rectangle

class TopmostWindowAtTest {
    private val frame = WindowCandidate("frame", Rectangle(0, 0, 1000, 800), owners = emptyList())
    private val popup = WindowCandidate("popup", Rectangle(400, 300, 200, 150), owners = listOf("frame"))
    private val otherFrame = WindowCandidate("other", Rectangle(200, 200, 600, 400), owners = emptyList())

    @Test
    fun `a click on a popup over the named frame goes to the popup`() {
        assertEquals("popup", topmostWindowAt(Point(450, 350), "frame", listOf(frame, popup)))
    }

    @Test
    fun `a click outside the popup stays on the named frame`() {
        assertEquals("frame", topmostWindowAt(Point(50, 50), "frame", listOf(frame, popup)))
    }

    @Test
    fun `a nested popup is above the popup that owns it`() {
        val submenu = WindowCandidate("submenu", Rectangle(500, 320, 200, 100), owners = listOf("popup", "frame"))
        assertEquals("submenu", topmostWindowAt(Point(550, 350), "frame", listOf(frame, popup, submenu)))
    }

    @Test
    fun `an unrelated window over the point does not take the click from the named window`() {
        assertEquals("frame", topmostWindowAt(Point(300, 250), "frame", listOf(frame, otherFrame)))
    }

    @Test
    fun `a popup of another window over the point does not take the click`() {
        val othersPopup = WindowCandidate("othersPopup", Rectangle(400, 300, 100, 100), owners = listOf("other"))
        assertEquals("frame", topmostWindowAt(Point(450, 350), "frame", listOf(frame, otherFrame, othersPopup)))
    }

    @Test
    fun `a point outside the named window goes to the topmost window there`() {
        assertEquals("popup", topmostWindowAt(Point(450, 350), "other-id", listOf(frame, popup)))
    }

    @Test
    fun `an owner listed after its popup is still below it`() {
        assertEquals("popup", topmostWindowAt(Point(450, 350), "other-id", listOf(popup, frame)))
    }

    @Test
    fun `a point outside every window has no target`() {
        assertNull(topmostWindowAt(Point(5000, 5000), "frame", listOf(frame, popup)))
    }
}
