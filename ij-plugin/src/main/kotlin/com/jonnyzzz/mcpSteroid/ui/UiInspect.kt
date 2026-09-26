/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.internal.inspector.PropertyBean
import com.intellij.internal.inspector.UiInspectorContextProvider
import com.intellij.internal.inspector.UiInspectorListRendererContextProvider
import com.intellij.internal.inspector.UiInspectorTableRendererContextProvider
import com.intellij.internal.inspector.UiInspectorTreeRendererContextProvider
import com.intellij.internal.inspector.UiInspectorUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionMenu
import com.intellij.openapi.actionSystem.impl.ActionMenuItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ClientProperty
import com.intellij.ui.ExpandedItemListCellRendererWrapper
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.ui.ComponentWithEmptyText
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.SwingUtilities

/**
 * Where a control comes from, as the platform's UI Inspector (Ctrl+Alt+Click in internal mode) finds it, but in one
 * line an agent reads: its class and plugin, the action behind it, the tool window and dialog it sits in, its model
 * and renderer, and the code that created it. The inspector's own collector lists every Swing property, about 3 KB a
 * control, and fails on some zero-size components, so this reads only what locates the code. Call on the EDT.
 */
object UiInspect {
    private const val MAX_CONTEXT = 12
    private const val MAX_VALUE = 160
    private const val CREATOR_FRAMES = 3

    /** Frames of the UI toolkit and of the platform code that adds components; the creator is the first frame after them. */
    private val INFRASTRUCTURE = Regex(
        "^at (java\\.|javax\\.|jdk\\.|sun\\.|kotlin\\.|kotlinx\\.|com\\.intellij\\.(internal\\.inspector|ui\\.(popup|components|ComponentUtil|ScrollPaneFactory|dsl|tabs|treeStructure|scale)" +
            "|ide\\.ui\\.laf|openapi\\.(wm\\.impl|ui\\.DialogWrapper|actionSystem\\.impl)|util\\.ui|util\\.concurrency|openapi\\.application\\.impl|" +
            "ide\\.IdeEventQueue|platform\\.ide\\.core\\.permissions|openapi\\.progress))"
    )

    /**
     * Starts recording where each component is added, as the inspector does, and returns whether it was off. It stays
     * on until the IDE restarts: each added component keeps a stack trace, and only components added after this call
     * have one.
     */
    fun startRecording(): Boolean {
        if (UiInspectorUtil.isSaveStacktraces()) return false
        UiInspectorUtil.enableStacktraceSaving()
        return true
    }

    /** The facts of [c] as `key=value` pairs on one line, then the creator frames on a second line when recorded. */
    fun describe(c: Component, project: Project?): String {
        val facts = linkedMapOf<String, String>()
        facts["class"] = className(c.javaClass)
        pluginOf(c.javaClass)?.let { facts["plugin"] = it }
        action(c)?.let { action ->
            ActionManager.getInstance().getId(action)?.let { facts["action"] = it }
            facts["actionClass"] = className(action.javaClass)
            pluginOf(action.javaClass)?.let { facts["actionPlugin"] = it }
        }
        (stripeToolWindow(c)?.let { "opensToolWindow" to it } ?: project?.let { toolWindowOf(c, it) }?.let { "toolWindow" to it })?.let { (key, window) ->
            facts[key] = window.id
            ToolWindowEP.EP_NAME.extensionList.firstOrNull { it.id == window.id }?.factoryClass?.let { facts["toolWindowFactory"] = it }
        }
        DialogWrapper.findInstance(c)?.let { facts["dialog"] = className(it.javaClass) }
        when (c) {
            is JTree -> facts["model"] = className(c.model.javaClass)
            is JList<*> -> facts["model"] = className(c.model.javaClass)
            is JTable -> facts["model"] = className(c.model.javaClass)
        }
        renderer(c)?.let { facts["renderer"] = className(it.javaClass) }
        (c as? ComponentWithEmptyText)?.emptyText?.toString()?.takeIf { it.isNotBlank() }?.let { facts["emptyText"] = it }
        context(UiInspectorUtil.getProvider(c)?.uiInspectorContext).forEach { (k, v) -> facts.putIfAbsent(k, v) }
        val line = line(facts)
        return line + "\ncreated: " + (creator(c) ?: "not recorded")
    }

    /**
     * The facts of row [index] of a list, tree or table: the row's value and user object classes, the action behind a
     * popup item, the intention or quick fix behind an Alt+Enter item, and what the renderer tells the inspector.
     */
    fun describeRow(c: Component, index: Int): String {
        val facts = linkedMapOf<String, String>()
        val value: Any? = when (c) {
            is JList<*> -> c.model.getElementAt(index)
            is JTree -> c.getPathForRow(index)?.lastPathComponent
            is JTable -> c.getValueAt(index, 0)
            else -> null
        }
        facts["value"] = value?.let { className(it.javaClass) } ?: "null"
        val userObject = (value as? javax.swing.tree.TreeNode)?.let { TreeUtil.getUserObject(it) }
        if (userObject != null && userObject !== value) facts["userObject"] = className(userObject.javaClass)
        (userObject ?: value)?.let { item -> pluginOf(item.javaClass)?.let { facts["plugin"] = it } }
        itemFacts(value).forEach { (k, v) -> facts.putIfAbsent(k, v) }
        val provided = buildList {
            @Suppress("UNCHECKED_CAST")
            when (c) {
                is JList<*> -> (ExpandedItemListCellRendererWrapper.unwrap((c as JList<Any?>).cellRenderer) as? UiInspectorListRendererContextProvider)
                    ?.let { addAll(it.getUiInspectorContext(c, value, index)) }
                is JTree -> (c.cellRenderer as? UiInspectorTreeRendererContextProvider)?.let { addAll(it.getUiInspectorContext(c, value, index)) }
                is JTable -> (c.getCellRenderer(index, 0) as? UiInspectorTableRendererContextProvider)?.let { addAll(it.getUiInspectorContext(c, value, index, 0)) }
            }
            (value as? UiInspectorContextProvider)?.let { addAll(it.uiInspectorContext) }
            (userObject as? UiInspectorContextProvider)?.let { addAll(it.uiInspectorContext) }
            // A list cell renderer built with the UI DSL, such as Search Everywhere's, puts the row's facts on the
            // component it renders.
            rendered(c, value, index)?.let { UiInspectorUtil.getProvider(it)?.uiInspectorContext }?.let { addAll(it) }
        }
        context(provided).forEach { (k, v) -> facts.putIfAbsent(k, v) }
        return line(facts)
    }

