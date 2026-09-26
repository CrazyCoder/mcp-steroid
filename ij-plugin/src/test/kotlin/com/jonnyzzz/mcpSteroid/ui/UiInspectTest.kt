/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiInspectTest {
    @Test
    fun `the creator is the first frames after the toolkit and the component-adding code`() {
        val frames = listOf(
            "at com.intellij.internal.inspector.AddedAtStacktracesCollector.eventDispatched(UiInspectorUtil.kt:94)",
            "at java.desktop/java.awt.Container.addImpl(Container.java:1152)",
            "at com.intellij.ui.ScrollPaneFactory.createScrollPane(ScrollPaneFactory.java:25)",
            "at com.intellij.ui.popup.AbstractPopup.showImpl(AbstractPopup.java:1471)",
            "at com.intellij.platform.searchEverywhere.frontend.SeFrontendService.calcPopupPositionAndShow(SeFrontendService.kt:453)",
            "at com.intellij.platform.searchEverywhere.frontend.SeFrontendService.createAndShowIdlePopup(SeFrontendService.kt:377)",
            "at com.intellij.openapi.wm.impl.IdeFrameImpl.dispatch(IdeFrameImpl.kt:10)",
            "at com.intellij.ide.actions.GotoActionAction.actionPerformed(GotoActionAction.kt:40)",
            "at com.example.Later.frame(Later.kt:1)",
        )
        assertEquals(
            "com.intellij.platform.searchEverywhere.frontend.SeFrontendService.calcPopupPositionAndShow(SeFrontendService.kt:453) < " +
                "com.intellij.platform.searchEverywhere.frontend.SeFrontendService.createAndShowIdlePopup(SeFrontendService.kt:377) < " +
                "com.intellij.ide.actions.GotoActionAction.actionPerformed(GotoActionAction.kt:40)",
            UiInspect.creatorFrames(frames),
        )
    }

    @Test
    fun `a trace of toolkit frames only names no creator`() {
        assertNull(UiInspect.creatorFrames(listOf("at java.desktop/java.awt.Container.addImpl(Container.java:1152)", "at javax.swing.JComponent.add(JComponent.java:1)")))
    }
}
