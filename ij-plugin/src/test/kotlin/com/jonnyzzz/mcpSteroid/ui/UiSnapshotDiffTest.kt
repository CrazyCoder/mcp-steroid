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
    fun `a window that closed is one line`() {
        val after = "window w-9 \"uiprobe\" (frame) source=remote-driver\n- JPanel"
        assertEquals("- window w-1 \"Settings\" (dialog, modal) closed", UiSnapshotDiff.diff("$before\n\n$after", after))
    }

    @Test
    fun `a window that opened is listed whole`() {
        val frame = "window w-9 \"uiprobe\" (frame) source=remote-driver\n- JButton \"Run\" [ref=e5]"
        assertEquals("+ $before", UiSnapshotDiff.diff(frame, "$before\n\n$frame"))
    }

    @Test
    fun `wrapper panels and the memory indicator are no change, other windows are named`() {
        val frame = "window w-9 \"uiprobe\" (frame) source=remote-driver\n- JPanel\n  - MemoryUsagePanelImpl \"Memory Usage: 500M\" [ref=e9]"
        val after = before.replace("[ref=e2] [disabled]", "[ref=e2]").replace("- JPanel", "- DialogPanel")
        assertEquals(
            """
            window w-1 "Settings" (dialog, modal):
            - JButton "Apply" [ref=e2] [disabled]
            + JButton "Apply" [ref=e2]
            """.trimIndent(),
            UiSnapshotDiff.diff("$before\n\n$frame", "$after\n\n" + frame.replace("500M", "700M")),
        )
    }
}
