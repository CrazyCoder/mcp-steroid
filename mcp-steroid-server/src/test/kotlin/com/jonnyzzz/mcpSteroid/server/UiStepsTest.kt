/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UiStepsTest {
    private fun fails(json: String): String = assertThrows<IllegalArgumentException> { UiSteps.parse(json) }.message!!

    @Test
    fun `a flat click step parses into a step with a name target`() {
        val step = UiSteps.parse("""[{"action":"click","name":"OK"}]""").single()
        assertEquals(UiAction.CLICK, step.action)
        assertEquals(UiTarget(name = "OK"), step.target)
        assertEquals(UiSteps.DEFAULT_TIMEOUT_MS, step.timeoutMs)
    }

    @Test
    fun `every target field and nth are lifted into the target`() {
        val step = UiSteps.parse("""[{"action":"click","text":"Apply","class":"JButton","nth":1,"timeout_ms":900}]""").single()
        assertEquals(UiTarget(text = "Apply", cls = "JButton", nth = 1), step.target)
        assertEquals(900L, step.timeoutMs)
    }

    @Test
    fun `a ref target is kept as a ref`() {
        assertEquals(UiTarget(ref = "e12"), UiSteps.parse("""[{"action":"hover","ref":"e12"}]""").single().target)
    }

    @Test
    fun `an unknown action fails with its index`() {
        val message = fails("""[{"action":"click","name":"OK"},{"action":"tap","name":"X"}]""")
        assertTrue(message.contains("step 2") && message.contains("tap"), message)
    }

    @Test
    fun `an unknown field fails instead of being ignored`() {
        val message = fails("""[{"action":"click","nmae":"OK"}]""")
        assertTrue(message.contains("nmae"), message)
    }

    @Test
    fun `a click without a target fails`() {
        assertTrue(fails("""[{"action":"click"}]""").contains("needs a target"))
    }

    @Test
    fun `fill needs text and press needs keys`() {
        assertTrue(fails("""[{"action":"fill","name":"Search"}]""").contains("text"))
        assertTrue(fails("""[{"action":"press"}]""").contains("keys"))
    }

    @Test
    fun `select needs a row or an index`() {
        assertTrue(fails("""[{"action":"select","name":"Settings categories"}]""").contains("row"))
        assertEquals("Editor", UiSteps.parse("""[{"action":"select","name":"t","row":"Editor"}]""").single().row)
    }

    @Test
    fun `wait needs a known condition and what it waits for`() {
        assertTrue(fails("""[{"action":"wait","for":"soon"}]""").contains("soon"))
        assertTrue(fails("""[{"action":"wait","for":"visible"}]""").contains("needs a target"))
        assertTrue(fails("""[{"action":"wait","for":"window"}]""").contains("title"))
        val idle = UiSteps.parse("""[{"action":"wait","for":"idle"}]""").single()
        assertEquals(UiWaitCondition.IDLE, idle.condition)
        assertNull(idle.target)
    }

    @Test
    fun `type and close take an optional target`() {
        val steps = UiSteps.parse("""[{"action":"type","text":"abc"},{"action":"close"}]""")
        assertNull(steps[0].target)
        assertNull(steps[1].target)
    }

    @Test
    fun `click options are read`() {
        val step = UiSteps.parse("""[{"action":"click","name":"row","button":"right","count":2,"modifiers":"ctrl+shift","offset_x":3,"offset_y":4}]""").single()
        assertEquals("right", step.button)
        assertEquals(2, step.count)
        assertEquals("ctrl+shift", step.modifiers)
        assertEquals(3, step.offsetX)
        assertEquals(4, step.offsetY)
    }

    @Test
    fun `a bad button or count fails`() {
        assertTrue(fails("""[{"action":"click","name":"a","button":"side"}]""").contains("button"))
        assertTrue(fails("""[{"action":"click","name":"a","count":3}]""").contains("count"))
    }

    @Test
    fun `input that is not an array of objects fails`() {
        assertTrue(fails("""{"action":"click"}""").contains("JSON array"))
        assertTrue(fails("""[1]""").contains("object"))
        assertTrue(fails("""[{"action":"click",""").contains("JSON"))
    }
}
