/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDriverModelTest {
    /** Refuses every class the way a plugin's main loader refuses a class of its content module. */
    private class RefusingLoader : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            throw IllegalStateException("Class $name must not be requested from main classloader")
    }

    private class ServingLoader(private val served: Class<*>) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = served
    }

    @Test
    fun `a loader that refuses the class is skipped and the next one serves it`() {
        val found = RemoteDriverModel.loadCreator(sequenceOf(RefusingLoader(), ServingLoader(String::class.java)))
        assertEquals(String::class.java, found)
    }

    @Test
    fun `when every loader refuses, the reasons are reported`() {
        val e = runCatching { RemoteDriverModel.loadCreator(sequenceOf(RefusingLoader())) }.exceptionOrNull()
        assertTrue(e is ClassNotFoundException)
        assertTrue(e!!.message, e.message!!.contains("must not be requested from main classloader"))
    }
}
