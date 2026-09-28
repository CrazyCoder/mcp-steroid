/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.scale.JBUIScale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Window

/**
 * What makes two pictures of the same window state differ, recorded with a screenshot step's picture: the window's
 * size, the screen's scale and the IDE's own zoom, the theme, the editor font, the IDE build and the OS.
 */
internal class UiPictureFacts(
    val width: Int,
    val height: Int,
    val screenScale: Float,
    val ideScale: Float,
    val theme: String?,
    val editorFont: String,
    val build: String,
    val os: String,
) {
    fun describe(): String = "scale ${screenScale}x screen, ${ideScale}x IDE; theme ${theme ?: "unknown"}; editor font $editorFont"

    fun json(): String = buildJsonObject {
        put("width", width)
        put("height", height)
        put("screen_scale", screenScale)
        put("ide_scale", ideScale)
        theme?.let { put("theme", it) }
        put("editor_font", editorFont)
        put("build", build)
        put("os", os)
    }.toString()

    companion object {
        /** EDT. */
        fun of(window: Window): UiPictureFacts {
            val scheme = EditorColorsManager.getInstance().globalScheme
            return UiPictureFacts(
                width = window.width,
                height = window.height,
                screenScale = JBUIScale.sysScale(window),
                ideScale = JBUIScale.scale(1f),
                theme = LafManager.getInstance().currentUIThemeLookAndFeel?.name,
                editorFont = "${scheme.editorFontName} ${scheme.editorFontSize}",
                build = ApplicationInfo.getInstance().build.asString(),
                os = "${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION}",
            )
        }
    }
}
