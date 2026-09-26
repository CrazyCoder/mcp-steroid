/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.swing.JButton

class UiRefRegistryTest {
    @Test
    fun `one component keeps one ref, another gets a new one`() {
        val registry = UiRefRegistry(showing = { true })
        val a = JButton("A")
        val b = JButton("B")
        assertEquals(registry.refFor(a), registry.refFor(a))
        assertNotEquals(registry.refFor(a), registry.refFor(b))
    }

    @Test
    fun `a ref resolves while its component shows and is stale after`() {
        var showing = true
        val registry = UiRefRegistry(showing = { showing })
        val a = JButton("A")
        val ref = registry.refFor(a)
        assertEquals(UiRefResolution.Live(a), registry.resolve(ref))
        showing = false
        assertEquals(UiRefResolution.Stale(ref), registry.resolve(ref))
    }

    @Test
    fun `an unknown ref is reported as unknown`() {
        assertEquals(UiRefResolution.Unknown("e999"), UiRefRegistry(showing = { true }).resolve("e999"))
    }

    @Test
    fun `the oldest refs are evicted past the capacity`() {
        val registry = UiRefRegistry(capacity = 2, showing = { true })
        val first = registry.refFor(JButton("1"))
        registry.refFor(JButton("2"))
        registry.refFor(JButton("3"))
        assertEquals(UiRefResolution.Unknown(first), registry.resolve(first))
    }
}
