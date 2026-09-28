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

    @Test
    fun `a setup block names only what it knows, and each part checks as a step`() {
        fun withSetup(setup: String) = """{"scenario":1,"title":"t","setup":$setup,"steps":[{"action":"close"}]}"""
        assertTrue(fails(withSetup("""{"windows":{}}""")).contains("unknown setup field(s) windows"))
        assertTrue(fails(withSetup("""{"window":{"width":10}}""")).contains("setup.window"))
        assertTrue(fails(withSetup("""{"window":{"title":"x"}}""")).contains("setup.window takes"))
        assertTrue(fails(withSetup("""{"menu":"sideways"}""")).contains("unknown menu mode"))
        assertTrue(fails(withSetup("""{"toolwindows":{"Project":{"side":"left"}}}""")).contains("setup.toolwindows.Project takes"))
        assertTrue(fails(withSetup("""{"settings":[{"registry":"a"}]}""")).contains("set needs a value"))
        assertTrue(fails(withSetup("""{"layout":"always"}""")).contains("setup.layout is one of"))
        val s = UiScenario.parse(withSetup("""{"toolwindows":{"Project":{"width":"fit"}},"window":{"maximize":true}}"""))
        assertEquals(listOf(UiAction.WINDOW, UiAction.TOOLWINDOW), s.setup.map { it.action })
        assertEquals(null, s.layout)
    }

    @Test
    fun `requires tells what an IDE lacks`() {
        val r = UiScenarioRequires(since = "262.10000", until = "262.*", products = listOf("IU"), plugins = listOf("org.jetbrains.kotlin"),
            os = listOf("windows"), mode = "split")
        val here = UiScenarioRequires.Here("262.10968.63", "IU", setOf("org.jetbrains.kotlin"), "windows", "split")
        assertEquals(emptyList<String>(), r.unmet(here))
        assertEquals(listOf("build 261.1 is older than 262.10000"), r.unmet(here.copy(build = "261.1")))
        assertEquals(listOf("build 263.1 is newer than 262.*"), r.unmet(here.copy(build = "263.1")))
        assertEquals(listOf("product IC is not IU"), r.unmet(here.copy(product = "IC")))
        assertEquals(listOf("plugin(s) org.jetbrains.kotlin not enabled"), r.unmet(here.copy(plugins = emptySet())))
        assertEquals(listOf("the OS is linux, not windows"), r.unmet(here.copy(os = "linux")))
        assertEquals(listOf("this is a regular IDE, not Split Mode"), r.unmet(here.copy(mode = "monolith")))
        assertEquals(0, UiScenarioRequires.compareBuild("262.10968", "262.10968.0"))
        assertTrue(UiScenarioRequires.compareBuild("262.9", "262.10") < 0)
    }

    @Test
    fun `requires takes known fields and values only`() {
        fun withRequires(r: String) = """{"scenario":1,"title":"t","requires":$r,"steps":[{"action":"close"}]}"""
        assertTrue(fails(withRequires("""{"build":"262"}""")).contains("unknown requires field(s) build"))
        assertTrue(fails(withRequires("""{"since":"IU-262.1"}""")).contains("without the product code"))
        assertTrue(fails(withRequires("""{"os":["dos"]}""")).contains("requires.os lists"))
        assertTrue(fails(withRequires("""{"mode":"remote"}""")).contains("requires.mode is"))
        assertTrue(fails(withRequires("""{"plugins":"a"}""")).contains("array of strings"))
        assertEquals(listOf("a"), UiScenario.parse(withRequires("""{"plugins":["a"]}""")).requires?.plugins)
    }

    @Test
    fun `a skipped scenario says what the IDE lacks and is not a failure`() {
        val v = UiVerdict.skipped(listOf("the OS is linux, not windows"))
        assertEquals(UiVerdict.Kind.SKIPPED, v.kind)
        assertTrue(v.line.startsWith("SKIPPED: "))
        assertTrue(!UiScenarioBatch.isBad(UiScenarioBatch.verdictOf("x\n${v.line}")))
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
    fun `the backend's notices in front of a forwarded step's answer are taken apart, one per notice`() {
        val errors = "EDITOR ERRORS in the backend: 2 errors in 1 open file; ...:\n- src/a.ts: 2 errors, first at 1:7: TS2322\n"
        val build = "BUILD FAILED in the backend: a build failed since your last call:\n- 10:00:00 mcp: 1 error\n  src/a.ts:1: X\n"
        val answer = "execution_id: e1\nstep 1 get: ok"
        val (notices, rest) = UiForwardedStep.notices(listOf(errors + build, answer))
        assertEquals(listOf(errors, build), notices)
        assertEquals(listOf(answer), rest)
        // An answer alone, or a first text that is not a backend notice, is left whole.
        assertEquals(emptyList<String>() to listOf(answer), UiForwardedStep.notices(listOf(answer)))
        val client = "EDITOR STATE: the JetBrains Client and the backend disagree:\n- a\n"
        assertEquals(emptyList<String>() to listOf(client, answer), UiForwardedStep.notices(listOf(client, answer)))
    }

    @Test
    fun `a run that stops before its bug check is incomplete`() {
        assertEquals(UiVerdict.Kind.INCOMPLETE, UiVerdict.of(listOf(click, bugCheck), listOf(ok(1, click))).kind)
    }
}
