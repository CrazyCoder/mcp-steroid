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
    fun `an aged time, the gutter and a file editor's text are no change`() {
        val frame = { age: String, lines: String, text: String ->
            """
            window w-3 "refplay – App.java" (frame) source=remote-driver
            - JPanel
              - EditorGutterComponentImpl [ref=e75] text=$lines
              - EditorComponentImpl "Editor for App.java" [ref=e56] [editable] value="$text"
              - EditorComponentImpl "Name:" [ref=e57] [editable] value="$text"
              - TextPanel "// Build completed successfully in 2 sec ($age)" [ref=e123]
            """.trimIndent()
        }
        assertEquals(
            """
            - EditorComponentImpl "Name:" [ref=e57] [editable] value="a"
            + EditorComponentImpl "Name:" [ref=e57] [editable] value="b"
            """.trimIndent(),
            UiSnapshotDiff.diff(frame("a minute ago", "1|2", "a"), frame("12 minutes ago", "1|2|3", "b")),
        )
    }

    @Test
    fun `a focus change alone is no change`() {
        val after = before.replace(" [focused]", "").replace("[ref=e2] [disabled]", "[ref=e2] [disabled] [focused]")
        assertEquals("", UiSnapshotDiff.diff(before, after))
    }

    @Test
    fun `controls rebuilt under new refs are counted, not listed`() {
        val after = before.replace("[ref=e1]", "[ref=e7]").replace("[ref=e2] [disabled]", "[ref=e8]")
        assertEquals(
            """
            - JButton "Apply" [ref=e2] [disabled]
            + JButton "Apply" [ref=e8]
            ~ 1 control(s) rebuilt with new refs; take a snapshot for them
            """.trimIndent(),
            UiSnapshotDiff.diff(before, after),
        )
    }

    @Test
    fun `background tasks in the status bar are no change, with everything under them`() {
        val bar = { task: String ->
            "$before\n  - InlineProgressPanel \"Background process: $task\" [ref=e5]\n    - TextPanel [ref=e6] text=$task\n  - JLabel \"Ready\" [ref=e9]"
        }
        assertEquals("", UiSnapshotDiff.diff(bar("Indexing"), bar("Preparing new chat")))
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
