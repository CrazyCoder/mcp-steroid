/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

@Timeout(120, unit = TimeUnit.SECONDS)
class ProcessAiAgentCliRunnerTest {
    @TempDir
    lateinit var tempDir: Path

    private fun awaitTrue(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `exit code and merged output pass through`() {
        val result = ProcessAiAgentCliRunner().run(agentCliFixtureInvocation("echo", "3"))
        assertEquals(3, result.exitCode)
        assertTrue(result.output.contains("fixture-stdout-line")) { result.output }
        assertTrue(result.output.contains("fixture-stderr-line"), "stderr must stay merged into the output")
    }

    @Test
    fun `zero exit code passes through`() {
        assertEquals(0, ProcessAiAgentCliRunner().run(agentCliFixtureInvocation("echo", "0")).exitCode)
    }

    @Test
    fun `stdin is closed - a stdin-reading agent CLI sees immediate eof instead of hanging`() {
        val result = ProcessAiAgentCliRunner(timeout = 30.seconds).run(agentCliFixtureInvocation("stdin"))
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("stdin-eof:0")) { result.output }
    }

    @Test
    fun `a hung agent CLI is killed at the timeout and reported loudly`() {
        val pidFile = tempDir.resolve("fixture.pid")
        val ex = assertThrows(IllegalStateException::class.java) {
            ProcessAiAgentCliRunner(timeout = 3.seconds).run(agentCliFixtureInvocation("sleep", pidFile.toString()))
        }
        assertTrue(ex.message!!.contains("timed out")) { ex.message }

        assertTrue(pidFile.exists(), "the fixture must have started and reported its pid")
        val pid = pidFile.readText().trim().toLong()
        awaitTrue("hung agent CLI process $pid to be killed") {
            ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
        }
    }

    @Test
    fun `no temp output files are left behind`() {
        val outputDir = Files.createDirectory(tempDir.resolve("outputs"))
        ProcessAiAgentCliRunner(outputDir = outputDir).run(agentCliFixtureInvocation("echo", "0"))
        assertThrows(IllegalStateException::class.java) {
            ProcessAiAgentCliRunner(timeout = 2.seconds, outputDir = outputDir)
                .run(agentCliFixtureInvocation("sleep", tempDir.resolve("p.pid").toString()))
        }
        assertEquals(emptyList<String>(), outputFiles(outputDir), "runner must clean up its temp output files on success AND on timeout")
    }

    @Test
    fun `an interrupted wait kills the agent CLI, deletes its output and keeps the interrupt`() {
        val outputDir = Files.createDirectory(tempDir.resolve("outputs"))
        val pidFile = tempDir.resolve("interrupted.pid")
        var thrown: Throwable? = null
        var interruptedAfter = false
        val worker = Thread {
            try {
                ProcessAiAgentCliRunner(timeout = 60.seconds, outputDir = outputDir)
                    .run(agentCliFixtureInvocation("sleep", pidFile.toString()))
            } catch (e: Throwable) {
                thrown = e
                interruptedAfter = Thread.currentThread().isInterrupted
            }
        }
        worker.start()
        awaitTrue("the fixture to report its pid") { pidFile.exists() && pidFile.readText().isNotBlank() }
        val pid = pidFile.readText().trim().toLong()
        worker.interrupt()
        worker.join(30_000)

        assertFalse(worker.isAlive, "the runner must return after the interrupt")
        assertTrue(thrown is InterruptedException) { "expected InterruptedException, got $thrown" }
        assertTrue(interruptedAfter, "the runner must re-set the interrupt flag")
        awaitTrue("interrupted agent CLI process $pid to be killed") {
            ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
        }
        assertEquals(emptyList<String>(), outputFiles(outputDir), "the output file must be deleted after an interrupt")
    }

    private fun outputFiles(dir: Path): List<String> =
        Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
}
