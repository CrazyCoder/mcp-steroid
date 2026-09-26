/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiComponentFactsTest {
    @Test
    fun `html tags go and character references are decoded`() {
        assertEquals("Run 'Run Plugin (2026.2)'", UiComponentFacts.clean("<html>Run &#39;Run Plugin (2026.2)&#39;</html>"))
        assertEquals("a & b < c > d \"e\" 'f' g", UiComponentFacts.clean("a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos;&nbsp;g"))
        assertEquals("x ' y", UiComponentFacts.clean("x &#x27; y"))
    }

    @Test
    fun `a local class is named without the number the JVM gives it`() {
        assertEquals("MyTextField", UiComponentFacts.simpleName("com.intellij.ide.util.GotoLineNumberDialog$1MyTextField"))
        assertEquals("MyTextField", UiComponentFacts.simpleName("1MyTextField"))
        assertEquals("JBTextField", UiComponentFacts.simpleName("com.intellij.ui.components.JBTextField"))
    }

    @Test
    fun `an unknown reference stays as it is`() {
        assertEquals("&copy; &#xZZ;", UiComponentFacts.clean("&copy; &#xZZ;"))
    }
}
