/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.refactor

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {
    @Test
    fun `tags go and named entities decode`() {
        assertEquals("a <b> & \"c\"", plainText("<html>a&nbsp;&lt;b&gt; &amp; &quot;c&quot;</html>"))
    }

    @Test
    fun `numeric entities decode`() {
        assertEquals("(err?: Error) => void", plainText("(err?:&#32;Error)&#32;=&#x3e;&#32;void"))
    }

    @Test
    fun `an escaped entity stays literal`() {
        assertEquals("&#32;", plainText("&amp;#32;"))
    }

    @Test
    fun `an invalid code point stays as written`() {
        assertEquals("&#99999999;", plainText("&#99999999;"))
    }
}
