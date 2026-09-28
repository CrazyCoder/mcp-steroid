/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class UiCodeRangeTextTest : BasePlatformTestCase() {
    fun `test the text of each line is a span from its first character to its end, and a blank line has none`() {
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("fun a() = 1\n\n    val b = 2\n"))
        try {
            val spans = UiCodeRange.textSpans(editor, 0..2)
            assertEquals(2, spans.size)
            val (first, third) = spans
            assertEquals(editor.offsetToXY(0).x, first.x)
            assertEquals(editor.offsetToXY("fun a() = 1".length).x, first.x + first.width)
            assertTrue("the indent is no text: $third", third.x == editor.offsetToXY(editor.document.getLineStartOffset(2) + 4).x)
            assertEquals(editor.visualLineToY(2), third.y)
            assertEquals(editor.lineHeight, third.height)
        } finally {
            factory.releaseEditor(editor)
        }
    }
}
