/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.testExecParams
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for runHighlightingPasses() in McpScriptContext: the editor's highlighting passes, run
 * without an editor or a window.
 */
class RunHighlightingPassesTest : BasePlatformTestCase() {

    private lateinit var testFilePath: String

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        initInspectionsUntil(testRootDisposable)

        // An unresolved call is an error from the highlighting pass itself, whatever the profile enables.
        val testCode = """
            package test

            class TestClass {
                fun testMethod() {
                    undefinedCall()
                }
            }
        """.trimIndent()

        val basePath = project.basePath ?: error("Project base path is not available")
        val srcVf = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(Paths.get(basePath, "src").toString())
        }
        PsiTestUtil.addSourceRoot(module, srcVf)

        val file = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val filePath = Paths.get(basePath, "src/test/TestClass.kt")
            val parent = VfsUtil.createDirectories(filePath.parent.toString())
            val name = filePath.fileName.toString()
            val child = parent.findChild(name) ?: parent.createChildData(this, name)
            VfsUtil.saveText(child, testCode)
            child
        }
        testFilePath = file.path
    }

    private fun getTextContent(result: ToolCallResult): String =
        result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

    fun testReportsTheErrorsTheEditorShows(): Unit = timeoutRunBlocking(90.seconds) {
        val code = $$"""
            val file = findFile("$$testFilePath") ?: error("File not found")
            val highlights = runHighlightingPasses(file)
            highlights.forEach { println("[${it.severity}] ${it.description}") }
            println("offsets sorted: ${highlights.map { it.startOffset } == highlights.map { it.startOffset }.sorted()}")
        """.trimIndent()

        val result = project.service<ExecutionManager>().executeWithProgress(
            testExecParams(code, taskId = "run-highlighting-passes-test", reason = "test runHighlightingPasses"),
            NoOpProgressReporter
        )
        val text = getTextContent(result)
        println("Test output:\n$text")

        assertFalse("Should execute without error. Output:\n$text", result.isError)
        assertTrue("Should report the unresolved call as an error. Output:\n$text",
            text.lines().any { it.startsWith("[ERROR") && it.contains("undefinedCall") })
        assertTrue("Should return the highlights in document order. Output:\n$text", text.contains("offsets sorted: true"))
    }

    fun testTimeoutReturnsNothingWithAWarning(): Unit = timeoutRunBlocking(90.seconds) {
        val code = $$"""
            import kotlin.time.Duration.Companion.milliseconds

            val file = findFile("$$testFilePath") ?: error("File not found")
            println("count=${runHighlightingPasses(file, timeout = 1.milliseconds).size}")
        """.trimIndent()

        val result = project.service<ExecutionManager>().executeWithProgress(
            testExecParams(code, taskId = "run-highlighting-passes-timeout-test", reason = "test runHighlightingPasses timeout"),
            NoOpProgressReporter
        )
        val text = getTextContent(result)
        println("Test output:\n$text")

        assertFalse("A timeout is not a script failure. Output:\n$text", result.isError)
        assertTrue("Should return no highlights. Output:\n$text", text.contains("count=0"))
        assertTrue("Should warn about the timeout. Output:\n$text", text.contains("did not finish within"))
    }
}
