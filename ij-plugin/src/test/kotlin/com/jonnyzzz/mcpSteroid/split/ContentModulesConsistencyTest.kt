/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** `<content>` in plugin.xml and the content-module descriptors must list the same modules. */
class ContentModulesConsistencyTest {
    private val pluginDir = File(
        System.getProperty("mcp.steroid.test.projectHome") ?: error("mcp.steroid.test.projectHome is not set"),
        "ij-plugin",
    )
    private val pluginXml = File(pluginDir, "src/main/resources/META-INF/plugin.xml").readText()
    private val declared = Regex("""<module\s+name="([^"]+)"""").findAll(
        pluginXml.substringAfter("<content>", "").substringBefore("</content>")
    ).map { it.groupValues[1] }.toSet()
    private val descriptors = listOf("shared", "backend", "frontend").associateWith { dir ->
        File(pluginDir, "$dir/src/main/resources").listFiles { f -> f.name.startsWith("mcp-steroid.") && f.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }.orEmpty()
    }

    @Test
    fun `every content module has a descriptor and every descriptor is declared`() {
        assertEquals(setOf("mcp-steroid.shared", "mcp-steroid.backend", "mcp-steroid.frontend"), declared)
        assertEquals(declared, descriptors.values.flatten().toSet())
    }

    @Test
    fun `module descriptors use dependencies, never depends`() {
        for ((dir, names) in descriptors) for (name in names) {
            val xml = File(pluginDir, "$dir/src/main/resources/$name.xml").readText()
            assertFalse("$name must not use <depends>", xml.contains("<depends"))
        }
    }
}
