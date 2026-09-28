/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UiScenarioBatchTest {
    @TempDir
    lateinit var root: Path

    private fun file(rel: String): Path = root.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, "{}") }

    private fun fails(spec: String): String = assertThrows<IllegalArgumentException> { UiScenarioBatch.expand(spec, root) }.message!!

    @Test
    fun `a single file is not a batch`() {
        file("a.scenario.json")
        assertNull(UiScenarioBatch.expand("a.scenario.json", root))
        assertNull(UiScenarioBatch.expand("missing.scenario.json", root)) // the plain path reports the missing file
    }

    @Test
    fun `a folder replays its scenario files at any depth, in path order, and skips other files`() {
        val b = file("triage/B-2/repro/check.scenario.json")
        val a = file("triage/A-1/repro/check.scenario.json")
        file("triage/A-1/repro/notes.json")
        file("triage/A-1/repro/check.scenario.json.bak")
        assertEquals(listOf(a, b), UiScenarioBatch.expand("triage", root))
    }

    @Test
    fun `a list takes files and folders, relative or absolute, once each`() {
        val a = file("one/a.scenario.json")
        val b = file("two/b.scenario.json")
        val spec = """["one/a.scenario.json", "${root.resolve("two").toString().replace("\\", "\\\\")}", "one"]"""
        assertEquals(listOf(a, b), UiScenarioBatch.expand(spec, root))
    }

    @Test
    fun `a wrong list or folder fails with a message that names the problem`() {
        Files.createDirectories(root.resolve("empty"))
        assertTrue(fails("empty").contains("no *.scenario.json file under"))
        assertTrue(fails("[\"a\"").contains("not a JSON array"))
        assertTrue(fails("[1]").contains("path string"))
        assertTrue(fails("[]").contains("empty"))
        repeat(UiScenarioBatch.MAX_FILES + 1) { file("many/s$it.scenario.json") }
        assertTrue(fails("many").contains("more than the ${UiScenarioBatch.MAX_FILES}"))
    }

    @Test
    fun `the verdict of a report is its last verdict line, else its error`() {
        assertEquals("PASSED: all 3 step(s)", UiScenarioBatch.verdictOf("execution_id: x\nstep 1 click: ok\nPASSED: all 3 step(s)\nrecorded: nothing"))
        assertEquals("NOT REPRODUCED: step 4 passed", UiScenarioBatch.verdictOf("step 4 expect: fine\nNOT REPRODUCED: step 4 passed"))
        assertEquals("ERROR: no scenario file at x", UiScenarioBatch.verdictOf("ERROR: no scenario file at x"))
        assertEquals("no verdict: see its report below", UiScenarioBatch.verdictOf("step 1 click: ok"))
        // A failed step's own line, and a step line that mentions a verdict word, are not the verdict.
        assertEquals(
            "FAILED at step 2: the check did not hold",
            UiScenarioBatch.verdictOf("step 1 expect: PASSED text\nFAILED step 2 expect failed: expected x\nFAILED at step 2: the check did not hold"),
        )
        assertEquals("REPRODUCED at step 3: the tab stays", UiScenarioBatch.verdictOf("step 3 expect: x\nREPRODUCED at step 3: the tab stays"))
        assertEquals("BROKEN at step 1: the step could not be done", UiScenarioBatch.verdictOf("BROKEN at step 1: the step could not be done"))
    }

    @Test
    fun `every verdict UiVerdict writes is read back as that verdict`() {
        // UiVerdict owns the verdict lines; this reads each kind it writes, so a change to its wording fails here.
        val click = UiStep(UiAction.CLICK, UiTarget(name = "OK"), intent = "confirm")
        val check = UiStep(UiAction.EXPECT, UiTarget(name = "A"))
        val bug = UiStep(UiAction.EXPECT, UiTarget(name = "A"), bug = "A is lost")
        fun ok(i: Int, s: UiStep) = UiStepOutcome(i, s, passed = true, message = "")
        fun bad(i: Int, s: UiStep) = UiStepOutcome(i, s, passed = false, message = "no")
        val verdicts = listOf(
            UiVerdict.of(listOf(click), listOf(ok(1, click))),
            UiVerdict.of(listOf(click, check), listOf(ok(1, click), bad(2, check))),
            UiVerdict.of(listOf(click), listOf(bad(1, click))),
            UiVerdict.of(listOf(click, bug), listOf(ok(1, click), bad(2, bug))),
            UiVerdict.of(listOf(click, bug), listOf(ok(1, click), ok(2, bug))),
            UiVerdict.of(listOf(click, bug), listOf(ok(1, click))),
        )
        assertEquals(UiVerdict.Kind.entries.toSet(), verdicts.map { it.kind }.toSet())
        for (v in verdicts) {
            val report = "step 1 click: ok\nFAILED step 2 expect failed: no\n${v.line}\nrecorded: 2 step(s)"
            assertEquals(v.line, UiScenarioBatch.verdictOf(report), v.kind.name)
            assertEquals(v.kind == UiVerdict.Kind.FAILED || v.kind == UiVerdict.Kind.BROKEN, UiScenarioBatch.isBad(v.line), v.kind.name)
        }
    }

    @Test
    fun `failures, broken runs, errors and missing verdicts need a look, the rest do not`() {
        listOf("FAILED: step 2", "BROKEN: step 1", "ERROR: x", "no verdict: see its report below").forEach { assertTrue(UiScenarioBatch.isBad(it), it) }
        listOf("PASSED: all", "REPRODUCED: step 3", "NOT REPRODUCED: step 3", "INCOMPLETE: stopped").forEach { assertFalse(UiScenarioBatch.isBad(it), it) }
    }

    @Test
    fun `files outside the project are named from the folder they share`() {
        val other = root.resolve("elsewhere/triage")
        val text = UiScenarioBatch.render(
            listOf(other.resolve("A-1/repro/a.scenario.json") to "PASSED: all 1 step(s)", other.resolve("B-2/repro/b.scenario.json") to "PASSED: all 1 step(s)"),
            root.resolve("project"),
        )
        assertTrue(text.contains("\n- A-1/repro/a.scenario.json: PASSED"), text)
        assertTrue(text.contains("\n- B-2/repro/b.scenario.json: PASSED"), text)
    }

    @Test
    fun `the result counts each verdict, lists one line per file, then each report`() {
        val a = root.resolve("t/a.scenario.json")
        val b = root.resolve("t/b.scenario.json")
        val text = UiScenarioBatch.render(listOf(a to "step 1: ok\nREPRODUCED at step 1: the bug", b to "ERROR: no scenario file"), root)
        assertEquals(
            """
            replayed 2 scenario file(s): 1 REPRODUCED, 1 ERROR
            - t/a.scenario.json: REPRODUCED at step 1: the bug
            - t/b.scenario.json: ERROR: no scenario file

            == t/a.scenario.json ==
            step 1: ok
            REPRODUCED at step 1: the bug

            == t/b.scenario.json ==
            ERROR: no scenario file
            """.trimIndent(),
            text,
        )
    }
}
