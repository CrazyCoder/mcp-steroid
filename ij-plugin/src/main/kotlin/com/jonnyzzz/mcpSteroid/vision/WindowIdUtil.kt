/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import com.intellij.openapi.wm.WindowManager
import java.awt.Component
import java.awt.Window
import javax.swing.SwingUtilities

object WindowIdUtil {
    fun compute(window: Window?, component: Component): String {
        return if (window != null) {
            "w-" + Integer.toHexString(System.identityHashCode(window))
        } else {
            "c-" + Integer.toHexString(System.identityHashCode(component))
        }
    }
}

/**
 * Finds the component for a window id from steroid_list_windows: a project frame's root component, or any
 * other displayable window. Call on the EDT.
 */
fun findComponentByWindowId(windowId: String): Component? {
    for (frame in WindowManager.getInstance().allProjectFrames) {
        val component = frame.component
        val window = SwingUtilities.getWindowAncestor(component)
        if (WindowIdUtil.compute(window, component) == windowId) {
            return component
        }
    }
    for (window in Window.getWindows()) {
        if (!window.isDisplayable) continue
        if (WindowIdUtil.compute(window, window) == windowId) {
            return window
        }
    }
    return null
}
