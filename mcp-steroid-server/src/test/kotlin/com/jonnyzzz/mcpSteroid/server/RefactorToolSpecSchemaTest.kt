/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class RefactorToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = RefactorToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_refactor")
        assertRequiredExactly(schema, "project_name", "task_id", "reason", "op")
        assertEnumProperty(schema, "op", "rename", "safe_delete", "move", "fix", "intention", "optimize_imports", "reformat", "usages")
        listOf("file", "symbol", "new_name", "to", "inspection", "name").forEach { assertStringProperty(schema, it) }
        listOf("line", "column", "nth").forEach { assertIntegerProperty(schema, it) }
        assertBooleanProperty(schema, "all")
        assertBooleanProperty(schema, "apply")
    }
}
