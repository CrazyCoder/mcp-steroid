/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiTarget
import java.awt.Component
import javax.swing.SwingUtilities

sealed interface UiMatch {
    data class One(val node: UiNode) : UiMatch
    data class None(val candidates: List<UiNode>) : UiMatch
    data class Many(val matches: List<UiNode>) : UiMatch
}

/**
 * Finds the component a step addresses by name, painted text, class or XPath. Matching is strict: several
 * matches are an error unless the target picks one with `nth`. Refs are resolved by [UiRefRegistry], not here.
 */
object UiLocator {
    const val MAX_CANDIDATES = 5

    /** [roots] are the trees to search, topmost window first. [xpathMatches] answers an XPath over the model. */
    fun find(roots: List<UiNode>, target: UiTarget, xpathMatches: ((String) -> Set<Component>)? = null): UiMatch {
        val byXpath = target.xpath?.let { xpath ->
            requireNotNull(xpathMatches) { "an xpath target needs the remote-driver model, which is not available here" }(xpath)
        }
        val nodes = roots.flatMap { it.walk().toList() }.distinctBy { it.component }
        val matches = narrow(nodes.filter { node ->
            (target.name == null || node.name == target.name) &&
                (target.text == null || node.text.any { it.contains(target.text!!) } || node.name?.contains(target.text!!) == true) &&
                (target.cls == null || classMatches(node.component, target.cls!!)) &&
                (byXpath == null || node.component in byXpath)
        })
        val nth = target.nth
        return when {
            matches.isEmpty() -> UiMatch.None(candidates(nodes, target))
            nth != null -> matches.getOrNull(nth)?.let { UiMatch.One(it) } ?: UiMatch.None(matches.take(MAX_CANDIDATES))
            matches.size == 1 -> UiMatch.One(matches.single())
            else -> UiMatch.Many(matches)
        }
    }

    /**
     * Keeps the matches a user would mean. A label shares its field's accessible name (`labelFor`), so when some
     * matches are interactive the others go. A match inside another match, such as an editable combo box's editor
     * field, gives way to the outer one: the combo box is what a user selects from.
     */
    private fun narrow(matches: List<UiNode>): List<UiNode> {
        val interactive = matches.filter { it.interactive }
        val kept = interactive.ifEmpty { matches }
        return kept.filter { inner ->
            kept.none { outer -> outer !== inner && SwingUtilities.isDescendingFrom(inner.component, outer.component) }
        }
    }

    private fun classMatches(c: Component, cls: String): Boolean =
        generateSequence<Class<*>>(c.javaClass) { it.superclass }.any { it.simpleName == cls || it.name == cls } ||
            UiComponentFacts.simpleClassName(c) == cls

    /** The listed components most like the target: the same class first, then the closest name or text. */
    private fun candidates(nodes: List<UiNode>, target: UiTarget): List<UiNode> {
        val wanted = target.name ?: target.text
        return nodes.asSequence()
            .filter { it.listed }
            .filter { target.cls == null || classMatches(it.component, target.cls!!) || wanted != null }
            .map { node -> node to score(node, target, wanted) }
            .sortedBy { it.second }
            .take(MAX_CANDIDATES)
            .map { it.first }
            .toList()
    }

    private fun score(node: UiNode, target: UiTarget, wanted: String?): Int {
        val classPenalty = if (target.cls != null && !classMatches(node.component, target.cls!!)) 1000 else 0
        if (wanted == null) return classPenalty
        val labels = listOfNotNull(node.name) + node.text
        val distance = labels.minOfOrNull { distance(it.lowercase(), wanted.lowercase()) } ?: 500
        return classPenalty + distance
    }

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }
}
