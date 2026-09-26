/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JPanel

class UiSnapshotFormatterTest {
    private val header = UiWindowHeader("w-1", "Settings", "dialog", modal = true, source = "remote-driver", note = null)
    private val dummy = JPanel()

    private fun node(
        cls: String,
        name: String? = null,
        text: List<String> = emptyList(),
        value: String? = null,
        states: Set<UiState> = emptySet(),
        interactive: Boolean = false,
        kids: List<UiNode> = emptyList(),
    ) = UiNode(dummy, cls, name, text, null, value, states, interactive, kids)

    @Test
    fun `listed components get a line with ref and states, wrappers with one child are skipped`() {
        val tree = node("JDialog", kids = listOf(
            node("JRootPane", kids = listOf(
                node("JPanel", kids = listOf(
                    node("JBCheckBox", name = "Show tool window bars", states = setOf(UiState.CHECKED), interactive = true),
                    node("JButton", name = "OK", states = setOf(UiState.DEFAULT), interactive = true),
                )),
            )),
        ))
        var n = 0
        val out = UiSnapshotFormatter.format(header, tree, { "e${++n}" }, maxNodes = 400, withBounds = false)
        assertEquals(
            """
            window w-1 "Settings" (dialog, modal) source=remote-driver
            - JPanel
              - JBCheckBox "Show tool window bars" [ref=e1] [checked]
              - JButton "OK" [ref=e2] [default]
            """.trimIndent(),
            out.text,
        )
        assertEquals(3, out.listedCount)
    }

    @Test
    fun `long values are cut and the node cap counts the rest`() {
        val many = (1..10).map { node("JButton", name = "B$it", interactive = true) }
        val tree = node("JPanel", kids = listOf(
            node("JTextArea", value = "x".repeat(500), interactive = true),
            node("JPanel", kids = many),
        ))
        val out = UiSnapshotFormatter.format(header, tree, { "e0" }, maxNodes = 5, withBounds = false)
        assertTrue(out.text, out.text.contains("value=\"" + "x".repeat(80) + "…\""))
        assertEquals(7, out.cut)
        assertTrue(out.text, out.text.contains("… 7 more"))
    }

    @Test
    fun `painted text beyond eight entries is counted, not listed`() {
        val tree = node("JPanel", kids = listOf(
            node("Tree", name = "Settings categories", text = (1..12).map { "row$it" }, interactive = true),
        ))
        val out = UiSnapshotFormatter.format(header, tree, { "e7" }, maxNodes = 400, withBounds = false)
        assertTrue(out.text, out.text.contains("text=row1|row2|row3|row4|row5|row6|row7|row8|+4"))
    }

    @Test
    fun `a note from the model is shown under the header`() {
        val out = UiSnapshotFormatter.format(header.copy(source = "swing", note = "remote driver unavailable: x"), node("JPanel"), { "e0" }, 400, false)
        assertEquals("window w-1 \"Settings\" (dialog, modal) source=swing\nnote: remote driver unavailable: x", out.text)
    }
}
