/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.refactor

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangedLinesTest {
    @Test
    fun `removed lines come first with a minus, added lines with a plus, at their line numbers`() {
        val before = "import a.B\nimport a.C\nimport a.D\n\nclass X\n"
        val after = "import a.*\n\nclass X\n"
        assertEquals(listOf("1: - import a.B", "2: - import a.C", "3: - import a.D", "1: + import a.*"), changedLines(before, after))
    }

    @Test
    fun `identical texts change no line`() {
        assertEquals(emptyList<String>(), changedLines("a\nb\n", "a\nb\n"))
    }

    @Test
    fun `a line in the middle is replaced in place`() {
        assertEquals(listOf("2: - b", "2: + B"), changedLines("a\nb\nc\n", "a\nB\nc\n"))
    }
}
