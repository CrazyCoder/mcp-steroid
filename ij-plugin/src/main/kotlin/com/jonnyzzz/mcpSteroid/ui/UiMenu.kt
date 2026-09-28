/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.DataManager
import com.intellij.ide.ui.MainMenuDisplayMode
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.mac.screenmenu.Menu
import com.intellij.util.ui.UIUtil
import com.jonnyzzz.mcpSteroid.server.UiSteps
import com.jonnyzzz.mcpSteroid.server.UiRestore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.awt.Component
import java.awt.Window
import javax.swing.JFrame
import javax.swing.JMenuBar
import kotlin.time.TimeSource

/**
 * The IDE's main menu as its action group builds it. Every way the IDE shows the menu (a menu bar, merged into the
 * main toolbar with the menus that do not fit folded into the Main Menu button, under that button alone, the macOS
 * screen menu bar, a Linux desktop's global menu) is built from the `MainMenu` group, so a path resolved through the
 * group reaches an item whichever of them shows, including a menu outside the IDE window that no click reaches.
 */
class UiMenu {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /** One item of a menu as it shows now, in the context of the control a person would open the menu from. */
    class Item(val action: AnAction, val text: String, val enabled: Boolean, val checked: Boolean?, val submenu: Boolean) {
        val id: String? get() = ActionManager.getInstance().getId(action)
        fun describe(): String = buildString {
            append(text)
            if (submenu) append(" >")
            id?.let { append(" (").append(it).append(')') }
            KeymapUtil.getFirstKeyboardShortcutText(action).takeIf { it.isNotEmpty() }?.let { append(" ").append(it) }
            if (!enabled) append(" [disabled]")
            when (checked) {
                true -> append(" [checked]")
                false -> append(" [unchecked]")
                null -> Unit
            }
        }
    }

    /** The items of [group] as its menu shows them: hidden ones left out, separators dropped, inline groups expanded. EDT. */
    fun items(group: ActionGroup, context: DataContext): List<Item> {
        val presentations = PresentationFactory()
        return Utils.expandActionGroup(group, presentations, context, ActionPlaces.MAIN_MENU, ActionUiKind.MAIN_MENU)
            .filter { it !is Separator }
            .map { action ->
                val p = presentations.getPresentation(action)
                Item(
                    action = action,
                    text = UiComponentFacts.clean(p.text.orEmpty()),
                    enabled = p.isEnabled,
                    checked = if (action is Toggleable) Toggleable.isSelected(p) else null,
                    submenu = action is ActionGroup && p.isPopupGroup,
                )
            }
    }

