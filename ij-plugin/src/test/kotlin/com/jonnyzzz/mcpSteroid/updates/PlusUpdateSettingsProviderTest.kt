/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.ide.plugins.RepositoryHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PlusUpdateSettingsProviderTest : BasePlatformTestCase() {
    fun `test the IDE checks the release feed for plugin updates`() {
        assertTrue(
            "the custom plugin repositories must include $UPDATE_PLUGINS_URL",
            RepositoryHelper.getCustomPluginRepositoryHosts().contains(UPDATE_PLUGINS_URL),
        )
    }

    fun `test the feed is the updatePlugins xml of the latest release`() {
        assertEquals("https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/updatePlugins.xml", UPDATE_PLUGINS_URL)
    }
}
