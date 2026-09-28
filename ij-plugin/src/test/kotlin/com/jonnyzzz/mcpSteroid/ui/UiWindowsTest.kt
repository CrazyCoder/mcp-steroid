/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `every kind of window a snapshot reports can be closed, except the project frame`() {
        for (kind in UiWindows.Kind.entries) {
            // The floating Settings window is such a dialog: no DialogWrapper, popup or menu holds it.
            assertEquals(kind.label, UiWindows.CloseWay.REQUEST_CLOSE, UiWindows.closeWay(kind, false, false, false, false))
        }
        assertNull(UiWindows.closeWay(UiWindows.Kind.FRAME, isProjectFrame = true, hasDialogWrapper = false, hasPopup = false, hasMenu = false))
    }

    @Test
    fun `the IDE's own cancel comes first, and a menu in the project frame closes alone`() {
        val dialog = UiWindows.Kind.DIALOG
        assertEquals(UiWindows.CloseWay.CANCEL_DIALOG, UiWindows.closeWay(dialog, false, hasDialogWrapper = true, hasPopup = true, hasMenu = true))
        assertEquals(UiWindows.CloseWay.CANCEL_POPUP, UiWindows.closeWay(UiWindows.Kind.POPUP, false, hasDialogWrapper = false, hasPopup = true, hasMenu = true))
        assertEquals(UiWindows.CloseWay.CLOSE_MENU, UiWindows.closeWay(UiWindows.Kind.FRAME, isProjectFrame = true, hasDialogWrapper = false, hasPopup = false, hasMenu = true))
    }

    @Test
    fun `a frame with no owned windows is listed alone`() {
        val frame = W("frame", null)
        assertEquals(listOf("frame"), UiWindows.order(listOf(frame), listOf(frame), ownerOf = { it.owner }).map { it.id })
    }
}
