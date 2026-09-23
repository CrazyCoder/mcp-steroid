/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

class SteroidsMcpServerRebindTest : BasePlatformTestCase() {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** The whole range a rebind tries: the pinned port and the nine after it. */
    private fun occupyTenConsecutivePorts(): List<ServerSocket> {
        repeat(50) {
            val base = freePort()
            val opened = mutableListOf<ServerSocket>()
            for (p in base until base + 10) {
                opened += runCatching { ServerSocket(p) }.getOrNull() ?: break
            }
            if (opened.size == 10) return opened
            opened.forEach { it.close() }
        }
        error("could not occupy ten consecutive ports")
    }

    private fun responds(port: Int): Boolean = try {
        val c = URI("http://127.0.0.1:$port/.well-known/mcp.json").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 2000
        c.responseCode == 200
    } catch (_: Exception) {
        false
    }

    override fun tearDown() {
        try {
            SteroidsMcpServer.getInstance().forgetAllForTest()
        } finally {
            super.tearDown()
        }
    }

    fun testRebindMovesTheServerToTheNewPort() {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val old = server.port
        val target = freePort()
        assertEquals(target, server.rebind(target))
        assertEquals(target, server.port)
        assertTrue(responds(target))
        assertFalse(responds(old))
    }

    fun testRebindKeepsTheOldPortWhenTheWholeNewRangeIsBusy() {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val old = server.port
        val busy = occupyTenConsecutivePorts()
        try {
            assertEquals(old, server.rebind(busy.first().localPort))
            assertEquals(old, server.port)
            assertTrue(responds(old))
        } finally {
            busy.forEach { it.close() }
        }
    }

    fun testRebindToABusyPortFallsBackToTheNextFreeOne() {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        ServerSocket(0).use { busy ->
            val bound = server.rebind(busy.localPort)
            assertTrue(bound > busy.localPort)
            assertEquals(bound, server.port)
            assertTrue(responds(bound))
        }
    }
}
