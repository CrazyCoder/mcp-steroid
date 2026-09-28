/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextFieldCellRenderer
import com.intellij.ui.table.JBTable
import javax.swing.table.DefaultTableModel

class UiRowsEditorRendererTest : BasePlatformTestCase() {
    /**
     * A table drawn by an editor-based renderer that sets its text only when painted, as Change Signature's
     * parameter table is: each row reads as its own text, not the value behind it or the row painted last.
     */
    fun `test rows of an editor-based renderer read their own text`() {
        val renderer = object : EditorTextFieldCellRenderer(project, null as com.intellij.lang.Language?, testRootDisposable) {
            override fun getText(table: javax.swing.JTable, value: Any?, row: Int, column: Int) = "param$row: Int"
        }
        val table = JBTable(DefaultTableModel(Array(3) { arrayOf<Any>("value") }, arrayOf("name"))).apply {
            setDefaultRenderer(Any::class.java, renderer)
            setSize(400, 90)
            doLayout()
        }
        assertEquals(listOf("param0: Int", "param1: Int", "param2: Int"), UiRows.rows(table))
    }
}
