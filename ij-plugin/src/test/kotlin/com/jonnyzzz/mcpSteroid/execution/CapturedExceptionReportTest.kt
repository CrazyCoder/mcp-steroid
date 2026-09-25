/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class CapturedExceptionReportTest {
    private val logFile = Path.of("runs", "eid-1", "ide-exceptions.txt")

    private fun captured(message: String, frames: List<String>, pluginId: String? = "tanvd.grazi") = CapturedIdeException(
        timestamp = Instant.parse("2026-09-25T21:47:14Z"),
        throwable = RuntimeException(message),
        message = message,
        stacktrace = "java.lang.Throwable: $message\n" + frames.joinToString("") { "\tat $it\n" },
        pluginId = pluginId,
    )

    private val frames = (1..40).map { "com.example.Frame$it.run(Frame$it.kt:$it)" }

    private fun CapturedExceptionReport.summary(ex: CapturedIdeException): String = add(ex).summary!!

    @Test
    fun `the summary keeps the message, the plugin and the top frames, and names the log file`() {
        val summary = CapturedExceptionReport(logFile).summary(captured("ClientId is already set", frames))

        assertTrue(summary, summary.contains("IDE exception #1"))
        assertTrue(summary, summary.contains("tanvd.grazi"))
        assertTrue(summary, summary.contains("ClientId is already set"))
        assertTrue(summary, summary.contains("at com.example.Frame1.run(Frame1.kt:1)"))
        assertTrue(summary, summary.contains("at com.example.Frame3.run(Frame3.kt:3)"))
        assertFalse(summary, summary.contains("Frame4.run"))
        assertTrue(summary, summary.contains(logFile.toString()))
    }

    @Test
    fun `a long or multi-line message is cut in the summary but kept whole in the log`() {
        val longMessage = "x".repeat(2_000) + "\nsecond line"
        val entry = CapturedExceptionReport(logFile).add(captured(longMessage, frames))
        val summary = entry.summary!!

        assertTrue("summary is ${summary.length} chars", summary.length < 1_000)
        assertFalse(summary, summary.contains("second line"))
        assertTrue(entry.logText.contains(longMessage))
    }

    @Test
    fun `a repeat of the same exception is one line that points at the first`() {
        val report = CapturedExceptionReport(logFile)
        report.add(captured("ClientId is already set", frames))
        val repeat = report.summary(captured("ClientId is already set", frames))

        assertEquals(1, repeat.trim().lines().size)
        assertTrue(repeat, repeat.contains("#2"))
        assertTrue(repeat, repeat.contains("#1"))
    }

    @Test
    fun `a different exception gets its own summary`() {
        val report = CapturedExceptionReport(logFile)
        report.add(captured("ClientId is already set", frames))
        val other = report.summary(captured("Read access is allowed from inside read-action only", frames.drop(10)))

        assertTrue(other, other.contains("Read access is allowed"))
        assertTrue(other, other.contains("at com.example.Frame11.run"))
    }

    @Test
    fun `after a few summaries, further exceptions take one line each`() {
        val report = CapturedExceptionReport(logFile)
        val summaries = (1..20).map { report.summary(captured("distinct error $it", frames)) }

        assertTrue(summaries.first().trim().lines().size > 1)
        val last = summaries.last()
        assertEquals(1, last.trim().lines().size)
        assertTrue(last, last.contains("#20"))
        assertTrue(last, last.contains("distinct error 20"))
    }

    @Test
    fun `past the output cap exceptions go to the log only, after one notice`() {
        val report = CapturedExceptionReport(logFile)
        val entries = (1..200).map { report.add(captured("flood error $it", frames)) }

        val printed = entries.mapNotNull { it.summary }
        assertTrue("printed ${printed.size} of 200", printed.size < 100)
        assertTrue(printed.last(), printed.last().contains(logFile.toString()))
        assertEquals(null, entries.last().summary)
        assertTrue(entries.last().logText.contains("flood error 200"))
    }

    @Test
    fun `the log text keeps every full stack trace`() {
        val report = CapturedExceptionReport(logFile)
        val text = listOf(
            captured("first", frames),
            captured("first", frames),
            captured("second", frames.reversed()),
        ).joinToString("") { report.add(it).logText }
        assertTrue(text.contains("at com.example.Frame40.run(Frame40.kt:40)"))
        assertEquals(3, Regex("=== IDE exception #\\d+").findAll(text).count())
        assertTrue(text.contains("tanvd.grazi"))
    }
}
