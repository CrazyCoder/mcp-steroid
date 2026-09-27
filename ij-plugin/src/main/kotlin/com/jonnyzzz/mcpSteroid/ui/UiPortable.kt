/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Component
import javax.swing.JTree

/**
 * Rewrites a step that ran into one that replays in another IDE session. A ref such as `e12` lives only while its
 * control shows, so a recorded step names the control by what a user sees instead: its name, its caption, its text,
 * its class, in that order, as Playwright prefers a role and name to a CSS path. A row index becomes the row's text.
 */
object UiPortable {
    private val TARGET_KEYS = setOf("ref", "name", "text", "class", "xpath", "nth")

    /**
     * The simplest target that finds [node] alone among [roots], the windows a step searches, topmost first; else the
     * first one that finds it among others, with `nth`. [textIsInput] leaves out text, which type and fill enter. EDT.
     */
    fun stableTarget(node: UiNode, roots: List<UiNode>, textIsInput: Boolean = false): UiTarget {
        val c = node.component
        val cls = node.className
        val candidates = buildList {
            node.name?.takeIf { it.isNotBlank() }?.let { add(UiTarget(name = it)); add(UiTarget(name = it, cls = cls)) }
            // An unnamed field answers to its caption as its name.
            if (node.name == null) node.label?.takeIf { it.isNotBlank() }?.let { add(UiTarget(name = it)); add(UiTarget(name = it, cls = cls)) }
            if (!textIsInput) node.text.firstOrNull { it.isNotBlank() }?.let { add(UiTarget(text = it)); add(UiTarget(text = it, cls = cls)) }
            add(UiTarget(cls = cls))
        }
        var fallback: UiTarget? = null
        for (candidate in candidates) {
            when (val m = UiLocator.find(roots, candidate)) {
                is UiMatch.One -> if (m.node.component === c) return candidate
                is UiMatch.Many -> if (fallback == null) {
                    val nth = m.matches.indexOfFirst { it.component === c }
                    if (nth >= 0) fallback = candidate.copy(nth = nth)
                }
                is UiMatch.None -> Unit
            }
        }
        return fallback ?: UiTarget(cls = cls)
    }

    /**
     * The row text that names row [index] of [c] for a replay: its text when that finds it alone, else its tree
     * path, else null, and the index stays. EDT.
     */
    fun stableRow(c: Component, rows: List<String>, index: Int): String? {
        val text = rows.getOrNull(index) ?: return null
        fun finds(wanted: String) = runCatching { UiRows.find(c, rows, wanted) }.getOrNull() == index
        if (text.isNotBlank() && finds(text)) return text
        if (c is JTree) UiRows.treePath(c, index).takeIf { UiRows.PATH_SEPARATOR in it && finds(it) }?.let { return it }
        return null
    }

    /**
     * [source], the step as written, with its target and row replaced by the portable ones given, and [fields] such
     * as a Settings page id or a full option name put over the ones written. With [textIsInput], "text" is what the
     * step enters and stays.
     */
    fun rewrite(source: JsonObject, target: UiTarget?, row: String?, fields: Map<String, String> = emptyMap(), textIsInput: Boolean = false): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(source)
        if (target != null) {
            TARGET_KEYS.filter { !(textIsInput && it == "text") }.forEach(out::remove)
            target.name?.let { out["name"] = JsonPrimitive(it) }
            target.text?.let { out["text"] = JsonPrimitive(it) }
            target.cls?.let { out["class"] = JsonPrimitive(it) }
            target.nth?.let { out["nth"] = JsonPrimitive(it) }
        }
        if (row != null) {
            out.remove("index")
            out["row"] = JsonPrimitive(row)
        }
        fields.forEach { (k, v) -> out[k] = JsonPrimitive(v) }
        // Keep "action" first, as steps are written.
        val action = out.remove("action")
        return JsonObject(if (action == null) out else linkedMapOf("action" to action) + out)
    }
}
