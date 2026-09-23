/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class PortPinsTest {
    @Test
    fun `windows paths normalize to lowercase forward slashes`() {
        assertEquals("e:/dev/programs/idea/plugins", PortPins.normalize("E:\\dev\\Programs\\IDEA\\plugins\\", windows = true))
    }

    @Test
    fun `posix paths keep their case`() {
        assertEquals("/Users/Me/Library/plugins", PortPins.normalize("/Users/Me/Library/plugins/", windows = false))
    }

    @Test
    fun `parse reads ports keyed by normalized path`() {
        val pins = PortPins.parse("""{"version":1,"ports":{"E:\\dev\\x\\plugins":6320}}""", windows = true)
        assertEquals(mapOf("e:/dev/x/plugins" to 6320), pins)
    }

    @Test
    fun `parse drops out-of-range and non-numeric ports`() {
        val pins = PortPins.parse("""{"version":1,"ports":{"/a":0,"/b":70000,"/c":"x","/d":6316}}""", windows = false)
        assertEquals(mapOf("/d" to 6316), pins)
    }

    @Test
    fun `parse of malformed text is null`() {
        assertNull(PortPins.parse("not json", windows = false))
    }

    @Test
    fun `pinned port matches the plugins path in another case`() {
        val file = Files.createTempFile("ports", ".json")
        Files.writeString(file, """{"version":1,"ports":{"e:/dev/x/plugins":6321}}""")
        assertEquals(6321, PortPins.pinnedPort(file, "E:\\dev\\X\\plugins", windows = true))
    }

    @Test
    fun `pinned port of a missing file is null`() {
        assertNull(PortPins.pinnedPort(Files.createTempDirectory("p").resolve("ports.json"), "/x", windows = false))
    }

    @Test
    fun `pin wins over a registry value the user set`() {
        assertEquals(6320, PortPins.resolve(registryPort = 7000, pinned = 6320))
    }

    @Test
    fun `no pin keeps the registry value`() {
        assertEquals(6315, PortPins.resolve(registryPort = 6315, pinned = null))
    }

    @Test
    fun `rebind target is the new pin`() {
        assertEquals(6322, PortPins.rebindTarget(currentPort = 6315, pinned = 6322))
    }

    @Test
    fun `no rebind when the pin is removed`() {
        assertNull(PortPins.rebindTarget(currentPort = 6315, pinned = null))
    }

    @Test
    fun `no rebind when the port already matches`() {
        assertNull(PortPins.rebindTarget(currentPort = 6322, pinned = 6322))
    }
}
