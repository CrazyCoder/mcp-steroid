/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.openapi.updateSettings.impl.UpdateSettingsProvider

/**
 * The custom plugin repository of the latest GitHub release: one `<plugin>` entry with the version
 * and zip URL of that release, written by `release/scripts/make-update-plugins-xml.sh`.
 */
const val UPDATE_PLUGINS_URL = "https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/updatePlugins.xml"

/**
 * Adds [UPDATE_PLUGINS_URL] to the repositories the IDE's own plugin update check reads, so the IDE
 * offers and installs MCP Steroid Plus updates like a Marketplace plugin, including automatic plugin
 * updates when the user turned them on. The URL is not stored in the IDE settings and goes away with
 * the plugin.
 */
internal class PlusUpdateSettingsProvider : UpdateSettingsProvider {
    override fun getPluginRepositories(): List<String> = listOf(UPDATE_PLUGINS_URL)
}
