/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiRestoreTest {
    private fun set(key: String, value: String) = UiRestore.step("set", "registry" to key, "value" to value)

    @Test
    fun `restores run last change first, and a state restores to its first value`() {
        val journal = UiRestore.Journal()
        journal.add(listOf(set("a", "1")))
        journal.add(listOf(set("b", "x")))
        // A second change of a: the first restore, which runs last, puts back the value a had before the run.
        journal.add(listOf(set("a", "2")))
        journal.add(emptyList())
        assertEquals(listOf(set("b", "x"), set("a", "1")), journal.steps())
    }

    @Test
    fun `the steps of one change keep their order`() {
        val journal = UiRestore.Journal()
        val size = UiRestore.step("toolwindow", "id" to "Project", "width" to 250)
        val hide = UiRestore.step("toolwindow", "id" to "Project", "hide" to true)
        journal.add(listOf(size, hide))
        assertEquals(listOf(size, hide), journal.steps())
    }

    @Test
    fun `a step's restore of a state restored before leaves its other restores in`() {
        val journal = UiRestore.Journal()
        val size = UiRestore.step("toolwindow", "id" to "Project", "width" to 250)
        journal.add(listOf(size))
        // Making room widened the tool window and filled the screen: the width is restored already, the window is not.
        val window = UiRestore.step("window", "class" to "IdeFrameImpl", "width" to 1400, "height" to 900)
        journal.add(listOf(UiRestore.step("toolwindow", "id" to "Project", "width" to 80), window))
        assertEquals(listOf(window, size), journal.steps())
    }

    @Test
    fun `consecutive restores of one state stay together`() {
        val journal = UiRestore.Journal()
        val level = UiRestore.step("set", "inspection" to "X", "value" to "WARNING")
        val off = UiRestore.step("set", "inspection" to "X", "value" to "off")
        journal.add(listOf(level, off))
        journal.add(listOf(UiRestore.step("set", "inspection" to "X", "value" to "ERROR")))
        assertEquals(listOf(level, off), journal.steps())
    }

    @Test
    fun `a key names the state, not the value, and check and uncheck set one state`() {
        assertEquals(UiRestore.key(set("a", "1")), UiRestore.key(set("a", "2")))
        assertNotEquals(UiRestore.key(set("a", "1")), UiRestore.key(set("b", "1")))
        assertEquals(
            UiRestore.key(UiRestore.step("check", "path" to "View > Status Bar")),
            UiRestore.key(UiRestore.step("uncheck", "path" to "View > Status Bar")),
        )
        // A size and a visibility of one tool window are two states.
        assertNotEquals(
            UiRestore.key(UiRestore.step("toolwindow", "id" to "Project", "width" to 250)),
            UiRestore.key(UiRestore.step("toolwindow", "id" to "Project", "hide" to true)),
        )
    }

    @Test
    fun `a restore runs on its step's side, and the line carries it to a JetBrains Client`() {
        val onBackend = UiRestore.onSide(listOf(set("a", "1")), "backend").single()
        assertEquals(JsonPrimitive("backend"), onBackend["side"])
        assertEquals(listOf(set("a", "1")), UiRestore.onSide(listOf(set("a", "1")), null))
        val line = UiRestore.line(listOf(onBackend))
        assertTrue(line.startsWith(UiRestore.LINE))
        assertEquals(listOf(onBackend), UiRestore.parse(line))
        assertEquals(emptyList<Any>(), UiRestore.parse("step 1 set: done"))
    }

    @Test
    fun `restore steps parse as steps`() {
        val steps = listOf(
            set("a", "1"),
            UiRestore.step("write", "file" to "src/A.java", "delete" to true),
            UiRestore.step("check", "path" to "View > Appearance > Status Bar"),
            UiRestore.step("menu", "mode" to "merged"),
            UiRestore.step("window", "class" to "IdeFrameImpl", "width" to 1400, "height" to 900),
            UiRestore.step("toolwindow", "id" to "Project", "hide" to false),
        )
        assertEquals(steps.size, UiSteps.parse(kotlinx.serialization.json.JsonArray(steps)).size)
    }

    @Test
    fun `a forwarded step's report carries its restores and leaves the line out of its text`() {
        val label = "step 1 set"
        val restore = set("a", "1")
        val text = "execution_id: x\n$label: registry a: 1 -> 2\n${UiRestore.line(listOf(restore))}\nPASSED: all 1 step(s)"
        val report = UiForwardedStep.parse(text, label, isError = false)
        assertEquals("registry a: 1 -> 2", report.text)
        assertEquals(listOf(restore), report.undo)
    }
}
