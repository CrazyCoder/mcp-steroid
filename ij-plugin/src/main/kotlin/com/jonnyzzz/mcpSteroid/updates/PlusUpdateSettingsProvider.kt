/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.openapi.updateSettings.impl.UpdateSettingsProvider
import com.jonnyzzz.mcpSteroid.devrig.PLUGIN_REPOSITORY_URL

/**
 * Adds [PLUGIN_REPOSITORY_URL] to the repositories the IDE's own plugin update check reads, so the IDE
 * offers and installs MCP Steroid Plus updates like a Marketplace plugin, including automatic plugin
 * updates when the user turned them on. The URL is not stored in the IDE settings and goes away with
 * the plugin.
 */
internal class PlusUpdateSettingsProvider : UpdateSettingsProvider {
    override fun getPluginRepositories(): List<String> = listOf(PLUGIN_REPOSITORY_URL)
}
