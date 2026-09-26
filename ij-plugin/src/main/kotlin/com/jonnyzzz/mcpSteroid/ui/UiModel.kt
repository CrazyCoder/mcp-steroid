/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.diagnostic.logger
import java.awt.Component
import java.lang.reflect.InvocationTargetException

data class UiModelResult(val root: UiNode, val source: String, val note: String?)

/** Builds the snapshot tree of a root component. Call on the EDT. */
object UiModel {
    const val SOURCE_REMOTE_DRIVER = "remote-driver"
    const val SOURCE_SWING = "swing"

    private val log = logger<UiModel>()

    fun build(
        root: Component,
        onlyShowing: Boolean = true,
        remote: (Component) -> UiNode = RemoteDriverModel::build,
        remoteUnavailable: () -> String? = RemoteDriverModel::unavailableReason,
    ): UiModelResult {
        val unavailable = remoteUnavailable()
        if (unavailable != null) {
            return UiModelResult(FallbackUiWalker(onlyShowing).build(root), SOURCE_SWING, "remote driver unavailable: $unavailable")
        }
        return try {
            UiModelResult(remote(root), SOURCE_REMOTE_DRIVER, null)
        } catch (e: Exception) {
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            log.warn("The remote-driver UI model failed; using the Swing walker", cause)
            UiModelResult(FallbackUiWalker(onlyShowing).build(root), SOURCE_SWING, "remote driver failed: ${cause.message}")
        }
    }
}
