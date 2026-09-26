/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.ModalMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Drives a real dialog in a Dockerized IDE through `steroid_ui`: a snapshot with refs, fill, check, select in a
 * list and a combo box, a click that opens a modal dialog, and the failure messages. Direct MCP HTTP calls.
 */
class SteroidUiIntegrationTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(this::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(
                lifetime, IntelliJContainerOpts(
                    consoleTitle = "Steroid UI",
                    project = IntelliJProject.EmptyProject,
                )
            ).waitForProjectReady()
        }
        val console get() = session.console

        private const val TITLE = "Steroid UI Test"

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    /**
     * Opens a non-modal DialogWrapper with a text field, a checkbox, a list, a combo box and a button that opens a
     * modal message dialog. OK records the values in the system property `steroid.ui.test.result`.
     */
    private fun openDialog() {
        console.writeStep("Opening the steroid_ui test dialog")
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                System.clearProperty("steroid.ui.test.result")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.EDT).launch {
                    val field = javax.swing.JTextField("", 12).apply { accessibleContext.accessibleName = "Name" }
                    val box = javax.swing.JCheckBox("Enable feature", false)
                    val list = com.intellij.ui.components.JBList(listOf("Alpha", "Beta", "Gamma")).apply { accessibleContext.accessibleName = "Letters" }
                    val combo = com.intellij.openapi.ui.ComboBox(arrayOf("Red", "Green", "Blue")).apply { accessibleContext.accessibleName = "Color" }
                    val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                        init {
                            title = "$$TITLE"
                            isModal = false
                            init()
                        }

                        override fun createCenterPanel(): javax.swing.JComponent {
                            val panel = javax.swing.JPanel(java.awt.GridLayout(0, 1))
                            panel.add(field)
                            panel.add(box)
                            panel.add(list)
                            panel.add(combo)
                            panel.add(javax.swing.JButton("Ask").apply {
                                addActionListener { com.intellij.openapi.ui.Messages.showInfoMessage(project, "Question body", "Steroid UI Question") }
                            })
                            return panel
                        }

                        override fun doOKAction() {
                            System.setProperty("steroid.ui.test.result", "${field.text}|${box.isSelected}|${list.selectedValue}|${combo.selectedItem}")
                            super.doOKAction()
                        }
                    }
                    dialog.show()
                }
                kotlinx.coroutines.delay(1500)
                println("dialog opened")
            """.trimIndent(),
            taskId = "open-ui-test-dialog",
            reason = "Open the steroid_ui test dialog",
        ).assertExitCode(0)
    }

    private fun result(): String {
        val run = session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = "println(\"RESULT=\" + System.getProperty(\"steroid.ui.test.result\"))",
            taskId = "read-ui-test-result",
            reason = "Read the values the test dialog recorded",
        )
        run.assertExitCode(0)
        return run.stdout.lineSequence().first { it.startsWith("RESULT=") }.removePrefix("RESULT=")
    }

    @AfterEach
    fun closeDialogs() {
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                withContext(kotlinx.coroutines.Dispatchers.EDT + com.intellij.openapi.application.ModalityState.any().asContextElement()) {
                    java.awt.Window.getWindows().filter { it.isShowing && it is java.awt.Dialog }.forEach { it.dispose() }
                }
                println("closed")
            """.trimIndent(),
            taskId = "close-ui-test-dialogs",
            reason = "Close the test dialogs",
        )
    }

    private fun ProcessResult.assertContains(text: String) =
        Assertions.assertTrue(stdout.contains(text), "expected '$text' in:\n$stdout")

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `a snapshot lists the dialog's controls with refs`() {
        openDialog()
        val run = session.mcpSteroid.mcpUi()
        run.assertExitCode(0)
        run.assertContains("\"$TITLE\" (dialog)")
        Assertions.assertTrue(Regex("""JCheckBox "Enable feature" \[ref=e\d+]""").containsMatchIn(run.stdout), run.stdout)
        run.assertContains("JButton \"OK\"")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `fill, check, select and OK apply the values`() {
        openDialog()
        val run = session.mcpSteroid.mcpUi(
            steps = """[
                {"action":"fill","name":"Name","text":"steroid"},
                {"action":"check","name":"Enable feature"},
                {"action":"select","name":"Letters","row":"Beta"},
                {"action":"select","name":"Color","row":"Blue"},
                {"action":"click","name":"OK"}
            ]""",
        )
        run.assertExitCode(0)
        run.assertContains("closed dialog")
        Assertions.assertEquals("steroid|true|Beta|Blue", result())
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `a click that opens a modal dialog returns and names it`() {
        openDialog()
        val started = System.nanoTime()
        val run = session.mcpSteroid.mcpUi(steps = """[{"action":"click","name":"Ask"}]""", snapshot = "none")
        val ms = (System.nanoTime() - started) / 1_000_000
        run.assertExitCode(0)
        run.assertContains("opened modal dialog")
        Assertions.assertTrue(ms < 15_000, "the click took $ms ms")
        session.mcpSteroid.mcpUi(steps = """[{"action":"click","name":"OK"}]""", snapshot = "none").assertExitCode(0)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `a miss names the nearest control and a stale ref is reported as stale`() {
        openDialog()
        val miss = session.mcpSteroid.mcpUi(steps = """[{"action":"click","name":"Enable featur","timeout_ms":300}]""")
        Assertions.assertEquals(1, miss.exitCode, miss.stdout)
        miss.assertContains("nearest: JCheckBox \"Enable feature\"")

        val ref = Regex("""JButton "OK" \[ref=(e\d+)]""").find(session.mcpSteroid.mcpUi().stdout)!!.groupValues[1]
        session.mcpSteroid.mcpUi(steps = """[{"action":"close","name":"Enable feature"}]""", snapshot = "none").assertExitCode(0)
        val stale = session.mcpSteroid.mcpUi(steps = """[{"action":"click","ref":"$ref"}]""")
        Assertions.assertEquals(1, stale.exitCode, stale.stdout)
        stale.assertContains("ref $ref is stale")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `modal=dialog runs EDT work and a write action while a modal dialog stays open`() {
        session.mcpSteroid.mcpUi(steps = null) // warms the session up before the dialog opens
        openDialog()
        session.mcpSteroid.mcpUi(steps = """[{"action":"click","name":"Ask"}]""", snapshot = "none").assertExitCode(0)

        val gate = session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.NON_MODAL,
            code = "println(\"ran\")",
            taskId = "modal-dialog-gate",
            reason = "non_modal must refuse to run while a modal dialog is open",
            timeout = 30,
        )
        Assertions.assertEquals(1, gate.exitCode, gate.stdout)
        Assertions.assertTrue(gate.stdout.contains("modal=dialog"), gate.stdout)

        val run = session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.DIALOG,
            code = $$"""
                val doc = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument("before")
                withContext(kotlinx.coroutines.Dispatchers.EDT) { println("edt ran") }
                writeAction { doc.setText("after") }
                val stillOpen = withContext(kotlinx.coroutines.Dispatchers.EDT + com.intellij.openapi.application.ModalityState.any().asContextElement()) {
                    java.awt.Window.getWindows().any { it.isShowing && it is java.awt.Dialog && it.isModal }
                }
                println("text=${doc.text} modalStillOpen=$stillOpen")
            """.trimIndent(),
            taskId = "modal-dialog-run",
            reason = "modal=dialog must run EDT work and a write action under the open dialog",
            timeout = 30,
        )
        run.assertExitCode(0)
        run.assertContains("edt ran")
        run.assertContains("text=after modalStillOpen=true")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `ui helpers drive the dialog from a script, and ui open returns a modal dialog`() {
        openDialog()
        val run = session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                println(ui.fill(ui.name("Name"), "script"))
                println(ui.check(ui.name("Enable feature")))
                val question = ui.open {
                    com.intellij.openapi.ui.Messages.showInfoMessage(project, "Opened by ui.open", "Steroid UI Open")
                }
                println("opened modal=" + ((question as? java.awt.Dialog)?.isModal == true))
                println(ui.click(ui.name("OK") and ui.cls("JButton")))
                println(ui.click(ui.name("OK")))
            """.trimIndent(),
            taskId = "ui-helpers",
            reason = "Drive the test dialog through the ui helpers",
            timeout = 60,
        )
        run.assertExitCode(0)
        run.assertContains("opened modal=true")
        Assertions.assertEquals("script|true|null|Red", result())
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `a traced run writes pictures, snapshots and an index per step`() {
        openDialog()
        val run = session.mcpSteroid.mcpUi(
            steps = """[{"action":"check","name":"Enable feature"},{"action":"fill","name":"Name","text":"traced"}]""",
            trace = true,
        )
        run.assertExitCode(0)
        val index = Regex("""trace: (\S+trace\.md)""").find(run.stdout)!!.groupValues[1]
        val listing = session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                val dir = java.nio.file.Path.of("$$index").parent
                println(java.nio.file.Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }.joinToString(","))
            """.trimIndent(),
            taskId = "list-trace",
            reason = "List the trace files",
        )
        listing.assertExitCode(0)
        listing.assertContains("01-after.png,01-before.png,01-snapshot.txt,02-after.png,02-before.png,02-snapshot.txt,trace.jsonl,trace.md")
    }
}
