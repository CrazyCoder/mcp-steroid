/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.plugins.PluginEnabler
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotificationKind
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotifications

/**
 * The upstream plugin installs into its own folder, so both can be present. Two copies fight over
 * registry keys and ports, so the upstream one is disabled. `PluginEnabler.HEADLESS` only writes the
 * persistent disabled flag; the copy that is already loaded keeps running until the restart.
 */
object UpstreamPluginGuard {
    const val UPSTREAM_PLUGIN_ID = "com.jonnyzzz.mcp-steroid"

    fun shouldDisable(installed: Boolean, disabled: Boolean): Boolean = installed && !disabled

    fun run() {
        val id = PluginId.getId(UPSTREAM_PLUGIN_ID)
        val installed = PluginManagerCore.getPlugin(id) != null
        if (!shouldDisable(installed, PluginManagerCore.isDisabled(id))) return
        PluginEnabler.HEADLESS.disableById(setOf(id))
        thisLogger().warn("Disabled the upstream plugin $UPSTREAM_PLUGIN_ID; restart the IDE to unload it")
        McpSteroidNotifications.getInstance().notify(
            McpSteroidNotificationKind.UPSTREAM_DISABLED, null, NotificationType.WARNING,
            "Upstream MCP Steroid disabled",
            "MCP Steroid Plus replaces the upstream MCP Steroid plugin, which is now disabled. Restart the IDE to finish.",
            NotificationAction.createSimpleExpiring("Restart IDE") {
                ApplicationManagerEx.getApplicationEx().restart(true)
            },
        )
    }
}
