/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class BackendReachPolicyTest {
    private var clock = 0L
    private val policy = BackendReachPolicy(full = 15.seconds, short = 1.seconds, quietPeriod = 30.seconds, nanoTime = { clock })

    @Test
    fun `a reachable backend gets the full timeout`() {
        assertEquals(15.seconds, policy.timeout())
    }

    @Test
    fun `after a failure the next calls fail fast`() {
        policy.onFailure()
        clock += 10.seconds.inWholeNanoseconds
        assertEquals(1.seconds, policy.timeout())
    }

    @Test
    fun `the full timeout returns after the quiet period`() {
        policy.onFailure()
        clock += 31.seconds.inWholeNanoseconds
        assertEquals(15.seconds, policy.timeout())
    }

    @Test
    fun `a success restores the full timeout`() {
        policy.onFailure()
        policy.onSuccess()
        assertEquals(15.seconds, policy.timeout())
    }
}
