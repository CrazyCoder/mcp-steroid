/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UiScenarioTest {
    private fun fails(json: String): String = assertThrows<IllegalArgumentException> { UiScenario.parse(json) }.message!!

    @Test
    fun `a scenario reads its fields, steps and cleanup`() {
        val scenario = UiScenario.parse(
            """
            {"scenario":1,"title":"Folding option","issue":"IDEA-1","ide":"IU-262.1","project":"any",
             "description":"the option does not stick",
             "steps":[{"action":"settings","page":"Code Folding"},{"action":"expect","name":"Show bottom arrows","is":"checked","bug":"it resets"}],
             "cleanup":[{"action":"close"}]}
            """.trimIndent()
        )
        assertEquals("Folding option", scenario.title)
        assertEquals("IDEA-1", scenario.issue)
        assertEquals("IU-262.1", scenario.ide)
        assertEquals(2, scenario.steps.size)
        assertEquals("it resets", scenario.steps[1].bug)
        assertEquals(UiAction.CLOSE, scenario.cleanup.single().action)
    }

    @Test
    fun `a scenario needs its version, a title and steps, and no unknown field`() {
        assertTrue(fails("""{"title":"t","steps":[{"action":"close"}]}""").contains("format version"))
        assertTrue(fails("""{"scenario":2,"title":"t","steps":[{"action":"close"}]}""").contains("format 2 is not known"))
        assertTrue(fails("""{"scenario":1,"steps":[{"action":"close"}]}""").contains("needs a title"))
        assertTrue(fails("""{"scenario":1,"title":"t","steps":[]}""").contains("needs steps"))
        assertTrue(fails("""{"scenario":1,"title":"t","step":[]}""").contains("unknown scenario field(s) step"))
        assertTrue(fails("""[1]""").contains("JSON object"))
    }

    @Test
    fun `a bad step names its list and number`() {
        assertTrue(fails("""{"scenario":1,"title":"t","steps":[{"action":"close"},{"action":"tap"}]}""").contains("steps: step 2"))
        assertTrue(fails("""{"scenario":1,"title":"t","steps":[{"action":"close"}],"cleanup":[{"action":"click"}]}""").contains("cleanup: step 1"))
    }

    private val click = UiStep(UiAction.CLICK, UiTarget(name = "OK"), intent = "confirm")
    private val bugCheck = UiStep(UiAction.EXPECT, UiTarget(name = "A"), bug = "A is lost")
    private val softCheck = UiStep(UiAction.EXPECT, UiTarget(name = "B"), soft = true)

    private fun ok(i: Int, s: UiStep) = UiStepOutcome(i, s, passed = true, message = "")
    private fun bad(i: Int, s: UiStep) = UiStepOutcome(i, s, passed = false, message = "no")

    @Test
    fun `a failing bug check reproduces the bug`() {
        val v = UiVerdict.of(listOf(click, bugCheck), listOf(ok(1, click), bad(2, bugCheck)))
        assertEquals(UiVerdict.Kind.REPRODUCED, v.kind)
        assertEquals("REPRODUCED at step 2: A is lost", v.line)
    }

    @Test
    fun `passing bug checks do not reproduce it`() {
        assertEquals(UiVerdict.Kind.NOT_REPRODUCED, UiVerdict.of(listOf(click, bugCheck), listOf(ok(1, click), ok(2, bugCheck))).kind)
        val later = UiVerdict.of(listOf(bugCheck, click), listOf(ok(1, bugCheck), bad(2, click)))
        assertEquals(UiVerdict.Kind.NOT_REPRODUCED, later.kind)
        assertTrue(later.line.contains("then step 2 failed"), later.line)
    }

    @Test
    fun `a failure before the bug check breaks the scenario and names what the step is for`() {
        val v = UiVerdict.of(listOf(click, bugCheck), listOf(bad(1, click)))
        assertEquals(UiVerdict.Kind.BROKEN, v.kind)
        assertTrue(v.line.contains("step 1") && v.line.contains("confirm"), v.line)
    }

    @Test
    fun `steps without a bug check pass or fail, and soft failures are counted`() {
        assertEquals(UiVerdict.Kind.PASSED, UiVerdict.of(listOf(click), listOf(ok(1, click))).kind)
        assertEquals(UiVerdict.Kind.FAILED, UiVerdict.of(listOf(click), listOf(bad(1, click))).kind)
        val soft = UiVerdict.of(listOf(softCheck, click), listOf(bad(1, softCheck), ok(2, click)))
        assertEquals(UiVerdict.Kind.PASSED, soft.kind)
        assertTrue(soft.line.contains("1 soft check(s) failed: steps 1"), soft.line)
    }

    @Test
    fun `a run that stops before its bug check is incomplete`() {
        assertEquals(UiVerdict.Kind.INCOMPLETE, UiVerdict.of(listOf(click, bugCheck), listOf(ok(1, click))).kind)
    }
}
