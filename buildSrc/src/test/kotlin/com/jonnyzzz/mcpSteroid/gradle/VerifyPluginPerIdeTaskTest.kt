/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class VerifyPluginPerIdeTaskTest {
    private val failing = listOf("Compatibility problems", "Override-only API usages")

    private fun output(vararg sections: String) = (listOf(
        "2026-09-27T19:44:30 [main] INFO  verification - Finished 1 of 1 verifications: IU-263.5701.42 against p:1.0: Compatible",
        "Compatibility problems (1): in the log preamble, before any result",
        "Plugin p:1.0 against IU-263.5701.42: Compatible. 112 usages of internal API",
    ) + sections).joinToString("\n")

    @Test
    fun `a compatible result with only allowed sections passes`() {
        val text = output("Internal API usages (112): ", "    Internal method a.B.c() is invoked in d.E.f()")
        assertEquals(emptyList(), VerifyPluginPerIdeTask.failedSections(text, failing))
    }

    @Test
    fun `a failing section after the result line is reported once`() {
        val text = output(
            "Compatibility problems (2): ",
            "    #Invocation of unresolved method a.B.c()",
            "Override-only API usages (1): ",
            "    Override-only method a.B.d() is invoked in e.F.g()",
            "Compatibility problems (1): ",
        )
        assertEquals(failing, VerifyPluginPerIdeTask.failedSections(text, failing))
    }

    @Test
    fun `output without a result line has no sections`() {
        assertEquals(emptyList(), VerifyPluginPerIdeTask.failedSections("Compatibility problems (1): ", failing))
    }
}
