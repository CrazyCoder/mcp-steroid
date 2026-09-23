/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseInfoTest {
    @Test
    fun `reads the version from release json`() {
        assertEquals("0.103", parseReleaseVersion("""{"version":"0.103","pluginId":"x","sha256":"ab"}"""))
    }

    @Test
    fun `ignores unknown keys`() {
        assertEquals("0.104", parseReleaseVersion("""{"version":"0.104","extra":{"a":1}}"""))
    }

    @Test
    fun `strips a leading v`() {
        assertEquals("0.105", parseReleaseVersion("""{"version":"v0.105"}"""))
    }

    @Test
    fun `malformed json gives null`() {
        assertNull(parseReleaseVersion("<html>rate limited</html>"))
    }

    @Test
    fun `missing version gives null`() {
        assertNull(parseReleaseVersion("""{"pluginId":"x"}"""))
    }

    @Test
    fun `release json url points at the fork`() {
        assertEquals(
            "https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/release.json",
            RELEASE_JSON_URL,
        )
    }
}
