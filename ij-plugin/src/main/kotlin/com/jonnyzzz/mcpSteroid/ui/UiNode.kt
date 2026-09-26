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
) {
    /** Listed in a snapshot: it shows something, or an agent can act on it. */
    val listed: Boolean get() = interactive || !name.isNullOrBlank() || text.isNotEmpty() || !tooltip.isNullOrBlank()

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.walk()) }
    }
}

enum class UiState(val label: String) {
    DISABLED("disabled"),
    CHECKED("checked"),
    SELECTED("selected"),
    FOCUSED("focused"),
    EXPANDED("expanded"),
    EDITABLE("editable"),
    DEFAULT("default"),
}
