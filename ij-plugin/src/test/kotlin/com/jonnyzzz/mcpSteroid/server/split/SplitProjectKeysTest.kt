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
import com.jonnyzzz.mcpSteroid.server.projectPathFor

class SplitProjectKeysTest : BasePlatformTestCase() {
    private fun bridge(key: String?, path: String? = null) = object : SplitFrontendBridge {
        override suspend fun forward(params: ToolCallParams, progress: McpProgressReporter) = ToolCallResult.successTextResult("")
        override suspend fun refreshProjectKeys() = Unit
        override fun backendKeyFor(project: Project): String? = key
        override fun backendPathFor(project: Project): String? = path
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

    fun testBackendPathWinsWhenTheBridgeKnowsTheProject() {
        assertEquals("/backend/app", projectPathFor(project, bridge("backend-key", "/backend/app")))
    }

    fun testUnknownProjectFallsBackToTheLocalPath() {
        assertNotNull(project.basePath)
        assertEquals(project.basePath, projectPathFor(project, bridge(null)))
        assertEquals(project.basePath, projectPathFor(project, null))
    }

    // The frontend content module also loads in a monolith, so its bridge extension is registered there.
    fun testMonolithIgnoresARegisteredBridge() {
        ExtensionTestUtil.maskExtensions(SPLIT_FRONTEND_BRIDGE_EP, listOf(bridge("backend-key", "/backend/app")), testRootDisposable)
        assertNull(activeSplitFrontendBridge())
        assertEquals(localProjectNameFor(project), projectNameFor(project))
        assertEquals(project.basePath, projectPathFor(project))
    }
}
