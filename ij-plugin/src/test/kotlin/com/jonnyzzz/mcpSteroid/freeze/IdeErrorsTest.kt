/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.jonnyzzz.mcpSteroid.execution.CapturedIdeException
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class IdeErrorsTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val errors = IdeErrors(scope)
    private val now = System.currentTimeMillis()

    @Test
    fun `a session hears the recent errors once, then only new ones`() {
        errors.add("OldException: long ago", now - IdeErrors.RECENT_MS - 1_000, null)
        errors.add("RecentException: a minute ago", now - 60_000, null)
        val session = Any()

        val first = errors.noticeFor(session, now)!!
        assertTrue(first, first.contains("RecentException"))
        assertFalse(first, first.contains("OldException"))
        assertNull(errors.noticeFor(session, now))

        errors.add("NewException: just now", now, "com.example.plugin")
        val next = errors.noticeFor(session, now)!!
        assertTrue(next, next.startsWith("IDE ERRORS: the IDE logged 1 error since your last call"))
        assertTrue(next, next.contains("NewException: just now [plugin com.example.plugin]"))
        assertFalse(next, next.contains("RecentException"))
    }

    @Test
    fun `errors the call reported itself are not repeated`() {
        val session = Any()
        errors.noticeFor(session, now)
        errors.add("BeforeCall: between calls", now - 5_000, null)
        errors.reportedBy(session, now - 1_000, now)
        assertTrue(errors.noticeFor(session, now)!!.contains("BeforeCall"))

        // Logged during the call, but reaching the service only after the call's notice was built.
        errors.add("DuringCall: listed in the execution result", now - 500, null)
        assertNull(errors.noticeFor(session, now))
        assertTrue("another session was not shown it", errors.noticeFor(Any(), now)!!.contains("DuringCall"))
    }

    @Test
    fun `a split frontend's copy of a backend error is left to the backend`() {
        fun captured(t: Throwable) = CapturedIdeException(java.time.Instant.ofEpochMilli(now), t, t.message, "", null)
        errors.add(captured(com.intellij.diagnostic.RemoteSerializedThrowable("BackendProbe", "BackendProbe", "java.lang.IllegalStateException", emptyArray(), null)))
        errors.add(captured(IllegalStateException("FrontendProbe")))
        val notice = errors.noticeFor(Any(), now)!!
        assertTrue(notice, notice.contains("FrontendProbe"))
        assertFalse(notice, notice.contains("BackendProbe"))
    }

    @Test
    fun `the notice groups repeats, caps its lines, and names the log`() {
        var seq = 0L
        val logged = List(5) { IdeError(++seq, now, "Same: again", null) } + (1..4).map { IdeError(++seq, now, "Kind$it: once", null) }
        val text = IdeErrors.render(logged, Path.of("logs", "idea.log"))

        assertTrue(text, text.startsWith("IDE ERRORS: the IDE logged 9 errors since your last call; full stack traces are in ${Path.of("logs", "idea.log")}."))
        assertEquals(text, 1 + IdeErrors.MAX_LINES + 1, text.trimEnd().lines().size)
        assertTrue(text, text.contains("Kind4: once"))
        assertTrue(text, text.contains("- and 2 more kinds of error"))
        assertFalse("the oldest kinds give way to the newest", text.contains("Same: again"))
        assertTrue(IdeErrors.render(logged, null, "the backend").startsWith("IDE ERRORS in the backend: the IDE logged 9 errors since your last call."))
    }

    @Test
    fun `a summary is one clipped line with the throwing frame`() {
        val t = IllegalStateException("Write-unsafe context! " + "x".repeat(300) + "\nsecond line")
        t.stackTrace = arrayOf(StackTraceElement("com.example.LibraryTable", "commit", "LibraryTable.kt", 42))
        val summary = IdeErrors.summaryOf(t)
        assertTrue(summary, summary.startsWith("IllegalStateException: Write-unsafe context! x"))
        assertTrue(summary, summary.endsWith("… at LibraryTable.commit(LibraryTable.kt:42)"))
        assertFalse(summary, summary.contains("second line"))
        assertTrue(summary, summary.length < 250)

        val fromScript = IllegalArgumentException("probe")
        fromScript.stackTrace = arrayOf(StackTraceElement("Script__jonnyzzz_eid_20260927T003126_053_mcp_s_probe", "code", "input.kt", 31))
        assertEquals("IllegalArgumentException: probe at a steroid_execute_code script, input.kt:31", IdeErrors.summaryOf(fromScript))
    }

    @Test
    fun `guard puts the errors in front of the result, except those execute_code reported`() = runBlocking {
        try {
            val monitor = FreezeMonitor(scope).also { it.ideErrors = { errors }; it.ideBanners = { null } }
            val session = Any()
            val ok = ToolCallResult(listOf(ContentItem.Text("ok")))
            assertEquals(ok, monitor.guard(session) { ok })

            val other = monitor.guard(session) { errors.add("DuringRefactor: boom", System.currentTimeMillis(), null); ok }
            assertTrue((other.content.first() as ContentItem.Text).text.contains("DuringRefactor"))

            val execute = monitor.guard(session, reportsIdeErrors = true) { errors.add("DuringScript: boom", System.currentTimeMillis(), null); ok }
            assertEquals(ok, execute)
        } finally {
            scope.cancel()
        }
    }
}
