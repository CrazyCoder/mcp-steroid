/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Component

/** One component of a UI snapshot, reduced to what an agent reads and what a step needs to find it. */
data class UiNode(
    val component: Component,
    val className: String,
    val name: String?,
    val text: List<String>,
    val tooltip: String?,
    val value: String?,
    val states: Set<UiState>,
    val interactive: Boolean,
    val children: List<UiNode>,
    /** The rows in view, for a list, tree or table. */
    val rows: UiRowsView? = null,
    /** The text of the label or checkbox just before an unnamed field, which a user reads as its caption. */
    val label: String? = null,
    /** The id of the IDE action behind a toolbar button or menu item, which a run step takes. */
    val action: String? = null,
    /** Where a scroll pane keeps it out of view, or null when it is in view. */
    val offscreen: UiOffscreen? = null,
    /** How much of it its panels and window cut, or null when it shows whole. */
    val clip: UiClip? = null,
    /** For a list, tree or table wider than its scroll pane shows: how much, as its rows are cut at the right. */
    val rowsCut: String? = null,
) {
    /** Listed in a snapshot: it shows something, or an agent can act on it. */
    val listed: Boolean get() = interactive || !name.isNullOrBlank() || text.isNotEmpty() || !tooltip.isNullOrBlank()

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.walk()) }
    }
}

/** Which way a control scrolled out of view lies: above the part a scroll pane shows, or below or beside it. */
enum class UiOffscreen { ABOVE, BELOW }

enum class UiState(val label: String) {
    DISABLED("disabled"),
    CHECKED("checked"),
    FOCUSED("focused"),
    EDITABLE("editable"),
    DEFAULT("default"),
}
