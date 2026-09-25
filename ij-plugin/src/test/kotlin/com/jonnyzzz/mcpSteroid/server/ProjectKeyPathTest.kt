/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectKeyPathTest {
    @Test
    fun `the drive letter case does not change the key path`() {
        assertEquals("C:/work/app", projectKeyPath("c:/work/app"))
        assertEquals("C:/work/app", projectKeyPath("C:/work/app"))
    }

    @Test
    fun `paths without a drive letter are unchanged`() {
        assertEquals("/work/app", projectKeyPath("/work/app"))
        assertEquals("c", projectKeyPath("c"))
        assertEquals("", projectKeyPath(""))
        assertNull(projectKeyPath(null))
    }

    @Test
    fun `only the drive letter is normalized`() {
        assertEquals("C:/Work/App", projectKeyPath("c:/Work/App"))
    }
}
