/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle

class UiHostLayoutTest {
    private val line = """layout: the rows of InspectionsConfigTreeTable [ref=e1] are cut at the right: they need 31 px more; {"action":"window","width":797} makes room"""

    @Test
    fun `a problem survives the trip from the backend's report`() {
        val problem = UiLayout.Problem(line, """{"action":"window","width":797}""", null, Rectangle(1146, 438, 244, 456))
        val report = "some other line\n" + UiHostLayout.encode(problem, Dimension(720, 600))
        val decoded = UiHostLayout.decode(report).single()
        assertEquals(line, decoded.line)
        assertEquals("""{"action":"window","width":797}""", decoded.fix)
        assertEquals(Rectangle(1146, 438, 244, 456), decoded.area)
        assertEquals(Dimension(720, 600), decoded.window)
        assertEquals(emptyList<UiHostLayout.HostProblem>(), UiHostLayout.decode("no layout problems"))
    }

    @Test
    fun `a problem without a fix or an area decodes with nulls`() {
        val decoded = UiHostLayout.decode(UiHostLayout.encode(UiLayout.Problem("layout: x", null, null, null), Dimension(10, 10))).single()
        assertNull(decoded.fix)
        assertNull(decoded.area)
    }

    @Test
    fun `a backend window step grows the Client's window by the same amount`() {
        assertEquals(
            """{"action":"window","width":1073}""",
            UiHostLayout.clientFix("""{"action":"window","width":797}""", backend = Dimension(720, 600), client = Dimension(996, 722)),
        )
        assertEquals(
            """{"action":"window","width":1073,"height":822}""",
            UiHostLayout.clientFix("""{"action":"window","width":797,"height":700}""", backend = Dimension(720, 600), client = Dimension(996, 722)),
        )
        // A window step without a size grows the Client's window to its preferred size, as it would the backend's.
        assertEquals("""{"action":"window"}""", UiHostLayout.clientFix("""{"action":"window"}""", Dimension(720, 600), Dimension(996, 722)))
    }

    @Test
    fun `any other backend step runs on the backend`() {
        assertEquals(
            """{"action":"splitter","ref":"e4","size":"fit","side":"backend"}""",
            UiHostLayout.clientFix("""{"action":"splitter","ref":"e4","size":"fit"}""", Dimension(720, 600), Dimension(996, 722)),
        )
        assertNull(UiHostLayout.clientFix(null, Dimension(720, 600), Dimension(996, 722)))
    }
}