    /**
     * Resolves [path], such as `View > Appearance > Compact Mode`, from the main menu in the context of [component].
     * A path to an item runs it, as a click on it does, and reports a checkable item's state before and after; a
     * path to a submenu, or no path, lists the items. Fails on a segment that matches none or several items, listing
     * them. With [want], the item must be checkable, and runs only when its state differs, as a check step does.
     * Gives [undo] the step that puts a checkable item, or the checked item of its group, back.
     */
    suspend fun step(path: String?, component: Component, timeoutMs: Long, want: Boolean? = null, undo: (List<JsonObject>) -> Unit = {}): String {
        val segments = path?.split(UiRows.PATH_SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val (trail, item, siblings) = withContext(edtAny) {
            val context = DataManager.getInstance().getDataContext(component)
            var group = ActionManager.getInstance().getAction(IdeActions.GROUP_MAIN_MENU) as? ActionGroup
                ?: throw UiStepFailure("the IDE has no main menu group")
            var items = items(group, context)
            var found: Item? = null
            val trail = mutableListOf<String>()
            for ((i, segment) in segments.withIndex()) {
                val item = pick(items, segment, trail)
                trail += item.text
                found = item
                if (i < segments.lastIndex) {
                    if (!item.submenu) throw UiStepFailure("${trail.joinToString(" > ")} is an item, not a menu; drop what follows it")
                    group = item.action as ActionGroup
                    items = items(group, context)
                }
            }
            val listed = if (found?.submenu == true) items(found.action as ActionGroup, context) else items
            Triple(trail.toList(), found, listed)
        }
        val where = withContext(edtAny) { reach(component, trail.firstOrNull()) }
        if (item == null || item.submenu) {
            val title = if (trail.isEmpty()) "the main menu" else trail.joinToString(" > ")
            val folded = if (trail.isEmpty()) withContext(edtAny) { folded(component) } else emptyList()
            return "$title has ${siblings.size} item(s)$where:\n" + siblings.joinToString("\n") {
                "  " + it.describe() + if (it.text in folded) " [folded into the Main Menu button]" else ""
            }
        }
        if (want != null) {
            val checked = item.checked ?: throw UiStepFailure("${trail.joinToString(" > ")} is not a checkable item; run it with a menu step")
            if (checked == want) return "${trail.joinToString(" > ")} was already ${state(want)}$where"
        }
        if (!item.enabled) throw UiStepFailure("${trail.joinToString(" > ")} is disabled here$where")
        val before = item.checked
        val checkedBefore = siblings.filter { it.checked == true }.map { it.action }
        val ran = CompletableDeferred<Boolean>()
        val windows = UiSettle.showingWindows()
        ApplicationManager.getApplication().invokeLater({
            ActionManager.getInstance().tryToExecute(item.action, null, component, ActionPlaces.MAIN_MENU, true)
                .doWhenDone { ran.complete(true) }
                .doWhenRejected(Runnable { ran.complete(false) })
        }, ModalityState.stateForComponent(component))
        // An item that opens a modal dialog completes only when the dialog closes: stop waiting once a window opens,
        // and the step reports the window.
        val started = TimeSource.Monotonic.markNow()
        while (!ran.isCompleted && started.elapsedNow().inWholeMilliseconds < timeoutMs && UiSettle.showingWindows() == windows) delay(POLL_MS)
        if (ran.isCompleted && !ran.await()) throw UiStepFailure("${trail.joinToString(" > ")} did not run$where")
        val siblingsAfter = if (before == null) null else withContext(edtAny) {
            siblingsOf(trail, DataManager.getInstance().getDataContext(component))
        }
        val after = siblingsAfter?.firstOrNull { it.action === item.action }?.checked
        if (before != null && after != null && after != before) {
            // One of a group, such as the main menu modes, unchecks the one checked before: the restore checks that one.
            val unchecked = siblingsAfter.firstOrNull { it.action !== item.action && it.action in checkedBefore && it.checked == false }
            val restorePath = if (unchecked != null) trail.dropLast(1) + unchecked.text else trail
            undo(listOf(UiRestore.step(if (unchecked != null || before) "check" else "uncheck", "path" to restorePath.joinToString(" > "))))
        }
        return "ran ${trail.joinToString(" > ")}${item.id?.let { " ($it)" }.orEmpty()}" +
            (if (before != null && after != null) "; it was ${state(before)}, now ${state(after)}" else "") + where
    }

    private fun state(checked: Boolean) = if (checked) "checked" else "unchecked"

    /**
     * Sets how the IDE shows its main menu: [wanted] is one of [UiSteps.MENU_MODES]. Only the new UI on Windows and
     * Linux has the setting. Gives [undo] the step that sets the mode before back.
     */
    suspend fun setMode(wanted: String, frame: Window?, undo: (List<JsonObject>) -> Unit): String {
        if (SystemInfo.isMac || !ExperimentalUI.isNewUI()) {
            throw UiStepFailure("the main menu mode is a setting of the new UI on Windows and Linux; this IDE shows " +
                if (SystemInfo.isMac) "the macOS screen menu bar" else "the classic UI's menu bar")
        }
        val target = MODES.getValue(wanted)
        val before = withContext(edtAny) {
            val settings = UISettings.getInstance()
            val before = settings.mainMenuDisplayMode
            if (before != target) {
                // The IDE asks for a restart on Linux for this change, and applies it only after one.
                if (SystemInfo.isLinux && MainMenuDisplayMode.SEPARATE_TOOLBAR in setOf(before, target)) {
                    throw UiStepFailure("on Linux, a change to or from a separate menu bar needs a restart of the IDE")
                }
                settings.mainMenuDisplayMode = target
                settings.fireUISettingsChanged()
            }
            before
        }
        if (before == target) return "the main menu is ${describe(target)} already"
        undo(listOf(UiRestore.step("menu", "mode" to MODES.entries.first { it.value == before }.key)))
        // The merged menu measures its room once the toolbar is laid out again.
        UiSettle.settle()
        val folded = frame?.let { withContext(edtAny) { summary(it) } }
        return "the main menu is now ${describe(target)}, was ${describe(before)}" + folded?.let { "; $it" }.orEmpty()
    }

    private fun describe(mode: MainMenuDisplayMode) = when (mode) {
        MainMenuDisplayMode.UNDER_HAMBURGER_BUTTON -> "under the Main Menu button"
        MainMenuDisplayMode.MERGED_WITH_MAIN_TOOLBAR -> "merged into the main toolbar"
        MainMenuDisplayMode.SEPARATE_TOOLBAR -> "a menu bar of its own"
    }

    /** The items of the menu that holds the last item of [trail], expanded again. EDT. */
    private fun siblingsOf(trail: List<String>, context: DataContext): List<Item>? {
        var group = ActionManager.getInstance().getAction(IdeActions.GROUP_MAIN_MENU) as? ActionGroup ?: return null
        for (segment in trail.dropLast(1)) {
            group = items(group, context).firstOrNull { it.text == segment }?.action as? ActionGroup ?: return null
        }
        return items(group, context)
    }

    private fun pick(items: List<Item>, segment: String, trail: List<String>): Item =
        items[pickIndex(items.map { it.text }, segment, where(trail)) { items.joinToString("; ") { it.describe() } }]

    private fun where(trail: List<String>) = if (trail.isEmpty()) "the main menu" else trail.joinToString(" > ")

    /**
     * How a person reaches the menu [top] (a top-level menu's text) in the window that holds [component], as a
     * clause that starts with "; ", or "" when it shows in the window's menu bar. EDT.
     */
    fun reach(component: Component, top: String?): String {
        val frame = UIUtil.getParentOfType(JFrame::class.java, component) ?: return ""
        return when (val mode = mode(frame)) {
            is Mode.Outside -> "; a person opens it from ${mode.where}, which no click here reaches"
            is Mode.Hamburger -> "; a person opens it from the Main Menu button"
            is Mode.Merged -> if (top != null && top in mode.folded) "; a person opens it from the Main Menu button, where ${top} is folded" else ""
            Mode.Bar -> ""
        }
    }

    /**
     * The `menu:` line of [frame]'s snapshot when its menu is not all in view: menus folded into the Main Menu
     * button, the menu under that button alone, or a menu outside the window. Null when every menu shows in a bar.
     */
    fun summary(frame: Window): String? {
        if (frame !is JFrame) return null
        val step = """a menu step reaches any item, such as {"action":"menu","path":"View > Appearance"}"""
        return when (val mode = mode(frame)) {
            is Mode.Outside -> "menu: the main menu is ${mode.where}, outside the IDE window, so no click here reaches it; $step"
            is Mode.Hamburger -> "menu: the main menu is under the Main Menu button; $step"
            is Mode.Merged -> if (mode.folded.isEmpty()) null
            else "menu: ${andList(mode.folded)} ${if (mode.folded.size == 1) "is" else "are"} folded into the Main Menu button, " +
                "because the main toolbar has no room; $step, and a wider IDE window shows more"
            Mode.Bar -> null
        }
    }

    /** The menus folded into the Main Menu button of the window that holds [component]. EDT. */
    private fun folded(component: Component): List<String> {
        val frame = UIUtil.getParentOfType(JFrame::class.java, component) ?: return emptyList()
        return (mode(frame) as? Mode.Merged)?.folded.orEmpty()
    }

    private sealed interface Mode {
        class Outside(val where: String) : Mode
        object Hamburger : Mode
        class Merged(val folded: List<String>) : Mode
        object Bar : Mode
    }

    private fun mode(frame: JFrame): Mode {
        if (SystemInfo.isMac && Menu.isJbScreenMenuEnabled()) return Mode.Outside("the macOS screen menu bar")
        if (ExperimentalUI.isNewUI() && !SystemInfo.isMac) {
            when (UISettings.getInstance().mainMenuDisplayMode) {
                MainMenuDisplayMode.UNDER_HAMBURGER_BUTTON -> return Mode.Hamburger
                MainMenuDisplayMode.MERGED_WITH_MAIN_TOOLBAR -> {
                    val merged = UIUtil.uiTraverser(frame).filter(JMenuBar::class.java).firstOrNull { it.javaClass.simpleName == MERGED_MENU && it.isShowing }
                        ?: return Mode.Bar
                    val shown = (0 until merged.menuCount).mapNotNull { merged.getMenu(it)?.text?.let(UiComponentFacts::clean) }.toSet()
                    val all = topLevel(frame)
                    return Mode.Merged(all.filter { it !in shown })
                }
                MainMenuDisplayMode.SEPARATE_TOOLBAR -> Unit
            }
        }
        // A menu bar the frame holds but does not show, with no mode above to explain it, is a desktop's global menu.
        val bar = frame.rootPane.jMenuBar
        if (bar != null && !bar.isShowing && SystemInfo.isLinux) return Mode.Outside("the desktop's global menu")
        return Mode.Bar
    }

    /** The texts of the main menu's top-level menus that show in [frame]'s context. EDT. */
    private fun topLevel(frame: JFrame): List<String> {
        val group = ActionManager.getInstance().getAction(IdeActions.GROUP_MAIN_MENU) as? ActionGroup ?: return emptyList()
        return items(group, DataManager.getInstance().getDataContext(frame.rootPane)).map { it.text }
    }

    companion object {
        private const val MERGED_MENU = "MergedMainMenu"
        private val MODES = mapOf(
            "hamburger" to MainMenuDisplayMode.UNDER_HAMBURGER_BUTTON,
            "merged" to MainMenuDisplayMode.MERGED_WITH_MAIN_TOOLBAR,
            "toolbar" to MainMenuDisplayMode.SEPARATE_TOOLBAR,
        )

        /**
         * The index of the text in [texts] that [segment] names: equal to it without a trailing ellipsis or case, else
         * starting with it, else holding it. Fails when none matches, listing [listed], or when several match.
         */
        internal fun pickIndex(texts: List<String>, segment: String, where: String, listed: () -> String = { texts.joinToString("; ") }): Int {
            val wanted = norm(segment)
            val tiers = listOf<(String) -> Boolean>({ it == wanted }, { it.startsWith(wanted) }, { it.contains(wanted) })
            for (tier in tiers) {
                val hits = texts.indices.filter { tier(norm(texts[it])) }
                if (hits.size == 1) return hits.single()
                if (hits.size > 1) throw UiStepFailure("\"$segment\" matches ${hits.size} items in $where: " + hits.joinToString("; ") { texts[it] })
            }
            throw UiStepFailure("no item \"$segment\" in $where; its items: ${listed()}")
        }

        private fun norm(s: String) = s.trim().removeSuffix("…").removeSuffix("...").trim().lowercase()

        internal fun andList(items: List<String>): String =
            if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()
        private const val POLL_MS = 50L
    }
}
