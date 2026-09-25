/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ExecuteCodeToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ExecuteCodeToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_execute_code")
        assertRequiredExactly(schema, "project_name", "code", "reason", "task_id")
        assertStringProperty(schema, "project_name")
        assertStringProperty(schema, "code")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "reason")
        assertIntegerProperty(schema, "timeout")
        assertEnumProperty(schema, "modal", "smart_non_modal", "non_modal", "unleashed")
        val side = assertEnumProperty(schema, "side", "frontend", "backend")
        assertFalse("default" in side, "side has no schema default: routing picks the side when it is absent")
    }
}
