/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.vision

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.ui.UiModel
import com.jonnyzzz.mcpSteroid.ui.UiRefs
import com.jonnyzzz.mcpSteroid.ui.UiSnapshotFormatter
import com.jonnyzzz.mcpSteroid.ui.UiWindowHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * The screenshot's component tree: the steroid_ui snapshot of the captured window, with refs and screen bounds.
 * The refs are the ones steroid_ui and `ui.*` accept.
 */
class SwingComponentTreeProvider : ScreenshotMetadataProvider {

    override val type: String = TYPE

    override suspend fun provide(context: ScreenCaptureContext): ProviderResult {
        val tree = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { snapshotText(context.component) }
        return ProviderResult.Success(
            ScreenshotMetadata(
                type = TYPE,
                fileName = FILE_NAME,
                content = tree,
                mimeType = "text/markdown",
            )
        )
    }

    companion object {
        const val TYPE = "swing-tree"
        const val FILE_NAME = "screenshot-tree.md"
        private const val MAX_NODES = 2_000

        /** The snapshot text of [component]'s window. Call on the EDT. */
        fun snapshotText(component: Component): String {
            val window = component as? Window ?: SwingUtilities.getWindowAncestor(component)
            val root = window ?: component
            val model = UiModel.build(root)
            val registry = service<UiRefs>().registry
            val header = UiWindowHeader(
                windowId = WindowIdUtil.compute(window, component),
                title = (window as? Frame)?.title ?: (window as? Dialog)?.title,
                kind = when (window) {
                    is Frame -> "frame"
                    is Dialog -> "dialog"
                    else -> "popup"
                },
                modal = (window as? Dialog)?.isModal == true,
                source = model.source,
                note = model.note,
            )
            return UiSnapshotFormatter.format(header, model.root, { registry.refFor(it.component) }, MAX_NODES, withBounds = true).text
        }
    }
}
