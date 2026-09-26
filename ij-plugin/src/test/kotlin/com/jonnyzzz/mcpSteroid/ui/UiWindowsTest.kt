/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiWindowsTest {
    private class W(val id: String, val owner: W?)

    @Test
    fun `owned windows come first, newest first, and the frame last`() {
        val frame = W("frame", null)
        val other = W("other-frame", null)
        val dialog = W("dialog", frame)
        val popup = W("popup", dialog)
        val stray = W("stray", other)
        val ordered = UiWindows.order(listOf(frame), listOf(frame, other, dialog, stray, popup), ownerOf = { it.owner })
        assertEquals(listOf("popup", "dialog", "frame"), ordered.map { it.id })
    }

    @Test
    fun `a separate project window and its popups show over the frame`() {
        val frame = W("frame", null)
        val settings = W("settings", null)
        val frameDialog = W("frame-dialog", frame)
        val settingsPopup = W("settings-popup", settings)
        val ordered = UiWindows.order(listOf(frame, settings), listOf(frame, frameDialog, settings, settingsPopup), ownerOf = { it.owner })
        assertEquals(listOf("settings-popup", "settings", "frame-dialog", "frame"), ordered.map { it.id })
    }

    @Test
    fun `a frame with no owned windows is listed alone`() {
        val frame = W("frame", null)
        assertEquals(listOf("frame"), UiWindows.order(listOf(frame), listOf(frame), ownerOf = { it.owner }).map { it.id })
    }
}
