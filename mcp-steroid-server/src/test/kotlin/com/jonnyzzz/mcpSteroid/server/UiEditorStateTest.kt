/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiEditorStateTest {
    private val session = UiEditorSide(
        UiEditorState.sessionLabel("5CE57E53"),
        listOf(UiOpenFile("xhtml-demo/page.xml", 2), UiOpenFile("docs/headings.md", 1), UiOpenFile("envs/b/.env", 1)),
        selected = "docs/headings.md",
        client = true,
    )

    @Test
    fun `the text form reads back as the sides it was made from`() {
        val sides = listOf(UiEditorSide("the backend's own editors", emptyList(), null), session)
        val text = UiEditorState.render(sides)
        assertEquals(
            "editors of the backend's own editors: none\n" +
                "editors of JetBrains Client session 5CE57E53: xhtml-demo/page.xml (2 editors) | docs/headings.md | envs/b/.env; selected docs/headings.md",
            text,
        )
        assertEquals(sides, UiEditorState.parse("execution_id: e1\n$text\nrecorded: 1 step(s)"))
    }

    @Test
    fun `a Client that shows what the backend records has no mismatch`() {
        val client = UiEditorSide("the JetBrains Client", listOf(UiOpenFile("page.xml", 2), UiOpenFile("headings.md", 1), UiOpenFile(".env", 1)), "headings.md")
        assertEquals(emptyList<String>(), UiEditorState.mismatches(client, listOf(session)))
    }

    @Test
    fun `extra backend editors, files only one side counts, and several sessions are told apart`() {
        val client = UiEditorSide("the JetBrains Client", listOf(UiOpenFile("page.xml", 1), UiOpenFile("notes.txt", 1)), "page.xml")
        val found = UiEditorState.mismatches(client, listOf(session))
        assertEquals(4, found.size, found.joinToString("\n"))
        assertTrue(found[0].contains("keeps 2 editors of xhtml-demo/page.xml"), found[0])
        assertTrue(found.any { it.contains("counts docs/headings.md as open") })
        assertTrue(found.any { it.contains("counts envs/b/.env as open") })
        assertTrue(found.any { it.contains("shows notes.txt, which the backend does not count") })
        assertEquals(3, found.count(UiEditorState::isStuck), "a Client-only tab is not stuck: " + found.joinToString("\n"))
        // With two Client sessions, which one is this Client's is unknown: only extra editors are told.
        val two = UiEditorState.mismatches(client, listOf(session, session.copy(label = UiEditorState.sessionLabel("AAAA"))))
        assertTrue(two.all { it.contains("keeps 2 editors") }, two.joinToString("\n"))
    }
}
