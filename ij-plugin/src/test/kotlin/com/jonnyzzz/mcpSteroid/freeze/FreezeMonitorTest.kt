/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.jonnyzzz.mcpSteroid.execution.RunningExecutions
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class FreezeMonitorTest {
    private fun f(cls: String, method: String, file: String = "X.kt", line: Int = 1) = StackTraceElement(cls, method, file, line)

    private val scriptClass = "Script__jonnyzzz_eid_20260926T224959_231_support_toolkit_s_safe_inspections"

    /** The freeze of 2026-09-26: the UI waits on a VFS refresh, which waits on a script and a TypeScript worker. */
    private val freezeDump = listOf(
        ThreadSample("AWT-EventQueue-0", Thread.State.TIMED_WAITING, listOf(
            f("com.intellij.openapi.progress.util.SuvorovProgress", "dispatchEventsUntilComputationCompletes"),
            f("com.intellij.platform.locking.impl.NestedLocksThreadingSupport\$ComputationState", "acquireWriteIntentPermit"),
            f("com.intellij.ide.IdeEventQueue", "dispatchEvent"),
        )),
        ThreadSample("DefaultDispatcher-worker-3", Thread.State.WAITING, listOf(
            f("com.intellij.core.rwmutex.WriteIntentPermitImpl", "acquireWriteActionPermit"),
            f("com.intellij.openapi.vfs.newvfs.RefreshQueueImpl", "processEventsSuspending"),
        )),
        ThreadSample("DefaultDispatcher-worker-15", Thread.State.TIMED_WAITING, listOf(
            f("java.util.concurrent.ForkJoinTask", "get"),
            f("com.intellij.concurrency.JobLauncherImpl", "invokeConcurrentlyUnderProgress"),
            f("com.intellij.codeInspection.InspectionEngine", "inspectEx", "InspectionEngine.java", 126),
            f("$scriptClass\$code", "inspect", "input.kt", 66),
            f("com.intellij.openapi.application.rw.InternalReadAction", "insideReadAction"),
            f("com.intellij.openapi.application.impl.ApplicationImpl", "tryRunReadAction"),
        )),
        ThreadSample("JobScheduler FJ pool 22/31", Thread.State.RUNNABLE, listOf(
            f("com.intellij.lang.javascript.ecmascript6.TypeScriptTypeEvaluator", "evaluateExportAssignment", "TypeScriptTypeEvaluator.java", 422),
            f("com.intellij.codeInspection.InspectionEngine", "lambda\$inspectElements\$2", "InspectionEngine.java", 363),
            f("com.intellij.openapi.application.impl.ApplicationImpl", "tryRunReadAction"),
        )),
        ThreadSample("ApplicationImpl pooled thread 7", Thread.State.WAITING, listOf(
            f("com.intellij.platform.locking.impl.NestedLocksThreadingSupport\$ComputationState", "acquireReadPermit"),
            f("com.intellij.openapi.application.impl.ApplicationImpl", "runReadAction"),
        )),
    )

    @Test
    fun `the analysis names the waiting writer and the read holders, the script first`() {
        val a = FreezeAnalysis.of(freezeDump)
        assertTrue(a.uiWaitsForLock)
        assertEquals("DefaultDispatcher-worker-3", a.writer)
        assertEquals(listOf("DefaultDispatcher-worker-15", "JobScheduler FJ pool 22/31"), a.holders.map { it.thread })
        assertEquals(scriptClass, a.holders[0].scriptClass)
        assertEquals("input.kt:66", a.holders[0].scriptLine)
        assertEquals("InspectionEngine.inspectEx(InspectionEngine.java:126)", a.holders[0].doing.first())
        assertTrue(a.holders[1].running)
        assertEquals("TypeScriptTypeEvaluator.evaluateExportAssignment(TypeScriptTypeEvaluator.java:422)", a.holders[1].doing.first())
    }

    @Test
    fun `a thread waiting for a read lock does not hold one`() {
        assertFalse(FreezeAnalysis.of(freezeDump).holders.any { it.thread == "ApplicationImpl pooled thread 7" })
    }

    @Test
    fun `a busy UI thread is reported by what it runs`() {
        val a = FreezeAnalysis.of(listOf(ThreadSample("AWT-EventQueue-0", Thread.State.RUNNABLE, listOf(
            f("com.example.SlowAction", "actionPerformed", "SlowAction.kt", 12),
            f("com.intellij.ide.IdeEventQueue", "dispatchEvent"),
        ))))
        assertFalse(a.uiWaitsForLock)
        assertEquals("SlowAction.actionPerformed(SlowAction.kt:12)", a.uiDoing.first())
    }

    @Test
    fun `the script class of an execution matches the class a thread dump shows`() {
        assertEquals(scriptClass, RunningExecutions.scriptClassOf("eid_20260926T224959-231-support-toolkit-s-safe-inspections"))
    }

    @Test
    fun `the notice names the execution, its cancellation, and the running worker`() {
        val freeze = Freeze(1, detectedAtMs = 20_000, thresholdMs = 5_000, reportDir = Path.of("dumps"),
            analysis = FreezeAnalysis.of(freezeDump), cancelled = mapOf("eid-1" to 21_000))
        val text = FreezeMonitor.render(freeze, nowMs = 32_000) { if (it == scriptClass) "eid-1" else null }
        assertTrue(text, text.startsWith("IDE FREEZE: the IDE's UI has not responded for 17 s"))
        assertTrue(text, text.contains("waits for a write action on thread \"DefaultDispatcher-worker-3\""))
        assertTrue(text, text.contains("- steroid_execute_code execution eid-1 at input.kt:66, cancelled by Steroid at"))
        assertTrue(text, text.contains("it has not stopped"))
        assertTrue(text, text.contains("- thread \"JobScheduler FJ pool 22/31\" (running): TypeScriptTypeEvaluator.evaluateExportAssignment"))
        assertTrue(text, text.contains("thread dumps of this freeze: dumps"))

        // The execution has ended by then, so only the id recorded during the freeze names it.
        val ended = FreezeMonitor.render(freeze.copy(durationMs = 41_000, executions = mapOf(scriptClass to "eid-1")), nowMs = 90_000) { null }
        assertTrue(ended, ended.startsWith("IDE FREEZE (ended): the IDE's UI did not respond for 41 s"))
        assertTrue(ended, ended.contains("- steroid_execute_code execution eid-1 at input.kt:66, cancelled by Steroid at"))
        assertFalse(ended, ended.contains("it has not stopped"))
    }

    @Test
    fun `a call a freeze holds up is answered early, and an ended freeze is told once per session`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val monitor = FreezeMonitor(scope)
            val session = Any()
            val ok = ToolCallResult(listOf(ContentItem.Text("ok")))
            assertEquals(ok, monitor.guard(session) { ok })

            monitor.started(Path.of("dumps"))
            val started = System.currentTimeMillis()
            val held = monitor.guard(session) { delay(60_000); ok }
            val waited = System.currentTimeMillis() - started
            assertTrue(held.isError)
            val heldText = (held.content.single() as ContentItem.Text).text
            assertTrue(heldText, heldText.startsWith("IDE FREEZE:") && heldText.endsWith(FreezeMonitor.STILL_RUNNING))
            assertTrue("answered after $waited ms", waited in FreezeMonitor.EARLY_ANSWER_MS..FreezeMonitor.EARLY_ANSWER_MS + 2_000)

            monitor.finished(9_000, null)
            assertNull(monitor.active)
            val after = monitor.guard(session) { ok }
            assertTrue((after.content.first() as ContentItem.Text).text.startsWith("IDE FREEZE (ended)"))
            assertEquals(ok, monitor.guard(session) { ok })
            assertTrue((monitor.guard(Any()) { ok }.content.first() as ContentItem.Text).text.startsWith("IDE FREEZE (ended)"))
        } finally {
            scope.cancel()
        }
    }
}
