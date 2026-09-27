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
 * unlisted component with one child adds no line, so wrapper panels do not deepen the tree, and a leaf that
 * only repeats its parent's name (a tab's title, a separator's label) adds none either. A list, tree or table
 * lists its rows in view under its line, one per row, by index. The controls of a long scroll pane's content that
 * are scrolled out of view are counted on one line. Call on the EDT when [format] is asked for bounds.
 */
object UiSnapshotFormatter {
    private const val MAX_TEXT = 80
    private const val MAX_TEXT_ENTRIES = 8
    private const val MAX_ENTRY = 40

    /**
     * The most controls a scroll pane's content lists out of view. A short form scrolled one screen down stays listed
     * whole; a long list such as the installed plugins lists the part in view and counts the rest.
     */
    private const val MAX_OFFSCREEN = 30

    fun format(header: UiWindowHeader, root: UiNode, refOf: (UiNode) -> String, maxNodes: Int, withBounds: Boolean): UiSnapshotText {
        val out = StringBuilder()
        out.append("window ").append(header.windowId)
        header.title?.takeIf { it.isNotBlank() }?.let { out.append(" \"").append(it).append('"') }
        out.append(" (").append(header.kind).append(if (header.modal) ", modal" else "").append(')')
        out.append(" source=").append(header.source)
        header.note?.let { out.append("\nnote: ").append(it) }
        var listed = 0
        var cut = 0

        fun walk(node: UiNode, depth: Int, parent: UiNode?) {
            if (!node.listed && node.children.size <= 1) {
                node.children.forEach { walk(it, depth, parent) }
                return
            }
            if (parent != null && repeatsParent(node, parent)) return
            if (listed >= maxNodes) {
                cut += node.walk().count { it.listed || it.children.size > 1 }
                return
            }
            listed++
            out.append('\n').append("  ".repeat(depth)).append("- ").append(line(node, refOf, withBounds))
            node.rows?.let { rows(out, it, depth + 1) }
            val hidden = node.children.filter { it.offscreen != null }
            val hiddenListed = hidden.sumOf { kid -> kid.walk().count { it.listed } }
            if (hiddenListed <= MAX_OFFSCREEN) {
                node.children.forEach { walk(it, depth + 1, node) }
            } else {
                node.children.filter { it.offscreen == null }.forEach { walk(it, depth + 1, node) }
                val above = hidden.filter { it.offscreen == UiOffscreen.ABOVE }.sumOf { kid -> kid.walk().count { it.listed } }
                val sides = listOfNotNull(above.takeIf { it > 0 }?.let { "$it above" }, (hiddenListed - above).takeIf { it > 0 }?.let { "$it below" })
                out.append('\n').append("  ".repeat(depth + 1)).append("- … ").append(sides.joinToString(" and "))
                    .append(" scrolled out of view: a scroll step with \"pages\" lists them, and steps find them by name or text")
            }
        }

        root.children.forEach { walk(it, 0, null) }
        if (cut > 0) out.append("\n… ").append(cut).append(" more (raise max_nodes or snapshot one window)")
        return UiSnapshotText(out.toString(), listed, cut)
    }

    /** A leaf that only shows its parent's name again, and that nothing acts on. */
    private fun repeatsParent(node: UiNode, parent: UiNode): Boolean {
        val shown = parent.name ?: return false
        if (node.interactive || node.children.isNotEmpty()) return false
        // A label cut to its width paints "4 spa..." for "4 spaces".
        val own = (listOfNotNull(node.name) + node.text).map { it.removeSuffix("...").removeSuffix("…") }
        return own.isNotEmpty() && own.all { it.isNotEmpty() && shown.startsWith(it) }
    }

    private fun rows(out: StringBuilder, view: UiRowsView, depth: Int) {
        val first = view.rows.firstOrNull()?.index ?: return
        val last = view.rows.last().index
        if (first > 0 || last < view.total - 1) {
            out.append('\n').append("  ".repeat(depth)).append("rows ").append(first).append('-').append(last)
                .append(" of ").append(view.total).append(" in view; select takes any row by text or index")
        }
        for (row in view.rows) {
            out.append('\n').append("  ".repeat(depth + row.depth)).append('#').append(row.index).append(' ').append(cut(row.text, MAX_TEXT))
            if (row.cells.any { it.isNotBlank() }) out.append(" | ").append(cut(row.cells.joinToString(" | "), MAX_TEXT))
            when (row.expanded) {
                true -> out.append(" [expanded]")
                false -> out.append(" [collapsed]")
                null -> Unit
            }
            if (row.selected) out.append(" [selected]")
        }
    }

    private fun line(node: UiNode, refOf: (UiNode) -> String, withBounds: Boolean): String = buildString {
        append(node.className)
        node.name?.let { append(" \"").append(cut(it, MAX_TEXT)).append('"') }
        node.label?.let { append(" label=\"").append(cut(it, MAX_TEXT)).append('"') }
        if (node.listed) append(" [ref=").append(refOf(node)).append(']')
        node.action?.let { append(" action=").append(it) }
        node.states.sortedBy { it.ordinal }.forEach { append(" [").append(it.label).append(']') }
        node.value?.let { append(" value=\"").append(cut(it, MAX_TEXT)).append('"') }
        // The rows are listed under the line, so the painted text would only repeat them.
        val texts = if (node.rows != null) emptyList() else node.text.filter { it != node.name && it != node.value }
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
