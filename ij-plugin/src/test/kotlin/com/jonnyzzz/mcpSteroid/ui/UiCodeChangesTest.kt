/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.ui.UiCodeChanges.FileChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCodeChangesTest {
    private val before = """
        class App {
            void main() {
                int total = count * 2 + 1;
                print(total);
            }
        }
    """.trimIndent() + "\n"
    private val after = """
        class App {
            void main() {
                int total = computeTotal();
                print(total);
            }

            int computeTotal() {
                return count * 2 + 1;
            }
        }
    """.trimIndent() + "\n"
    private val extract = FileChange("src/App.java", before, after)

    @Test
    fun `a summary gives each file's counts and its hunks headed by their new line`() {
        assertEquals(
            """
            changed src/App.java (+5 -1)
              @@ 3
              -        int total = count * 2 + 1;
              +        int total = computeTotal();
              @@ 5
              +    }
              +
              +    int computeTotal() {
              +        return count * 2 + 1;
            """.trimIndent(),
            UiCodeChanges.render(listOf(extract)),
        )
        assertEquals("changed src/App.java (+5 -1)", UiCodeChanges.counts(listOf(extract)))
    }

    @Test
    fun `a moved file is one line, and a long diff is cut with the way to the rest`() {
        val text = "a\nb\n"
        val moved = UiCodeChanges.render(listOf(FileChange("src/A.java", text, null), FileChange("src/pkg/A.java", null, text)))
        assertEquals("moved src/A.java -> src/pkg/A.java", moved)
        val cut = UiCodeChanges.render(listOf(extract), maxLines = 2)
        assertTrue(cut, cut.endsWith("… 4 more diff lines; {\"action\":\"get\",\"changes\":true} lists them all"))
    }

    @Test
    fun `diff lines match one after another in a hunk, without indentation`() {
        assertTrue(UiCodeChanges.diffHas(extract, listOf("-int total = count * 2 + 1;", "+int total = computeTotal();")))
        // An unchanged line around the change is matched as context.
        assertTrue(UiCodeChanges.diffHas(extract, listOf("+int total = computeTotal();", " print(total);")))
        assertTrue(UiCodeChanges.diffHas(extract, listOf("+    int computeTotal() {")))
        // Out of order, or a line the diff lacks, does not match.
        assertFalse(UiCodeChanges.diffHas(extract, listOf("+int total = computeTotal();", "-int total = count * 2 + 1;")))
        assertFalse(UiCodeChanges.diffHas(extract, listOf("-print(total);")))
        assertTrue(UiCodeChanges.diffHas(FileChange("New.java", null, "class New {}\n"), listOf("+class New {}")))
    }

    @Test
    fun `hunks close together share a hunk with their context`() {
        val a = (1..10).joinToString("\n") { "line $it" }
        val b = a.replace("line 3", "LINE 3").replace("line 5", "LINE 5")
        val hunks = UiCodeChanges.hunks(a, b, context = 1)
        assertEquals(1, hunks.size)
        assertEquals(2, hunks[0].newLine)
        assertEquals(listOf(" line 2", "-line 3", "+LINE 3", " line 4", "-line 5", "+LINE 5", " line 6"), hunks[0].lines)
    }
}
