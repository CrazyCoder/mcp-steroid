/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.execution.HealthyStubInspection
import com.jonnyzzz.mcpSteroid.execution.initInspectionsUntil
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

class BatchInspectionTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        // A run limited to some inspections builds a profile.
        initInspectionsUntil(testRootDisposable)
    }

    /**
     * A text file on disk under a source root, as the other inspection tests make theirs: they leave that source root on
     * the shared test project, and a file of the in-memory file system is then outside the project's content.
     */
    private fun sourceFile(): VirtualFile {
        val basePath = project.basePath ?: error("Project base path is not available")
        val src = WriteAction.computeAndWait<VirtualFile, RuntimeException> { VfsUtil.createDirectories(Paths.get(basePath, "src").toString()) }
        PsiTestUtil.addSourceRoot(module, src)
        return WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val file = src.findChild("A.txt") ?: src.createChildData(this, "A.txt")
            VfsUtil.saveText(file, "hello world")
            file
        }
    }

    fun `test a run sees an inspection enabled after an earlier run`() {
        val file = sourceFile()
        val batch = BatchInspection(project)
        timeoutRunBlocking(60.seconds) {
            assertEquals(emptyList<String>(), batch.run(batch.scopeOf(listOf(file))).problems.map { it.shortName })
        }
        myFixture.enableInspections(HealthyStubInspection())
        timeoutRunBlocking(60.seconds) {
            val result = batch.run(batch.scopeOf(listOf(file)))
            assertTrue(result.finished)
            assertEquals(listOf("HealthyStubInspection"), result.problems.map { it.shortName })
        }
    }

    fun `test a run limited to named inspections reports only them`() {
        myFixture.enableInspections(HealthyStubInspection(), OtherStubInspection())
        val file = sourceFile()
        val batch = BatchInspection(project)
        timeoutRunBlocking(60.seconds) {
            assertEquals(setOf("HealthyStubInspection", "OtherStubInspection"), batch.run(batch.scopeOf(listOf(file))).problems.map { it.shortName }.toSet())
            assertEquals(listOf("OtherStubInspection"), batch.run(batch.scopeOf(listOf(file)), listOf("OtherStubInspection")).problems.map { it.shortName })
        }
    }

    fun `test an unknown inspection name fails with similar names`() {
        myFixture.enableInspections(HealthyStubInspection())
        val file = sourceFile()
        val batch = BatchInspection(project)
        timeoutRunBlocking(60.seconds) {
            val e = runCatching { batch.run(batch.scopeOf(listOf(file)), listOf("HealthyStub")) }.exceptionOrNull()
            assertTrue("$e", e is UnknownInspectionsException)
            e as UnknownInspectionsException
            assertEquals(listOf("HealthyStub"), e.unknown)
            assertTrue(e.similar.toString(), "HealthyStubInspection" in e.similar)
        }
    }

    fun `test a run past its timeout is cancelled and says it did not finish`() {
        myFixture.enableInspections(SlowStubInspection())
        val file = sourceFile()
        val batch = BatchInspection(project)
        timeoutRunBlocking(60.seconds) {
            val started = System.currentTimeMillis()
            val result = batch.run(batch.scopeOf(listOf(file)), timeout = 2.seconds)
            val took = System.currentTimeMillis() - started
            assertFalse(result.finished)
            assertTrue("took $took ms", took < 15_000)
        }
    }
}

/** Reports one problem, under another name than [HealthyStubInspection]. */
class OtherStubInspection : LocalInspectionTool() {
    override fun getShortName(): String = "OtherStubInspection"
    override fun getDisplayName(): String = "Other stub inspection (test)"
    override fun getGroupDisplayName(): String = "MCP Steroid tests"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            private var reported = false
            override fun visitElement(element: PsiElement) {
                if (!reported) {
                    reported = true
                    holder.registerProblem(element, "other stub finding")
                }
            }
        }
}

/** Runs for a minute, checking for cancellation, as a slow but well-behaved inspection does. */
class SlowStubInspection : LocalInspectionTool() {
    override fun getShortName(): String = "SlowStubInspection"
    override fun getDisplayName(): String = "Slow stub inspection (test)"
    override fun getGroupDisplayName(): String = "MCP Steroid tests"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val until = System.currentTimeMillis() + 60_000
                while (System.currentTimeMillis() < until) {
                    ProgressManager.checkCanceled()
                    Thread.sleep(20)
                }
            }
        }
}
