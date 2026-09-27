/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.IdeBundle
import com.intellij.openapi.diagnostic.logger
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
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/** Facts read from a live component. Call on the EDT. */
object UiComponentFacts {
    private val log = logger<UiComponentFacts>()
    private const val MAX_VALUE = 200
    private const val CLIENT_PROJECT_VIEW_TREE = "ThinClientProjectViewTree"

    /** Simple class names that mark a component as clickable although it is not a Swing button. */
    private val CLICKABLE_CLASS_NAMES = setOf("ActionButton", "TabLabel", "LinkLabel", "ActionLink", "HyperlinkLabel")

    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    fun simpleClassName(c: Component): String {
        val type = if (c.javaClass.isAnonymousClass) c.javaClass.superclass else c.javaClass
        return simpleName(type.name).ifEmpty { type.simpleName }
    }

    /**
     * The name a snapshot shows for a class or model name: without package and outer classes, and without the
     * number the JVM gives a local class (`Outer$1MyTextField` is `MyTextField`).
     */
    fun simpleName(name: String): String = name.substringAfterLast('.').substringAfterLast('$').trimStart { it.isDigit() }

    fun interactive(c: Component): Boolean = when (c) {
        is AbstractButton, is JTextComponent, is JList<*>, is JTree, is JTable, is JComboBox<*>, is JSlider, is JSpinner, is JTabbedPane -> true
        else -> generateSequence<Class<*>>(c.javaClass) { it.superclass }.any { it.simpleName in CLICKABLE_CLASS_NAMES }
    }

    /** Where a showing control lies that its scroll pane keeps out of view, or null when part of it shows. EDT. */
    fun offscreen(c: Component): UiOffscreen? {
        if (c !is JComponent || !c.isShowing || !c.visibleRect.isEmpty) return null
        val port = SwingUtilities.getAncestorOfClass(JViewport::class.java, c) as? JViewport ?: return null
        val view = port.view ?: return null
        val at = SwingUtilities.convertRectangle(c.parent, c.bounds, view)
        return if (at.y + at.height <= port.viewPosition.y) UiOffscreen.ABOVE else UiOffscreen.BELOW
    }

    fun name(c: Component): String? = c.accessibleContext?.accessibleName?.let(::clean)?.takeIf { it.isNotEmpty() } ?: fallbackName(c)

    /**
     * The name a regular IDE gives a control that a JetBrains Client leaves unnamed, so that one step finds it in
     * both: the Client's Project view tree is the regular IDE's "Project structure tree".
     */
    fun fallbackName(c: Component): String? = when (c.javaClass.simpleName) {
        CLIENT_PROJECT_VIEW_TREE -> IdeBundle.message("project.structure.tree.accessible.name")
        else -> null
    }

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
        is JComboBox<*> -> comboText(c)?.take(MAX_VALUE)
        else -> null
    }

    fun states(c: Component): Set<UiState> = buildSet {
        if (!c.isEnabled) add(UiState.DISABLED)
        if (c is JToggleButton && c.isSelected) add(UiState.CHECKED)
        if (c is JTextComponent && c.isEditable) add(UiState.EDITABLE)
        if (c is JButton && c.isDefaultButton) add(UiState.DEFAULT)
        if (c === KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner) add(UiState.FOCUSED)
    }

    /** The selected item as the combo box shows it, through its renderer, else its text. */
    @Suppress("UNCHECKED_CAST")
    private fun comboText(c: JComboBox<*>): String? {
        val item = c.selectedItem ?: return null
        val renderer = (c as JComboBox<Any?>).renderer
        val shown = try {
            renderer?.getListCellRendererComponent(JList<Any?>(), item, -1, false, false)?.let(UiRows::text)
        } catch (e: RuntimeException) {
            // A renderer may expect the combo box's own popup list; its item text still serves.
            log.debug("The renderer of ${c.javaClass.name} failed outside its popup", e)
            null
        }
        return shown ?: clean(item.toString()).takeIf { it.isNotEmpty() }
    }

    /** HTML tags removed, character references such as `&#39;` and `&amp;` decoded, whitespace collapsed. */
    fun clean(raw: String): String = raw.replace(TAGS, " ").replace(ENTITY, ::decode).replace(SPACES, " ").trim()

    private fun decode(m: MatchResult): String = when (val e = m.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        else -> {
            val code = if (e.startsWith("#x", ignoreCase = true)) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
            code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
        }
    }
}