    /** The facts as `key=value` pairs; "; " separates them, as inspector context names and values hold spaces. */
    private fun line(facts: Map<String, String>): String = facts.entries.joinToString("; ") { (k, v) -> "$k=${v.take(MAX_VALUE)}" }

    /** The first frames of the recorded creation stack that are not toolkit or component-adding code, or null. */
    fun creator(c: Component): String? {
        val trace = (c as? JComponent)?.let { ClientProperty.get(it, UiInspectorUtil.ADDED_AT_STACKTRACE) } ?: return null
        return creatorFrames(trace.stackTrace.map { "at $it" })
    }

    /** The first [CREATOR_FRAMES] of [frames] after the toolkit and component-adding frames, joined by " < ". */
    fun creatorFrames(frames: List<String>): String? =
        frames.filterNot { INFRASTRUCTURE.containsMatchIn(it) }.take(CREATOR_FRAMES).takeIf { it.isNotEmpty() }
            ?.joinToString(" < ") { it.removePrefix("at ") }

    /** The action behind [c]: a toolbar button's or menu item's, or the one a custom action component carries. */
    fun action(c: Component): AnAction? = when (c) {
        is ActionButton -> c.action
        is ActionMenuItem -> c.anAction
        is ActionMenu -> c.anAction
        is JComponent -> ClientProperty.get(c, CustomComponentAction.ACTION_KEY)
        else -> null
    }

    /** The id of the action behind [c], for the snapshot line. */
    fun actionId(c: Component): String? = action(c)?.let { ActionManager.getInstance().getId(it) }

    private fun itemFacts(value: Any?): Map<String, String> = when (value) {
        is PopupFactoryImpl.ActionItem -> buildMap {
            ActionManager.getInstance().getId(value.action)?.let { put("action", it) }
            put("actionClass", className(value.action.javaClass))
        }
        is IntentionActionDelegate -> itemFacts(IntentionActionDelegate.unwrap(value.delegate))
        is IntentionAction -> {
            val fix: Any = QuickFixWrapper.unwrap(value) ?: value
            listOfNotNull(
                (if (fix === value) "intention" else "quickFix") to className(fix.javaClass),
                pluginOf(fix.javaClass)?.let { "plugin" to it },
            ).toMap()
        }
        else -> emptyMap()
    }

    /** The tool window a stripe button opens, read through its `getToolWindow()`, which both UIs' buttons have. */
    private fun stripeToolWindow(c: Component): ToolWindow? =
        c.javaClass.methods.firstOrNull { it.name == "getToolWindow" && it.parameterCount == 0 }
            ?.let { runCatching { it.invoke(c) as? ToolWindow }.getOrNull() }

    private fun toolWindowOf(c: Component, project: Project): ToolWindow? {
        val manager = ToolWindowManager.getInstance(project)
        return manager.toolWindowIds.asSequence().mapNotNull { manager.getToolWindow(it) }
            .firstOrNull { window -> window.isVisible && window.component.let { it === c || SwingUtilities.isDescendingFrom(c, it) } }
    }

    /** The component the renderer paints row [index] with. */
    @Suppress("UNCHECKED_CAST")
    private fun rendered(c: Component, value: Any?, index: Int): Component? = runCatching {
        when (c) {
            is JList<*> -> (c as JList<Any?>).cellRenderer?.getListCellRendererComponent(c, value, index, false, false)
            is JTree -> c.cellRenderer?.getTreeCellRendererComponent(c, value, false, false, c.model.isLeaf(value), index, false)
            is JTable -> c.prepareRenderer(c.getCellRenderer(index, 0), index, 0)
            else -> null
        }
    }.getOrNull()

    private fun renderer(c: Component): Any? = when (c) {
        is JTree -> c.cellRenderer
        is JList<*> -> c.cellRenderer?.let { ExpandedItemListCellRendererWrapper.unwrap(it) }
        is JTable -> if (c.rowCount > 0 && c.columnCount > 0) c.getCellRenderer(0, 0) else null
        else -> null
    }

    /** The inspector context a component or renderer provides, as name to value, without empty values. */
    private fun context(beans: List<PropertyBean>?): List<Pair<String, String>> = beans.orEmpty().asSequence()
        .mapNotNull { bean -> bean.propertyValue?.toString()?.takeIf { it.isNotBlank() }?.let { bean.propertyName.trim() to it.replace('\n', ' ') } }
        .take(MAX_CONTEXT).toList()

    /** The owning plugin of [type], or null for the platform itself. */
    private fun pluginOf(type: Class<*>): String? =
        PluginManager.getPluginByClass(type)?.pluginId?.idString?.takeIf { it != PluginManagerCore.CORE_PLUGIN_ID }

    /** The class name, with the named superclass of an anonymous class. */
    private fun className(type: Class<*>): String =
        if (type.isAnonymousClass) "${type.name} (extends ${type.superclass?.name})" else type.name
}
