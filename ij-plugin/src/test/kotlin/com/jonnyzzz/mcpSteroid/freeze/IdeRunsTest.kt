/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeRunsTest {
    private val runs = IdeRuns()

    private fun finish(run: IdeRuns.Run, exitCode: Int, stopped: Boolean = false) {
        run.stopped = stopped
        run.exitCode = exitCode
        run.endedMs = System.currentTimeMillis()
        runs.ended(run)
    }

    @Test
    fun `a failed run is told once with its last stderr lines`() {
        val session = Any()
        runs.noticeFor(session)
        val run = runs.start("refplay", "App", "Run")
        run.append("total: 5\n", "stdout")
        run.append("Exception in thread \"main\" java.lang.IllegalStateException: boom\n\tat demo.App.main(App.java:12)\n", "stderr")
        finish(run, exitCode = 1)

        val notice = runs.noticeFor(session)!!
        assertTrue(notice, notice.startsWith("RUN FAILED: a run failed since your last call"))
        assertTrue(notice, notice.contains("'App' (Run) started "))
        assertTrue(notice, notice.contains("exited with code 1"))
        assertTrue(notice, notice.contains("\n  ! Exception in thread \"main\" java.lang.IllegalStateException: boom\n  ! \tat demo.App.main(App.java:12)"))
        assertNull(runs.noticeFor(session))
    }

    @Test
    fun `a run that passed or that the user stopped is not a failure`() {
        val session = Any()
        runs.noticeFor(session)
        finish(runs.start("refplay", "App", "Run"), exitCode = 0)
        finish(runs.start("refplay", "Server", "Run"), exitCode = 130, stopped = true)
        assertNull(runs.noticeFor(session))
    }

    @Test
    fun `a get reads the latest run by name, its state and last lines, and a partial last line`() {
        val old = runs.start("refplay", "App", "Run")
        old.append("old\n", "stdout")
        finish(old, exitCode = 0)
        val run = runs.start("refplay", "App", "Debug")
        run.append("one\ntwo\nthr", "stdout")
        run.append("ee", "stdout")
        run.flush()
        val report = runs.report("refplay", "app", 2)
        assertTrue(report, report.startsWith("'App' (Debug) started "))
        assertTrue(report, report.contains("still running: 3 lines, the last 2"))
        assertEquals(listOf("two", "three"), report.lines().drop(1))
        assertTrue(runs.report("refplay", "Nope", 5).startsWith("no run named \"Nope\" since the IDE started; runs: \"App\""))
    }
}
