/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.text.JTextComponent

/** Facts read from a live component. Call on the EDT. */
object UiComponentFacts {
    private const val MAX_VALUE = 200

    /** Simple class names that mark a component as clickable although it is not a Swing button. */
    private val CLICKABLE_CLASS_NAMES = setOf("ActionButton", "TabLabel", "LinkLabel", "ActionLink", "HyperlinkLabel")

    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")

    fun simpleClassName(c: Component): String {
        val type = if (c.javaClass.isAnonymousClass) c.javaClass.superclass else c.javaClass
        return type.name.substringAfterLast('.').substringAfterLast('$')
    }

    fun interactive(c: Component): Boolean = when (c) {
        is AbstractButton, is JTextComponent, is JList<*>, is JTree, is JTable, is JComboBox<*>, is JSlider, is JSpinner -> true
        else -> generateSequence<Class<*>>(c.javaClass) { it.superclass }.any { it.simpleName in CLICKABLE_CLASS_NAMES }
    }

    fun name(c: Component): String? = c.accessibleContext?.accessibleName?.let(::clean)?.takeIf { it.isNotEmpty() }

    fun ownText(c: Component): String? {
        val raw = when (c) {
            is JLabel -> c.text
            is AbstractButton -> c.text
            else -> null
        } ?: return null
        return clean(raw).takeIf { it.isNotEmpty() }
    }

    fun tooltip(c: Component): String? = (c as? JComponent)?.toolTipText?.let(::clean)?.takeIf { it.isNotEmpty() }

    fun value(c: Component): String? = when (c) {
        is JTextComponent -> clean(c.text).take(MAX_VALUE)
        is JComboBox<*> -> c.selectedItem?.toString()?.let { clean(it).take(MAX_VALUE) }
        else -> null
    }

    fun states(c: Component): Set<UiState> = buildSet {
        if (!c.isEnabled) add(UiState.DISABLED)
        if (c is JToggleButton && c.isSelected) add(UiState.CHECKED)
        if (c is JTextComponent && c.isEditable) add(UiState.EDITABLE)
        if (c is JButton && c.isDefaultButton) add(UiState.DEFAULT)
        if (c === KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner) add(UiState.FOCUSED)
    }

    /** HTML tags removed, whitespace collapsed. */
    fun clean(raw: String): String = raw.replace(TAGS, " ").replace(SPACES, " ").trim()
}
