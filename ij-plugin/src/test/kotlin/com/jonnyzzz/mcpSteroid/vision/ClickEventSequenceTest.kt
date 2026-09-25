/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

class ClickEventSequenceTest {
    private val shift = InputEvent.SHIFT_DOWN_MASK

    @Test
    fun `a left click is move, press with the down mask, release, click`() {
        assertEquals(
            listOf(
                ClickEvent(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON, shift, 0, false),
                ClickEvent(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, shift or InputEvent.BUTTON1_DOWN_MASK, 1, false),
                ClickEvent(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, shift, 1, false),
                ClickEvent(MouseEvent.MOUSE_CLICKED, MouseEvent.BUTTON1, shift, 1, false),
            ),
            clickEventSequence(MouseEvent.BUTTON1, shift),
        )
    }

    @Test
    fun `a right click is a popup trigger with the button 3 down mask on press`() {
        assertEquals(
            listOf(
                ClickEvent(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON, 0, 0, false),
                ClickEvent(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3, InputEvent.BUTTON3_DOWN_MASK, 1, true),
                ClickEvent(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON3, 0, 1, true),
                ClickEvent(MouseEvent.MOUSE_CLICKED, MouseEvent.BUTTON3, 0, 1, true),
            ),
            clickEventSequence(MouseEvent.BUTTON3, 0),
        )
    }

    @Test
    fun `a middle click uses the button 2 down mask on press`() {
        val press = clickEventSequence(MouseEvent.BUTTON2, 0).single { it.id == MouseEvent.MOUSE_PRESSED }
        assertEquals(InputEvent.BUTTON2_DOWN_MASK, press.modifiers)
    }
}
