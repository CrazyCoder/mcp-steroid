/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Component
import java.awt.Container

/**
 * Builds the tree from Swing alone, for when the Performance Testing plugin's model is not available.
 * It reads accessible names, the text of labels and buttons, and tooltips, but not painted text such as
 * tree and list rows. Call on the EDT.
 */
class FallbackUiWalker(
    private val onlyShowing: Boolean = true,
    private val skipInvisible: Boolean = onlyShowing,
) {
    fun build(root: Component): UiNode = node(root, depth = 0)

    private fun node(c: Component, depth: Int): UiNode {
        val kids = if (c is Container && depth < MAX_DEPTH) {
            c.components.filter(::include).map { node(it, depth + 1) }
        } else {
            emptyList()
        }
        return UiNode(
            component = c,
            className = UiComponentFacts.simpleClassName(c),
            name = UiComponentFacts.name(c),
            text = listOfNotNull(UiComponentFacts.ownText(c)),
            tooltip = UiComponentFacts.tooltip(c),
            value = UiComponentFacts.value(c),
            states = UiComponentFacts.states(c),
            interactive = UiComponentFacts.interactive(c),
            children = kids,
        )
    }

    private fun include(c: Component): Boolean = when {
        onlyShowing -> c.isShowing
        skipInvisible -> c.isVisible
        else -> true
    }

    companion object {
        private const val MAX_DEPTH = 64
    }
}
