/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.process.windowsCommandLineArg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WindowsCommandLineArgTest {
    @Test
    fun `embedded double quotes are escaped inside a quoted argument`() {
        assertEquals(""""bash -c 'if [ -d \"/x\" ]; then echo ok; fi'"""", windowsCommandLineArg("""bash -c 'if [ -d "/x" ]; then echo ok; fi'"""))
    }

    @Test
    fun `backslashes before a quote are doubled, others are kept`() {
        assertEquals(""""a\b \\\"c"""", windowsCommandLineArg("""a\b \"c"""))
    }

    @Test
    fun `trailing backslashes are doubled before the closing quote`() {
        assertEquals(""""dir\\"""", windowsCommandLineArg("""dir\"""))
    }

    @Test
    fun `newlines stay inside the quoted argument`() {
        assertEquals("\"line1\nsay \\\"hi\\\"\"", windowsCommandLineArg("line1\nsay \"hi\""))
    }
}
