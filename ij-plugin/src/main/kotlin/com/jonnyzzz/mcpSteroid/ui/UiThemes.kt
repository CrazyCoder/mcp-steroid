/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.actions.QuickChangeLookAndFeel
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.jonnyzzz.mcpSteroid.server.UiRestore
import com.jonnyzzz.mcpSteroid.server.UiSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.Window
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * The IDE's themes, as Settings | Appearance & Behavior | Appearance lists them: the installed ones, and a switch to one
 * that waits until every window has repainted in it, since a switch applies and repaints some time after it starts.
 */
object UiThemes {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /** An installed theme; [plugin] names the plugin that installed it, null for the IDE's own. */
    data class Theme(val id: String, val name: String, val dark: Boolean, val plugin: String?, val current: Boolean)

    /** The installed themes, the current one marked. EDT. */
    fun list(): List<Theme> {
        val laf = LafManager.getInstance()
        val current = laf.currentUIThemeLookAndFeel?.id
        return laf.installedThemes.map { Theme(it.id, it.name, it.isDark, pluginOf(it), it.id == current) }.toList()
    }

    /** The theme [wanted] names: by name without case, else by id. Fails listing the installed names. */
    fun pick(themes: List<Theme>, wanted: String): Theme =
        themes.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: themes.firstOrNull { it.id == wanted }
            ?: throw UiStepFailure("no installed theme \"$wanted\"; installed: ${themes.joinToString { it.name }}")

    /** One line per theme, and whether the theme follows the OS's light or dark mode. */
    fun render(themes: List<Theme>, syncWithOs: Boolean): String = buildString {
        append(themes.size).append(" installed theme(s)")
        if (syncWithOs) append("; the theme follows the OS's light or dark mode (Sync with OS), which a set of a theme turns off")
        append(':')
        for (t in themes) {
            append("\n  ").append(t.name).append(" (").append(if (t.dark) "dark" else "light").append(", id ").append(t.id)
            t.plugin?.let { append(", plugin ").append(it) }
            append(')')
            if (t.current) append(" [current]")
        }
    }

    /**
     * Switches to the theme [wanted] names, or to following the OS with [UiSteps.THEME_SYNC], and waits up to
     * [timeoutMs] until it is current and the windows have repainted. The editor color scheme follows the theme, as the
     * Appearance page switches it. Gives the step that puts the theme before back.
     */
    internal suspend fun set(wanted: String, timeoutMs: Long): UiConfig.Outcome {
        val laf = LafManager.getInstance()
        val (beforeId, beforeName, syncing) = withContext(edtAny) {
            val current = laf.currentUIThemeLookAndFeel
            Triple(current?.id, current?.name, laf.autodetect)
        }
        val restore = listOf(UiRestore.step("set", "theme" to (if (syncing) UiSteps.THEME_SYNC else beforeId ?: UiSteps.THEME_SYNC)))
        val before = if (syncing) "the OS's mode ($beforeName)" else "$beforeName"
        if (wanted.equals(UiSteps.THEME_SYNC, ignoreCase = true)) {
            if (syncing) return UiConfig.Outcome("the theme follows the OS already ($beforeName)")
            if (!withContext(edtAny) { laf.autodetectSupported }) throw UiStepFailure("this OS gives the IDE no light or dark mode to follow")
            withContext(writeSafe()) {
                laf.autodetect = true
                laf.updateUI()
            }
            settle(null, timeoutMs, "the OS's mode")
            val now = withContext(edtAny) { laf.currentUIThemeLookAndFeel?.name }
            return UiConfig.Outcome("theme: $before -> the OS's mode ($now)", undo = restore)
        }
        val info = withContext(edtAny) {
            val theme = pick(list(), wanted)
            laf.installedThemes.first { it.id == theme.id }
        }
        if (info.isRestartRequired()) throw UiStepFailure("${info.name} needs an IDE restart, which a step does not do")
        if (info.id == beforeId && !syncing) return UiConfig.Outcome("the theme is ${info.name} already")
        withContext(writeSafe()) {
            // A theme set while following the OS would be switched back at the OS's next change.
            if (syncing) laf.autodetect = false
            QuickChangeLookAndFeel.switchLafAndUpdateUI(laf, info, false)
        }
        settle(info, timeoutMs, info.name)
        return UiConfig.Outcome("theme: $before -> ${info.name}", undo = restore)
    }

    /**
     * Waits until [info] is the current theme, or any theme is for null, then repaints every showing window and waits
     * until the event thread has been quiet for a while, which is when the repaint has finished.
     */
    private suspend fun settle(info: UIThemeLookAndFeelInfo?, timeoutMs: Long, name: String) {
        val started = TimeSource.Monotonic.markNow()
        fun left() = timeoutMs - started.elapsedNow().inWholeMilliseconds
        while (withContext(edtAny) { info != null && LafManager.getInstance().currentUIThemeLookAndFeel?.id != info.id }) {
            if (left() <= 0) throw UiStepFailure("the theme did not switch to $name within $timeoutMs ms")
            delay(POLL_MS)
        }
        withContext(edtAny) { Window.getWindows().filter { it.isShowing }.forEach { it.repaint() } }
        UiSettle.settle(quietMs = QUIET_MS, maxMs = left().coerceAtLeast(QUIET_MS))
    }

    /**
     * The EDT context a theme switch runs in: the modality of the topmost modal dialog, or none. A switch also switches
     * the editor color scheme, a model change, which the platform refuses in the "any" modality the other steps read in.
     */
    private suspend fun writeSafe(): CoroutineContext {
        val modality = withContext(edtAny) {
            Window.getWindows().lastOrNull { it.isShowing && it is Dialog && it.isModal }?.let(ModalityState::stateForComponent)
                ?: ModalityState.nonModal()
        }
        return Dispatchers.EDT + modality.asContextElement()
    }

    /** The name of the plugin that installed [info], or null for a theme the IDE bundles. */
    private fun pluginOf(info: UIThemeLookAndFeelInfo): String? = runCatching {
        (info.providerClassLoader as? PluginAwareClassLoader)?.pluginDescriptor?.takeIf { !it.isBundled }?.name
    }.getOrNull()

    private const val QUIET_MS = 500L
    private const val POLL_MS = 50L
}
