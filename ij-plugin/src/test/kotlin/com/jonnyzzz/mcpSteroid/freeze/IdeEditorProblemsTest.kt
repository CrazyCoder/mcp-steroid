/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeEditorProblemsTest {
    private val typeError = EditorProblem("proj", "src/a.ts", 1, 7, "ERROR", "TS2322: Type 'string' is not assignable to type 'number'.")
    private val comma = EditorProblem("proj", "src/a.ts", 5, 12, "ERROR", "TS1005: ',' expected.")
    private val other = EditorProblem("proj", "src/b.ts", 3, 1, "ERROR", "Unresolved variable x")

    @Test
    fun `errors are told when one is new to the session, and a moved error is not new`() {
        val problems = IdeEditorProblems()
        val session = Any()
        problems.current = listOf(typeError)
        assertTrue(problems.noticeFor(session)!!.contains("TS2322"))
        assertNull(problems.noticeFor(session))
        problems.current = listOf(typeError.copy(line = 4))
        assertNull("the same error after a line was added above it", problems.noticeFor(session))
        problems.current = listOf(typeError.copy(line = 4), comma)
        val notice = problems.noticeFor(session)!!
        assertTrue("every current error is counted", notice.contains("2 errors in 1 open file"))
        problems.current = emptyList()
        assertNull("fixed errors are not told", problems.noticeFor(session))
        problems.current = listOf(typeError)
        assertTrue("an error that came back is new", problems.noticeFor(session)!!.contains("TS2322"))
    }

    @Test
    fun `each file gives its count and its first error, the files with the most errors first`() {
        assertEquals(
            "EDITOR ERRORS: 3 errors in 2 open files; the steroid_ui step {\"action\":\"get\",\"problems\":\"<file>\"} lists a file's errors, " +
                "and with \"severity\":\"warning\" its warnings too:\n" +
                "- src/a.ts: 2 errors, first at 1:7: TS2322: Type 'string' is not assignable to type 'number'.\n" +
                "- src/b.ts: 1 error, first at 3:1: Unresolved variable x\n",
            IdeEditorProblems.render(listOf(comma, typeError, other)),
        )
    }

    @Test
    fun `past the file limit the rest is counted with how to list every open file`() {
        val files = (1..5).map { other.copy(file = "src/f$it.ts") }
        val notice = IdeEditorProblems.render(files)
        assertTrue(notice.contains("- and 2 more files, {\"action\":\"get\",\"problems\":true} lists every open file"))
        assertEquals(IdeEditorProblems.MAX_FILES + 1, notice.lines().count { it.startsWith("- ") })
    }

    private fun reading(problems: List<EditorProblem>, unfinished: Set<String> = emptySet()) = IdeEditorProblems.Reading(problems, unfinished)

    @Test
    fun `a list counts each severity and stops at its limit`() {
        val warning = comma.copy(severity = "WARNING", text = "Unused variable")
        assertEquals(
            "2 problem(s) of warning severity or above in src/a.ts (error 1, warning 1):\nsrc/a.ts:1:7: ERROR ${typeError.text}\nsrc/a.ts:5:12: WARNING Unused variable",
            IdeEditorProblems.renderList(reading(listOf(typeError, warning)), "warning", "src/a.ts"),
        )
        val many = (1..IdeEditorProblems.MAX_LISTED + 5).map { typeError.copy(line = it) }
        assertTrue(IdeEditorProblems.renderList(reading(many), "error", "the open files").endsWith("… and 5 more; name one file or raise the severity"))
        assertEquals("no problem of error severity or above in src/a.ts", IdeEditorProblems.renderList(reading(emptyList()), "error", "src/a.ts"))
    }

    @Test
    fun `a list names the files not analyzed to the end instead of reporting them clean`() {
        val list = IdeEditorProblems.renderList(reading(emptyList(), setOf("src/b.ts")), "error", "src/b.ts")
        assertEquals(
            "no problem of error severity or above in src/b.ts\nnot analyzed to the end, so possibly incomplete: src/b.ts. " +
                "The editor analyzes a file while its tab shows: select the tab with a goto, then get again.",
            list,
        )
    }

    @Test
    fun `the reader knows every severity a get step takes, in the same order`() {
        assertEquals(com.jonnyzzz.mcpSteroid.server.UiSteps.SEVERITIES, IdeEditorProblems.SEVERITIES.keys.toList())
    }

    @Test
    fun `a refresh that cannot read the editors keeps the last reading instead of failing the call`() {
        val problems = IdeEditorProblems()
        problems.current = listOf(typeError)
        runBlocking { problems.refresh() }
        assertEquals(listOf(typeError), problems.current)
    }
}
