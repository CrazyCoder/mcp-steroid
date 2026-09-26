/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.vision.InputModifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel

class UiInputEventsTest {
    private val source = JPanel()

    @Test
    fun `a key chord parses modifiers and key in any case`() {
        val chord = UiInput.parseKeys("ctrl+shift+A")
        assertEquals(KeyEvent.VK_A, chord.keyCode)
        assertEquals(setOf(InputModifier.CTRL, InputModifier.SHIFT), chord.modifiers)
        assertEquals(KeyEvent.VK_ENTER, UiInput.parseKeys("ENTER").keyCode)
        assertEquals(setOf(InputModifier.META), UiInput.parseKeys("meta+1").modifiers)
    }

    @Test
    fun `a bad key name fails with the name`() {
        val e = runCatching { UiInput.parseKeys("ctrl+NOPE") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.contains("NOPE"))
    }

    @Test
    fun `a double click is two moves and two press-release-click triples with counts 1 and 2`() {
        val events = UiInput.gestureEvents(MouseEvent.BUTTON1, 0, count = 2)
        assertEquals(
            listOf(
                MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_MOVED,
                MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED,
                MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED,
            ),
            events.map { it.id },
        )
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 2, 2), events.map { it.clickCount })
    }

    @Test
    fun `a single click is the 0_109 click sequence`() {
        assertEquals(5, UiInput.gestureEvents(MouseEvent.BUTTON1, 0, count = 1).size)
    }

    @Test
    fun `a typed letter is pressed, typed and released on one source`() {
        val events = UiInput.typedCharEvents(source, 'a', 0, now = 1)
        assertEquals(listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED, KeyEvent.KEY_RELEASED), events.map { it.id })
        assertEquals(KeyEvent.VK_A, events[0].keyCode)
        assertEquals('a', events[1].keyChar)
        assertTrue(events.all { it.source === source })
    }

    @Test
    fun `an upper-case letter carries shift`() {
        val events = UiInput.typedCharEvents(source, 'A', 0, now = 1)
        assertTrue(events.all { it.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0 })
    }

    @Test
    fun `a character no key produces is only typed`() {
        val events = UiInput.typedCharEvents(source, 'x', 0, now = 1, keyCodeFor = { KeyEvent.VK_UNDEFINED })
        assertEquals(listOf(KeyEvent.KEY_TYPED), events.map { it.id })
    }
}
