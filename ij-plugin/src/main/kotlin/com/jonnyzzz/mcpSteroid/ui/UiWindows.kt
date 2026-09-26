/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Window

/** Which windows belong to a project frame, in the order a snapshot lists them. */
object UiWindows {
    /**
     * [all] in creation order, as `Window.getWindows()` returns them. The windows owned by [frame], directly or
     * through another owned window, newest first, then the frame.
     */
    fun <W : Any> order(frame: W, all: List<W>, ownerOf: (W) -> W?): List<W> {
        val owned = all.filter { w -> w !== frame && generateSequence(ownerOf(w), ownerOf).any { it === frame } }
        return owned.reversed() + frame
    }

    /** The showing windows of [frame]: its dialogs and heavyweight popups, topmost first, then the frame. Call on the EDT. */
    fun projectWindows(frame: Window): List<Window> =
        order(frame, Window.getWindows().filter { it.isShowing }, Window::getOwner)
}
