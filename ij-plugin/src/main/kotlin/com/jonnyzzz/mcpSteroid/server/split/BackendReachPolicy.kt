/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import kotlin.time.Duration

/**
 * How long a Split Mode frontend waits for the backend. After the backend fails to answer, later calls
 * use [short] for [quietPeriod], so each UI tool does not wait the [full] timeout on a lost backend.
 */
class BackendReachPolicy(
    private val full: Duration,
    private val short: Duration,
    private val quietPeriod: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Volatile
    private var failedAt: Long? = null

    fun timeout(): Duration {
        val failed = failedAt ?: return full
        return if (nanoTime() - failed < quietPeriod.inWholeNanoseconds) short else full
    }

    fun onSuccess() {
        failedAt = null
    }

    fun onFailure() {
        failedAt = nanoTime()
    }
}
