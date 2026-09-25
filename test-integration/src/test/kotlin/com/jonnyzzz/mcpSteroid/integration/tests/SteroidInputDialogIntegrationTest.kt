/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.ModalMode
import com.jonnyzzz.mcpSteroid.integration.infra.TransientMcpRequestException
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Reproduces the three `steroid_input` / `steroid_take_screenshot` defects from issue #309
 * against a real dialog in a Dockerized IDE (Xvfb — the reporter's environment):
 *
 * 1. `steroid_input` targeting a window while a MODAL dialog is open must answer (success or
 *    error) in bounded time. The executor's EDT hops (`withContext(Dispatchers.EDT)` in
 *    `SwingInputExecutor`) carry no `ModalityState.any()` context element, so the dispatch is
 *    withheld until the modal closes and the MCP call hangs forever.
 * 2. A click on a `JCheckBox` inside a (non-modal, to isolate from 1.) dialog must toggle it.
 *    `SwingInputExecutor.click` dispatches the `MouseEvent` to the deepest component with
 *    window-relative coordinates (no `SwingUtilities.convertPoint` to the target), so the
 *    button model never arms — focus moves, state does not change.
 * 3. `steroid_take_screenshot` with a dialog `window_id` must echo THAT id back.
 *    `captureOnEdt` re-derives the window via `SwingUtilities.getWindowAncestor(component)`,
 *    which for a `Window` returns its OWNER (the IDE frame), so the response reports the
 *    main frame's id while the image shows the dialog.
 *
 * Uses direct MCP HTTP calls (no AI agents). Same session pattern as [DialogKillerIntegrationTest].
 */
class SteroidInputDialogIntegrationTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(this::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(
                lifetime, IntelliJContainerOpts(
                    consoleTitle = "Steroid Input Dialog",
                    // Project content is irrelevant — the empty project skips JDK setup and import.
                    project = IntelliJProject.EmptyProject,
                )
            ).waitForProjectReady()
        }
        val console get() = session.console

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    /** Opens a DialogWrapper carrying a named, initially-unselected JCheckBox; fire-and-forget on EDT. */
    private fun openDialog(title: String, modal: Boolean, visibleGlassPane: Boolean = false) {
        console.writeStep("Opening test dialog '$title' (modal=$modal, visibleGlassPane=$visibleGlassPane)")
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                // Fire-and-forget from a vanilla EDT scope so a modal show() does not block this script
                // (mirrors a real, user-opened dialog; same pattern as DialogKillerIntegrationTest).
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.EDT).launch {
                    val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                        init {
                            title = "$$title"
                            setModal($$modal)
                            init()
                        }

                        override fun createCenterPanel(): javax.swing.JComponent {
                            val panel = javax.swing.JPanel()
                            panel.add(javax.swing.JLabel("steroid_input repro dialog"))
                            val checkBox = javax.swing.JCheckBox("Filter checkbox", false)
                            checkBox.name = "test-checkbox"
                            panel.add(checkBox)
                            return panel
                        }
                    }
                    dialog.show()
                    // Like the Settings dialog: a visible glass pane over the whole window, with no mouse
                    // listeners. Set after show(), so only for a non-modal dialog, whose show() returns.
                    if ($$visibleGlassPane) dialog.rootPane.glassPane.isVisible = true
                }

                kotlinx.coroutines.delay(1500)
                println("dialog opened")
            """.trimIndent(),
            taskId = "open-input-test-dialog",
            reason = "Open test dialog for steroid_input repro",
        ).assertExitCode(0)
    }

    /**
     * Reads the dialog's identity from inside the IDE: its `window_id` (computed exactly like
     * `steroid_list_windows` mints it), the checkbox center in window-relative coordinates
     * (the documented `click:...@x,y` coordinate space), and the checkbox state.
     */
    private fun inspectDialog(title: String): DialogProbe {
        val result = session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil

                val info = withContext(
                    kotlinx.coroutines.Dispatchers.EDT +
                            com.intellij.openapi.application.ModalityState.any().asContextElement()
                ) {
                    val w = java.awt.Window.getWindows()
                        .firstOrNull { it.isVisible && (it as? java.awt.Dialog)?.title == "$$title" }
                        ?: error("dialog '$$title' not found among visible windows")

                    fun findCheckBox(c: java.awt.Component): javax.swing.JCheckBox? {
                        if (c is javax.swing.JCheckBox && c.name == "test-checkbox") return c
                        if (c is java.awt.Container) {
                            c.components.forEach { child -> findCheckBox(child)?.let { return it } }
                        }
                        return null
                    }

                    val cb = findCheckBox(w) ?: error("test-checkbox not found in dialog '$$title'")
                    val center = javax.swing.SwingUtilities.convertPoint(cb, cb.width / 2, cb.height / 2, w)
                    val focusOwner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                    val focusDesc = focusOwner?.let { it.javaClass.simpleName + "/" + it.name } ?: "<null>"
                    "WINDOW_ID: " + WindowIdUtil.compute(w, w) +
                            "\nCHECKBOX_AT: " + center.x + "," + center.y +
                            "\nSELECTED: " + cb.isSelected +
                            "\nFOCUS_OWNER: " + focusDesc +
                            "\nCHECKBOX_HAS_FOCUS: " + (focusOwner === cb) +
                            "\nDEEPEST: " + javax.swing.SwingUtilities.getDeepestComponentAt(w, center.x, center.y)?.javaClass?.simpleName
                }
                println(info)
            """.trimIndent(),
            taskId = "inspect-input-test-dialog",
            reason = "Read dialog window_id / checkbox position for steroid_input repro",
        )
        result.assertExitCode(0)
        console.writeInfo(
            "dialog probe:\n" + result.stdout.lineSequence()
                .filter { line -> listOf("WINDOW_ID:", "CHECKBOX_AT:", "SELECTED:", "FOCUS_OWNER:", "CHECKBOX_HAS_FOCUS:", "DEEPEST:").any { line.trim().startsWith(it) } }
                .joinToString("\n") { it.trim() }
        )
        return DialogProbe(
            windowId = result.extract("WINDOW_ID"),
            checkboxAt = result.extract("CHECKBOX_AT"),
            selected = result.extract("SELECTED").toBooleanStrict(),
            deepest = result.extract("DEEPEST"),
        )
    }

    /** [deepest]: the class `getDeepestComponentAt` returns at the checkbox, the target a naive click would pick. */
    private data class DialogProbe(val windowId: String, val checkboxAt: String, val selected: Boolean, val deepest: String)

    private fun ProcessResult.extract(key: String): String =
        stdout.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("$key: ") }
            ?.removePrefix("$key: ")
            ?: throw AssertionError("marker '$key:' not found in probe output:\n$stdout")

    private fun disposeDialog(title: String) {
        console.writeStep("Disposing test dialog '$title'")
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                withContext(
                    kotlinx.coroutines.Dispatchers.EDT +
                            com.intellij.openapi.application.ModalityState.any().asContextElement()
                ) {
                    java.awt.Window.getWindows()
                        .filter { it.isDisplayable && (it as? java.awt.Dialog)?.title == "$$title" }
                        .forEach { it.dispose() }
                }
                println("disposed")
            """.trimIndent(),
            taskId = "dispose-input-test-dialog",
            reason = "Dispose test dialog after steroid_input repro",
        ).assertExitCode(0)
    }

    private fun closeModalDialogs() {
        console.writeStep("Closing leftover modal dialogs")
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = """
                val closed = closeModalDialogs()
                println("closed ${'$'}closed dialog(s)")
            """.trimIndent(),
            taskId = "close-modal-after-input-repro",
            reason = "Cleanup modal dialog after steroid_input repro",
        ).assertExitCode(0)
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `steroid_input answers in bounded time while a modal dialog is open`() {
        val title = "MCP Steroid Input Modal Hang Repro"
        openDialog(title, modal = true)
        try {
            val probe = inspectDialog(title)
            console.writeStep("Sending click to modal dialog ${probe.windowId} (45s bound)")
            val result = try {
                session.mcpSteroid.mcpInput(
                    windowId = probe.windowId,
                    sequence = "click:Left@${probe.checkboxAt}",
                    timeoutSeconds = 45,
                )
            } catch (e: TransientMcpRequestException) {
                // The transport kills curl after 45s only when the server never answered.
                Assertions.fail<Nothing>(
                    "issue #309 Problem 1 reproduced: steroid_input gave no answer within 45s while a modal " +
                            "dialog was open — SwingInputExecutor's Dispatchers.EDT hops carry no " +
                            "ModalityState.any(), so the dispatch is withheld until the modal closes: ${e.message}"
                )
            }
            // Answering at all (success or a fast diagnostic error) is the contract under test here.
            console.writeInfo("steroid_input answered: exit=${result.exitCode}\n${result.stdout}")
            console.writeSuccess("steroid_input answered in bounded time under a modal dialog")
        } finally {
            closeModalDialogs()
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `steroid_input click toggles a checkbox in a non-modal dialog`() {
        val title = "MCP Steroid Input Checkbox Repro"
        openDialog(title, modal = false)
        try {
            val probe = inspectDialog(title)
            Assertions.assertFalse(probe.selected, "checkbox must start unselected")

            console.writeStep("Clicking checkbox at window-relative ${probe.checkboxAt} in ${probe.windowId}")
            session.mcpSteroid.mcpInput(
                windowId = probe.windowId,
                sequence = "click:Left@${probe.checkboxAt}",
                timeoutSeconds = 60,
            ).assertExitCode(0)

            val after = inspectDialog(title)
            Assertions.assertTrue(
                after.selected,
                "issue #309 Problem 2 reproduced: click:Left@${probe.checkboxAt} did not toggle the checkbox — " +
                        "SwingInputExecutor.click dispatches window-relative coordinates to the target component " +
                        "without SwingUtilities.convertPoint, so the button model never arms",
            )
            console.writeSuccess("Checkbox toggled by steroid_input click")

            // The reporter's second strategy: focus the checkbox (done by the click above), then
            // press SPACE. Key events dispatched to the root window are never retargeted to the
            // focus owner, so this only works when the executor routes keys to the focus owner.
            console.writeStep("Pressing SPACE to toggle the focused checkbox back")
            session.mcpSteroid.mcpInput(
                windowId = probe.windowId,
                sequence = "press:SPACE",
                timeoutSeconds = 60,
            ).assertExitCode(0)

            val afterSpace = inspectDialog(title)
            Assertions.assertFalse(
                afterSpace.selected,
                "issue #309 Problem 2 reproduced: press:SPACE on the focused checkbox did not toggle it — " +
                        "SwingInputExecutor.pressKey dispatches the KeyEvent to the root window, which is never " +
                        "retargeted to the focus owner's WHEN_FOCUSED bindings",
            )
            console.writeSuccess("SPACE toggled the focused checkbox back")
        } finally {
            disposeDialog(title)
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `steroid_input click reaches a checkbox under a visible glass pane`() {
        val title = "MCP Steroid Input Glass Pane Repro"
        openDialog(title, modal = false, visibleGlassPane = true)
        try {
            val probe = inspectDialog(title)
            Assertions.assertFalse(probe.selected, "checkbox must start unselected")
            // Precondition: without it this test would pass on the old target search too.
            Assertions.assertEquals(
                "IdeGlassPaneImpl", probe.deepest,
                "the visible glass pane must be the deepest component at the checkbox",
            )

            session.mcpSteroid.mcpInput(
                windowId = probe.windowId,
                sequence = "click:Left@${probe.checkboxAt}",
                timeoutSeconds = 60,
            ).assertExitCode(0)

            Assertions.assertTrue(
                inspectDialog(title).selected,
                "the click went to the glass pane: SwingInputExecutor.click must let AWT pick the target, " +
                        "as for a real click, instead of taking the deepest visible component",
            )
            console.writeSuccess("Checkbox under a visible glass pane toggled by steroid_input click")
        } finally {
            disposeDialog(title)
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `steroid_input click runs an unselected list popup item`() {
        console.writeStep("Opening a list popup with Alpha, Beta, Gamma")
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                System.clearProperty("steroid.test.popupChosen")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.EDT).launch {
                    val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<String>(
                        "Steroid popup repro", listOf("Alpha", "Beta", "Gamma")
                    ) {
                        override fun onChosen(selectedValue: String?, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                            System.setProperty("steroid.test.popupChosen", selectedValue ?: "<null>")
                            return FINAL_CHOICE
                        }
                    }
                    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step)
                        .showCenteredInCurrentWindow(project)
                }
                kotlinx.coroutines.delay(1500)
                println("popup opened")
            """.trimIndent(),
            taskId = "open-input-test-popup",
            reason = "Open a list popup for the steroid_input popup item repro",
        ).assertExitCode(0)

        try {
            val probe = session.mcpSteroid.mcpExecuteCode(
                modal = ModalMode.UNLEASHED,
                code = $$"""
                    import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil

                    val info = withContext(
                        kotlinx.coroutines.Dispatchers.EDT +
                                com.intellij.openapi.application.ModalityState.any().asContextElement()
                    ) {
                        val list = java.awt.Window.getWindows().filter { it.isShowing && it is javax.swing.RootPaneContainer }
                            .flatMap { com.intellij.util.ui.UIUtil.findComponentsOfType((it as javax.swing.RootPaneContainer).rootPane, javax.swing.JList::class.java) }
                            .firstOrNull { l -> (0 until l.model.size).any { l.model.getElementAt(it) == "Gamma" } }
                            ?: error("the test popup list is not showing")
                        val window = javax.swing.SwingUtilities.getWindowAncestor(list)
                        // A popup that fits inside the frame is lightweight: steroid_list_windows lists the frame then.
                        val frame = com.intellij.openapi.wm.WindowManager.getInstance().allProjectFrames
                            .firstOrNull { javax.swing.SwingUtilities.getWindowAncestor(it.component) === window }
                        val root: java.awt.Component = frame?.component ?: window
                        val b = list.getCellBounds(2, 2)
                        val p = javax.swing.SwingUtilities.convertPoint(list, b.x + b.width / 2, b.y + b.height / 2, root)
                        "POPUP_WINDOW_ID: " + WindowIdUtil.compute(window, root) +
                                "\nGAMMA_AT: " + p.x + "," + p.y +
                                "\nSELECTED_INDEX: " + list.selectedIndex
                    }
                    println(info)
                """.trimIndent(),
                taskId = "inspect-input-test-popup",
                reason = "Read the popup window_id and the Gamma item position",
            )
            probe.assertExitCode(0)
            Assertions.assertNotEquals("2", probe.extract("SELECTED_INDEX"), "Gamma must start unselected")

            session.mcpSteroid.mcpInput(
                windowId = probe.extract("POPUP_WINDOW_ID"),
                sequence = "click:Left@${probe.extract("GAMMA_AT")}",
                timeoutSeconds = 60,
            ).assertExitCode(0)

            val chosen = session.mcpSteroid.mcpExecuteCode(
                modal = ModalMode.UNLEASHED,
                code = """println("CHOSEN: " + System.getProperty("steroid.test.popupChosen"))""",
                taskId = "read-input-test-popup-choice",
                reason = "Read which popup item ran",
            )
            chosen.assertExitCode(0)
            Assertions.assertEquals(
                "Gamma", chosen.extract("CHOSEN"),
                "the popup ignored the click: a list popup selects its row on MOUSE_MOVED and drops a press " +
                        "on any other row, so SwingInputExecutor.click must move the mouse before pressing",
            )
            console.writeSuccess("steroid_input click ran the unselected popup item")
        } finally {
            session.mcpSteroid.mcpExecuteCode(
                modal = ModalMode.UNLEASHED,
                code = $$"""
                    withContext(
                        kotlinx.coroutines.Dispatchers.EDT +
                                com.intellij.openapi.application.ModalityState.any().asContextElement()
                    ) {
                        java.awt.Window.getWindows().filter { it.isShowing && it is javax.swing.RootPaneContainer }
                            .flatMap { com.intellij.util.ui.UIUtil.findComponentsOfType((it as javax.swing.RootPaneContainer).rootPane, javax.swing.JList::class.java) }
                            .filter { l -> (0 until l.model.size).any { l.model.getElementAt(it) == "Gamma" } }
                            .forEach { com.intellij.openapi.ui.popup.util.PopupUtil.getPopupContainerFor(it)?.cancel() }
                    }
                    println("popup closed")
                """.trimIndent(),
                taskId = "close-input-test-popup",
                reason = "Close the test popup if it is still showing",
            ).assertExitCode(0)
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `steroid_take_screenshot echoes the requested dialog window_id`() {
        val title = "MCP Steroid Screenshot WindowId Repro"
        openDialog(title, modal = false)
        try {
            val probe = inspectDialog(title)

            // The id we computed in-process must be discoverable the way agents discover it.
            val listed = session.mcpSteroid.mcpListWindows()
            Assertions.assertTrue(
                listed.any { it.windowId == probe.windowId },
                "dialog ${probe.windowId} must be listed by steroid_list_windows; got: $listed",
            )

            console.writeStep("Taking screenshot of dialog ${probe.windowId}")
            val screenshot = session.mcpSteroid.mcpTakeScreenshot(windowId = probe.windowId)
            screenshot.assertExitCode(0)

            val echoed = screenshot.extract("window_id")
            Assertions.assertEquals(
                probe.windowId, echoed,
                "issue #309 Problem 3 reproduced: steroid_take_screenshot echoed window_id $echoed for the " +
                        "requested dialog ${probe.windowId} — captureOnEdt resolves the window via " +
                        "SwingUtilities.getWindowAncestor(component), which for a Window returns its OWNER frame",
            )
            console.writeSuccess("Screenshot echoed the requested dialog window_id")
        } finally {
            disposeDialog(title)
        }
    }
}
