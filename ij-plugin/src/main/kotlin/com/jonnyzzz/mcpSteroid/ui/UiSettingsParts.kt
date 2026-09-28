/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ui.components.breadcrumbs.Breadcrumbs
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.Window

/**
 * The parts of the Settings window a picture names: the breadcrumb bar above the page, which shows the page's path,
 * and the page, the breadcrumb bar with the editor under it. All bounds are on screen.
 */
object UiSettingsParts {
    /** The breadcrumb bar of the Settings page [window] shows, or null when it shows none. EDT. */
    fun breadcrumbs(window: Window): Breadcrumbs? =
        UIUtil.uiTraverser(window).filter(Breadcrumbs::class.java).firstOrNull { it.isShowing && it.crumbs.iterator().hasNext() }

    /** The screen area of [bar]'s crumbs: the bar is as wide as the page, its crumbs only as wide as their text. EDT. */
    fun crumbsBounds(bar: Breadcrumbs): Rectangle =
        Rectangle(bar.locationOnScreen, java.awt.Dimension(minOf(bar.width, bar.preferredSize.width), bar.height))

    /** The screen area of the page [window] shows, with its breadcrumb bar, or null when it shows none. EDT. */
    fun page(window: Window): Rectangle? {
        val bar = breadcrumbs(window) ?: return null
        val panel = generateSequence<Component>(bar) { it.parent }.filterIsInstance<Container>().firstOrNull { holdsEditor(it) } ?: return null
        return Rectangle(panel.locationOnScreen, panel.size)
    }

    /**
     * Whether [window] shows a page of the Remote Development backend in a JetBrains Client: Lux draws it from the
     * backend's components, so the Client holds a Lux panel in their place. EDT.
     */
    fun hostPage(window: Window): Boolean = UIUtil.uiTraverser(window).any { it.isShowing && it.javaClass.simpleName.startsWith(LUX) }

    private const val LUX = "Lux"

    /** Whether [c] holds the Settings page editor, whose class is `ConfigurableEditor` or a subclass of it. EDT. */
    private fun holdsEditor(c: Container): Boolean = UIUtil.uiTraverser(c).any { child ->
        child.isShowing && generateSequence<Class<*>>(child.javaClass) { it.superclass }.any { it.simpleName == EDITOR_CLASS }
    }

    private const val EDITOR_CLASS = "ConfigurableEditor"
}
