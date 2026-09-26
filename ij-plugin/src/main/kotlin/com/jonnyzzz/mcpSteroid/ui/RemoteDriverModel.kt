/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.contentModules
import com.intellij.openapi.extensions.PluginId
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.awt.Component

/**
 * The Performance Testing plugin's UI model (`XpathDataModelCreator`), which adds the text a component
 * paints (tree and list rows, tabs, editor text) to what Swing reports.
 *
 * Its module has `visibility="internal"`, so Steroid cannot declare a dependency on it. The class is
 * loaded by name from the plugin's own class loaders: the main one (2026.1, where it is in
 * `performanceTesting.jar`) or a content module's (2026.2 and later). Three reflective calls: the
 * constructor, `getElementProcessors()` and `create(Component, Boolean, Component?)`.
 */
object RemoteDriverModel {
    private const val PLUGIN_ID = "com.jetbrains.performancePlugin"
    private const val CREATOR = "com.jetbrains.performancePlugin.remotedriver.xpath.XpathDataModelCreator"
    private const val DRIVER_EXTENSION = "com.jetbrains.performancePlugin.remotedriver.RemoteDriverDataModelExtension"
    private const val SEPARATOR = "||"

    /** Why the model cannot be used, or null when it can. */
    fun unavailableReason(): String? = try {
        creatorClass()
        null
    } catch (e: ClassNotFoundException) {
        e.message
    }

    private fun creatorClass(): Class<*> {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
            ?: throw ClassNotFoundException("the Performance Testing plugin is not installed")
        if (!PluginManagerCore.isLoaded(plugin.pluginId)) {
            throw ClassNotFoundException("the Performance Testing plugin is disabled")
        }
        val loaders = sequenceOf(plugin.pluginClassLoader) + plugin.contentModules.asSequence().map { it.pluginClassLoader }
        for (loader in loaders.filterNotNull()) {
            try {
                return Class.forName(CREATOR, false, loader)
            } catch (e: ClassNotFoundException) {
                continue
            }
        }
        throw ClassNotFoundException("$CREATOR is not in the Performance Testing plugin")
    }

    /** Builds the model of [root] and its showing descendants. Call on the EDT. */
    fun build(root: Component): UiNode {
        val type = creatorClass()
        val creator = type.getConstructor().newInstance()
        // RemoteDriverDataModelExtension and the Remote Development extensions call the JMX test driver,
        // which a normal IDE does not run: with them the build fails with "Invoker is not registered".
        @Suppress("UNCHECKED_CAST")
        val processors = type.getMethod("getElementProcessors").invoke(creator) as MutableList<Any>
        processors.removeIf { p ->
            p.javaClass.name == DRIVER_EXTENSION || p.javaClass.getMethod("isRemDevExtension").invoke(p) == true
        }
        val document = type.getMethod("create", Component::class.java, Boolean::class.javaPrimitiveType, Component::class.java)
            .invoke(creator, root, true, null) as Document
        val top = document.documentElement.childElements().firstOrNull { it.tagName == "div" }
            ?: error("the UI model of ${root.javaClass.name} is empty")
        return toNode(top)
    }

    private fun toNode(e: Element): UiNode {
        val component = e.getUserData("component") as? Component
            ?: error("a UI model element carries no component")
        return UiNode(
            component = component,
            className = e.getAttribute("class").ifEmpty { UiComponentFacts.simpleClassName(component) },
            name = e.getAttribute("accessiblename").let(UiComponentFacts::clean).takeIf { it.isNotEmpty() },
            text = e.getAttribute("visible_text").split(SEPARATOR).map(UiComponentFacts::clean).filter { it.isNotEmpty() },
            tooltip = e.getAttribute("tooltiptext").let(UiComponentFacts::clean).takeIf { it.isNotEmpty() },
            value = UiComponentFacts.value(component),
            states = UiComponentFacts.states(component),
            interactive = UiComponentFacts.interactive(component),
            children = e.childElements().filter { it.tagName == "div" }.map(::toNode).toList(),
        )
    }

    private fun Element.childElements(): Sequence<Element> =
        (0 until childNodes.length).asSequence().map { childNodes.item(it) }.filterIsInstance<Element>()
}
