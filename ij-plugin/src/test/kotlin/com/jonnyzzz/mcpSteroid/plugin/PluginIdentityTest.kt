/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.plugin

import com.jonnyzzz.mcpSteroid.notifications.MCP_STEROID_NOTIFICATION_GROUP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginIdentityTest {
    private val xml: String = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")!!
        .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun tag(name: String) = Regex("<$name[^>]*>([^<]*)</$name>").find(xml)!!.groupValues[1].trim()

    @Test
    fun `plugin id is the Plus id`() {
        assertEquals("io.github.crazycoder.mcp-steroid", tag("id"))
    }

    @Test
    fun `plugin name is MCP Steroid Plus`() {
        assertEquals("MCP Steroid Plus", tag("name"))
    }

    @Test
    fun `extension namespace follows the plugin id`() {
        assertFalse(xml.contains("defaultExtensionNs=\"com.jonnyzzz.mcp-steroid\""))
    }

    @Test
    fun `notification group is not the upstream group`() {
        assertEquals("io.github.crazycoder.mcp-steroid.notifications", MCP_STEROID_NOTIFICATION_GROUP)
        assertFalse(xml.contains("jonnyzzz.mcp.steroid.updates"))
    }

    @Test
    fun `descriptor names no internal product`() {
        assertFalse(xml.contains("jetdesk", ignoreCase = true))
    }
}
