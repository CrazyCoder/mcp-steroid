/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.localProjectNameFor
import com.jonnyzzz.mcpSteroid.server.projectNameFor

class SplitProjectKeysTest : BasePlatformTestCase() {
    private fun bridge(key: String?) = object : SplitFrontendBridge {
        override suspend fun forward(params: ToolCallParams, progress: McpProgressReporter) = ToolCallResult.successTextResult("")
        override suspend fun refreshProjectKeys() = Unit
        override fun backendKeyFor(project: Project): String? = key
        override suspend fun backendSelf(): BackendRef? = null
    }

    fun testBackendKeyWinsWhenTheBridgeKnowsTheProject() {
        assertEquals("backend-key", projectNameFor(project, bridge("backend-key")))
    }

    fun testUnknownProjectFallsBackToTheLocalKey() {
        assertEquals(localProjectNameFor(project), projectNameFor(project, bridge(null)))
    }

    fun testNoBridgeUsesTheLocalKey() {
        assertEquals(localProjectNameFor(project), projectNameFor(project, null))
    }

    // The frontend content module also loads in a monolith, so its bridge extension is registered there.
    fun testMonolithIgnoresARegisteredBridge() {
        ExtensionTestUtil.maskExtensions(SPLIT_FRONTEND_BRIDGE_EP, listOf(bridge("backend-key")), testRootDisposable)
        assertNull(activeSplitFrontendBridge())
        assertEquals(localProjectNameFor(project), projectNameFor(project))
    }
}
