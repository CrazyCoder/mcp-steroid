/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class UiCodeRangeCaretTest : BasePlatformTestCase() {
    private fun withEditor(block: (EditorEx) -> Unit) {
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("fun a() = 1\n")) as EditorEx
        try {
            block(editor)
        } finally {
            factory.releaseEditor(editor)
        }
    }

    fun `test a picture puts back the caret and its row as they were`() = withEditor { editor ->
        editor.settings.isCaretRowShown = true
        val show = UiCodeRange.hideCaret(editor)
        assertFalse(editor.settings.isCaretRowShown)
        assertFalse("hidden for the picture", editor.setCaretEnabled(false))
        show()
        assertTrue(editor.settings.isCaretRowShown)
        assertTrue("shown again", editor.setCaretEnabled(true))
    }

    fun `test a picture leaves a caret that was off, off`() = withEditor { editor ->
        editor.setCaretEnabled(false)
        UiCodeRange.hideCaret(editor)()
        assertFalse("still off", editor.setCaretEnabled(false))
    }
}
