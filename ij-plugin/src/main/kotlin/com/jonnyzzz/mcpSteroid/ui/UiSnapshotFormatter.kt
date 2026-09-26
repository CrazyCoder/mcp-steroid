/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

data class UiWindowHeader(
    val windowId: String,
    val title: String?,
    val kind: String,
    val modal: Boolean,
    val source: String,
    val note: String?,
)

data class UiSnapshotText(val text: String, val listedCount: Int, val cut: Int)

/**
 * The snapshot text: a header per window, then one line per listed component, indented by depth. An
 * unlisted component with one child adds no line, so wrapper panels do not deepen the tree. Call on the
 * EDT when [format] is asked for bounds.
 */
object UiSnapshotFormatter {
    private const val MAX_TEXT = 80
    private const val MAX_TEXT_ENTRIES = 8
    private const val MAX_ENTRY = 40

    fun format(header: UiWindowHeader, root: UiNode, refOf: (UiNode) -> String, maxNodes: Int, withBounds: Boolean): UiSnapshotText {
        val out = StringBuilder()
        out.append("window ").append(header.windowId)
        header.title?.takeIf { it.isNotBlank() }?.let { out.append(" \"").append(it).append('"') }
        out.append(" (").append(header.kind).append(if (header.modal) ", modal" else "").append(')')
        out.append(" source=").append(header.source)
        header.note?.let { out.append("\nnote: ").append(it) }
        var listed = 0
        var cut = 0

        fun walk(node: UiNode, depth: Int) {
            if (!node.listed && node.children.size <= 1) {
                node.children.forEach { walk(it, depth) }
                return
            }
            if (listed >= maxNodes) {
                cut += node.walk().count { it.listed || it.children.size > 1 }
                return
            }
            listed++
            out.append('\n').append("  ".repeat(depth)).append("- ").append(line(node, refOf, withBounds))
            node.children.forEach { walk(it, depth + 1) }
        }

        root.children.forEach { walk(it, 0) }
        if (cut > 0) out.append("\n… ").append(cut).append(" more (raise max_nodes or snapshot one window)")
        return UiSnapshotText(out.toString(), listed, cut)
    }

    private fun line(node: UiNode, refOf: (UiNode) -> String, withBounds: Boolean): String = buildString {
        append(node.className)
        node.name?.let { append(" \"").append(cut(it, MAX_TEXT)).append('"') }
        if (node.listed) append(" [ref=").append(refOf(node)).append(']')
        node.states.sortedBy { it.ordinal }.forEach { append(" [").append(it.label).append(']') }
        node.value?.let { append(" value=\"").append(cut(it, MAX_TEXT)).append('"') }
        val texts = node.text.filter { it != node.name }
        if (texts.isNotEmpty()) {
            append(" text=").append(texts.take(MAX_TEXT_ENTRIES).joinToString("|") { cut(it, MAX_ENTRY) })
            if (texts.size > MAX_TEXT_ENTRIES) append("|+").append(texts.size - MAX_TEXT_ENTRIES)
        }
        node.tooltip?.takeIf { it != node.name }?.let { append(" tip=\"").append(cut(it, MAX_TEXT)).append('"') }
        if (withBounds && node.component.isShowing) {
            val p = node.component.locationOnScreen
            append(" @").append(p.x).append(',').append(p.y).append(' ')
                .append(node.component.width).append('x').append(node.component.height)
        }
    }

    private fun cut(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "…"
}
