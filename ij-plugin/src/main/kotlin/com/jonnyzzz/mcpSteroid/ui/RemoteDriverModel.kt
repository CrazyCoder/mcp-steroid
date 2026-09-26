/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.contentModules
import com.intellij.openapi.extensions.PluginId
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.awt.Component
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory

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
        // Content modules first: in 2026.1 the class ships in the main jar but belongs to the remote-driver
        // content module, and the main loader refuses it with a PluginException rather than a
        // ClassNotFoundException.
        val loaders = plugin.contentModules.asSequence().mapNotNull { it.pluginClassLoader } +
            sequenceOf(plugin.pluginClassLoader).filterNotNull()
        return loadCreator(loaders)
    }

    /** The creator class from the first of [loaders] that serves it. A loader that throws anything is skipped. */
    fun loadCreator(loaders: Sequence<ClassLoader>): Class<*> {
        val refusals = mutableListOf<String>()
        for (loader in loaders) {
            try {
                return loader.loadClass(CREATOR)
            } catch (e: ClassNotFoundException) {
                refusals += "not found in ${loader.javaClass.simpleName}"
            } catch (e: RuntimeException) {
                refusals += e.message ?: e.javaClass.name
            }
        }
        throw ClassNotFoundException("$CREATOR is not available: ${refusals.joinToString("; ").ifEmpty { "no class loader" }}")
    }

    /** Builds the model of [root] and its showing descendants. Call on the EDT. */
    fun build(root: Component): UiModelBuild {
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
        return UiModelBuild(toNode(top)) { xpath -> evaluate(document, xpath) }
    }

    /** The components of the elements [xpath] selects in [document]. */
    private fun evaluate(document: Document, xpath: String): Set<Component> {
        val nodes = try {
            XPathFactory.newInstance().newXPath().compile(xpath).evaluate(document, XPathConstants.NODESET) as NodeList
        } catch (e: XPathExpressionException) {
            throw IllegalArgumentException("bad xpath '$xpath': ${e.message ?: e.cause?.message}", e)
        }
        return (0 until nodes.length).mapNotNull { (nodes.item(it) as? Element)?.getUserData("component") as? Component }.toSet()
    }

    private fun toNode(e: Element): UiNode {
        val component = e.getUserData("component") as? Component
            ?: error("a UI model element carries no component")
        return UiNode(
            component = component,
            className = e.getAttribute("class").ifEmpty { UiComponentFacts.simpleClassName(component) },
            name = e.getAttribute("accessiblename").let(UiComponentFacts::clean).takeIf { it.isNotEmpty() },
            text = e.getAttribute("visible_text").split(SEPARATOR).map(UiComponentFacts::clean).filter { it.isNotEmpty() }.dropRepeats(),
            tooltip = e.getAttribute("tooltiptext").let(UiComponentFacts::clean).takeIf { it.isNotEmpty() },
            value = UiComponentFacts.value(component),
            states = UiComponentFacts.states(component),
            interactive = UiComponentFacts.interactive(component),
            children = e.childElements().filter { it.tagName == "div" }.map(::toNode).toList(),
        )
    }

    /** A label painted twice in a row (a tooltip and its text, a split button's halves) is listed once. */
    private fun List<String>.dropRepeats(): List<String> = filterIndexed { i, s -> i == 0 || s != this[i - 1] }

    private fun Element.childElements(): Sequence<Element> =
        (0 until childNodes.length).asSequence().map { childNodes.item(it) }.filterIsInstance<Element>()
}
