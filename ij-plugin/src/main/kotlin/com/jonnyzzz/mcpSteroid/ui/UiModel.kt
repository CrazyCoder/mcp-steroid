/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.diagnostic.logger
import java.awt.Component
import java.lang.reflect.InvocationTargetException
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.text.JTextComponent

/** A built tree, and the XPath query over the model it came from when that model has one. */
class UiModelBuild(val root: UiNode, val xpath: ((String) -> Set<Component>)? = null)

data class UiModelResult(val root: UiNode, val source: String, val note: String?, val xpath: ((String) -> Set<Component>)? = null)

/** Builds the snapshot tree of a root component. Call on the EDT. */
object UiModel {
    const val SOURCE_REMOTE_DRIVER = "remote-driver"
    const val SOURCE_SWING = "swing"

    private val log = logger<UiModel>()

    fun build(
        root: Component,
        onlyShowing: Boolean = true,
        remote: (Component) -> UiModelBuild = RemoteDriverModel::build,
        remoteUnavailable: () -> String? = RemoteDriverModel::unavailableReason,
    ): UiModelResult {
        val unavailable = remoteUnavailable()
        if (unavailable != null) {
            return UiModelResult(labelled(FallbackUiWalker(onlyShowing).build(root)), SOURCE_SWING, "remote driver unavailable: $unavailable")
        }
        return try {
            val built = remote(root)
            UiModelResult(labelled(built.root), SOURCE_REMOTE_DRIVER, null, built.xpath)
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            log.warn("The remote-driver UI model failed; using the Swing walker", cause)
            UiModelResult(labelled(FallbackUiWalker(onlyShowing).build(root)), SOURCE_SWING, "remote driver failed: ${cause.message}")
        }
    }

    /**
     * Gives an unnamed field the caption of the label or checkbox just before it, as a form lays them out: the combo
     * box after "Show line numbers:" reads as that option's value.
     */
    fun labelled(node: UiNode): UiNode {
        val kids = node.children.map(::labelled)
        val captioned = kids.mapIndexed { i, kid ->
            val caption = kids.getOrNull(i - 1)?.takeIf { i > 0 }?.let(::caption)
            if (caption != null && kid.name == null && kid.label == null && takesCaption(kid.component)) kid.copy(label = caption) else kid
        }
        return if (captioned.indices.all { captioned[it] === node.children[it] }) node else node.copy(children = captioned)
    }

    private fun caption(node: UiNode): String? =
        if (node.component is JLabel || node.component is JCheckBox) node.name ?: node.text.firstOrNull() else null

    private fun takesCaption(c: Component): Boolean = c is JComboBox<*> || c is JTextComponent || c is JSpinner
}
