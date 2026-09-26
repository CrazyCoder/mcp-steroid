/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiSnapshotDiffTest {
    private val before = """
        window w-1 "Settings" (dialog, modal) source=remote-driver
        - JPanel
          - JBCheckBox "Show tool window bars" [ref=e1] [focused]
          - JButton "Apply" [ref=e2] [disabled]
    """.trimIndent()

    @Test
    fun `added and removed lines are marked and unchanged lines dropped`() {
        val after = """
            window w-1 "Settings" (dialog, modal) source=remote-driver
            - JPanel
              - JBCheckBox "Show tool window bars" [ref=e1] [checked]
              - JButton "Apply" [ref=e2]
        """.trimIndent()
        assertEquals(
            """
            - JBCheckBox "Show tool window bars" [ref=e1]
            - JButton "Apply" [ref=e2] [disabled]
            + JBCheckBox "Show tool window bars" [ref=e1] [checked]
            + JButton "Apply" [ref=e2]
            """.trimIndent(),
            UiSnapshotDiff.diff(before, after),
        )
    }

    @Test
    fun `a focus change alone is no change`() {
        val after = before.replace(" [focused]", "").replace("[ref=e2] [disabled]", "[ref=e2] [disabled] [focused]")
        assertEquals("", UiSnapshotDiff.diff(before, after))
    }

    @Test
    fun `a window that closed shows as its removed header`() {
        val after = "window w-9 \"uiprobe\" (frame) source=remote-driver\n- JPanel"
        val diff = UiSnapshotDiff.diff("$before\n\n$after", after)
        assertEquals(true, diff.startsWith("- window w-1 \"Settings\""))
    }
}
