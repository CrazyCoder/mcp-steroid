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

    @Test
    fun `an unknown name suggests a newer plugin, and a wrong step does not`() {
        val newer = "written for a newer MCP Steroid"
        assertTrue(fails("""{"scenario":1,"title":"t","steps":[{"action":"tap"}]}""").contains(newer))
        assertTrue(fails("""{"scenario":1,"title":"t","steps":[{"action":"close","shadow":1}]}""").contains(newer))
        assertTrue(fails("""{"scenario":1,"title":"t","tags":[],"steps":[{"action":"close"}]}""").contains(newer))
        assertTrue(fails("""{"scenario":2,"title":"t","steps":[{"action":"close"}]}""").contains("Update MCP Steroid"))
        assertTrue(!fails("""{"scenario":1,"title":"t","steps":[{"action":"click"}]}""").contains(newer))
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

    private val check = UiStep(UiAction.EXPECT, UiTarget(name = "Apply"), intent = "Apply is enabled")

    @Test
    fun `without a bug check, a check that fails is FAILED and a step that cannot be done is BROKEN`() {
        val failed = UiVerdict.of(listOf(click, check), listOf(ok(1, click), bad(2, check)))
        assertEquals(UiVerdict.Kind.FAILED, failed.kind)
        assertEquals("FAILED at step 2: the check did not hold (Apply is enabled)", failed.line)
        val broken = UiVerdict.of(listOf(click, check), listOf(bad(1, click)))
        assertEquals(UiVerdict.Kind.BROKEN, broken.kind)
        assertTrue(broken.line.contains("could not be done") && broken.line.contains("confirm"), broken.line)
    }

    @Test
    fun `steps without a bug check pass, and soft failures are counted`() {
        assertEquals(UiVerdict.Kind.PASSED, UiVerdict.of(listOf(click), listOf(ok(1, click))).kind)
        val soft = UiVerdict.of(listOf(softCheck, click), listOf(bad(1, softCheck), ok(2, click)))
        assertEquals(UiVerdict.Kind.PASSED, soft.kind)
        assertTrue(soft.line.contains("1 soft check(s) failed: steps 1"), soft.line)
    }

    @Test
    fun `a forwarded step's report is its own line and what follows, without the backend's verdict`() {
        val get = UiStep(UiAction.GET, null, option = "line numbers")
        val label = UiForwardedStep.label(get)
        val passed = UiForwardedStep.parse(
            "execution_id: e1 (5 ms)\n$label: 1 option(s) match \"line numbers\":\n  \"Show line numbers\" = true\nPASSED: all 1 step(s)\nrecorded: 1 step(s)",
            label, isError = false,
        )
        assertEquals(UiForwardedStep.Report(true, "1 option(s) match \"line numbers\":\n  \"Show line numbers\" = true"), passed)
        val expect = UiStep(UiAction.EXPECT, null, file = "A.kt")
        val failed = UiForwardedStep.parse(
            "IDE ERRORS: one\nexecution_id: e2 (9 ms)\nFAILED ${UiForwardedStep.label(expect)} failed: expected A.kt containing \"x\"\nFAILED at step 1: the check did not hold",
            UiForwardedStep.label(expect), isError = false,
        )
        assertEquals(UiForwardedStep.Report(false, "expected A.kt containing \"x\""), failed)
        val goto = UiStep(UiAction.GOTO, null, file = "A.kt")
        val moved = UiForwardedStep.parse(
            "${UiForwardedStep.label(goto)}: caret at A.kt:1:1; focus: MemoryUsagePanelImpl \"Memory Usage\" [ref=e2]\nPASSED: all 1 step(s)",
            UiForwardedStep.label(goto), isError = false,
        )
        assertEquals(UiForwardedStep.Report(true, "caret at A.kt:1:1"), moved)
        val code = UiStep(UiAction.CODE, null, code = "println(1)")
        val ran = UiForwardedStep.parse(
            "execution_id: e3 (9 ms)\n${UiForwardedStep.label(code)}: ran the code:\nexecution_id: e4\n1\nPASSED: all 1 step(s)",
            UiForwardedStep.label(code), isError = false,
        )
        assertEquals(UiForwardedStep.Report(true, "ran the code:\n1"), ran)
        assertEquals(false, UiForwardedStep.parse("ERROR: unknown project", label, isError = true).passed)
    }

    @Test
    fun `a run that stops before its bug check is incomplete`() {
        assertEquals(UiVerdict.Kind.INCOMPLETE, UiVerdict.of(listOf(click, bugCheck), listOf(ok(1, click))).kind)
    }
}
