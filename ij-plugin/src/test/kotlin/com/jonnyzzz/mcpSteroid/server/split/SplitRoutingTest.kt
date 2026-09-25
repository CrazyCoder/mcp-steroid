/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.platform.runtime.product.ProductMode
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitRoutingTest {
    private val none = buildJsonObject { }
    private fun side(value: String) = buildJsonObject { put(SIDE_ARGUMENT, value) }

    @Test
    fun `product modes map to roles`() {
        assertEquals(SplitRole.MONOLITH, classifySplitRole(ProductMode.MONOLITH))
        assertEquals(SplitRole.FRONTEND, classifySplitRole(ProductMode.FRONTEND))
        assertEquals(SplitRole.BACKEND, classifySplitRole(ProductMode.BACKEND))
    }

    @Test
    fun `monolith and backend run every tool locally`() {
        for (role in listOf(SplitRole.MONOLITH, SplitRole.BACKEND)) {
            for (tool in ROUTED_TOOLS.keys) {
                assertEquals("$role $tool", ToolSide.LOCAL, routeTool(role, tool, none))
            }
        }
    }

    @Test
    fun `split frontend forwards backend tools and keeps UI tools`() {
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_list_projects", none))
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_open_project", none))
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_execute_code", none))
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_execute_feedback", none))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_list_windows", none))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_take_screenshot", none))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_input", none))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_fetch_resource", none))
    }

    @Test
    fun `execute_code side argument overrides the default`() {
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_execute_code", side("frontend")))
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_execute_code", side("backend")))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.MONOLITH, "steroid_execute_code", side("frontend")))
    }

    @Test
    fun `side frontend on a backend endpoint is an error`() {
        val e = assertThrows(ToolCallErrorException::class.java) {
            routeTool(SplitRole.BACKEND, "steroid_execute_code", side("frontend"))
        }
        // A backend endpoint cannot tell whether a client is attached, so the message must not claim either.
        assertTrue(e.message, e.message.contains("cannot run code in the JetBrains Client"))
        assertFalse(e.message, e.message.contains("no frontend is attached"))
    }

    @Test
    fun `an unknown side value is an error`() {
        assertThrows(ToolCallErrorException::class.java) {
            routeTool(SplitRole.FRONTEND, "steroid_execute_code", side("both"))
        }
    }

    @Test
    fun `a non-string side value is an unsupported side`() {
        val values = listOf(buildJsonObject { put("x", 1) }, buildJsonArray { add("backend") })
        for (value in values) {
            val e = assertThrows(ToolCallErrorException::class.java) {
                routeTool(SplitRole.FRONTEND, "steroid_execute_code", buildJsonObject { put(SIDE_ARGUMENT, value) })
            }
            assertTrue(e.message, e.message.contains("Unsupported side"))
        }
    }

    @Test
    fun `a tool missing from the routing table fails loudly`() {
        val e = assertThrows(IllegalStateException::class.java) {
            routeTool(SplitRole.FRONTEND, "steroid_new_tool", none)
        }
        assertTrue(e.message!!, e.message!!.contains("no routing for tool steroid_new_tool"))
    }
}
