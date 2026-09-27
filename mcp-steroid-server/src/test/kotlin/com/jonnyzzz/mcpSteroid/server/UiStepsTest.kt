/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UiStepsTest {
    private fun fails(json: String): String = assertThrows<IllegalArgumentException> { UiSteps.parse(json) }.message!!

    @Test
    fun `a flat click step parses into a step with a name target`() {
        val step = UiSteps.parse("""[{"action":"click","name":"OK"}]""").single()
        assertEquals(UiAction.CLICK, step.action)
        assertEquals(UiTarget(name = "OK"), step.target)
        assertEquals(UiSteps.DEFAULT_TIMEOUT_MS, step.timeoutMs)
    }

    @Test
    fun `every target field and nth are lifted into the target`() {
        val step = UiSteps.parse("""[{"action":"click","text":"Apply","class":"JButton","nth":1,"timeout_ms":900}]""").single()
        assertEquals(UiTarget(text = "Apply", cls = "JButton", nth = 1), step.target)
        assertEquals(900L, step.timeoutMs)
    }

    @Test
    fun `inspect takes a target and an optional row`() {
        val step = UiSteps.parse("""[{"action":"inspect","ref":"e4","row":"Editor"}]""").single()
        assertEquals(UiAction.INSPECT, step.action)
        assertEquals(UiTarget(ref = "e4"), step.target)
        assertEquals("Editor", step.row)
        assertTrue(fails("""[{"action":"inspect"}]""").contains("inspect needs a target"))
    }

    @Test
    fun `a row ref is split into its control's ref and the row index`() {
        val step = UiSteps.parse("""[{"action":"click","ref":"e91#9","count":2}]""").single()
        assertEquals(UiTarget(ref = "e91"), step.target)
        assertEquals(9, step.index)
        assertEquals(12, UiSteps.parse("""[{"action":"select","ref":"e3#12"}]""").single().index)
    }

    @Test
    fun `a row ref works only with row steps and names its row once`() {
        assertTrue(fails("""[{"action":"check","ref":"e91#9"}]""").contains("check acts on a whole control"))
        assertTrue(fails("""[{"action":"select","ref":"e91#9","index":2}]""").contains("already names row #9"))
    }

    @Test
    fun `row and index go only with row steps, one of them, from 0`() {
        assertTrue(fails("""[{"action":"type","ref":"e1","text":"x","index":1}]""").contains("not type"))
        assertEquals("Hard wrap at:", UiSteps.parse("""[{"action":"fill","ref":"e1","row":"Hard wrap at:","text":"90"}]""").single().row)
        assertTrue(fails("""[{"action":"select","ref":"e1","row":"a","index":1}]""").contains("not both"))
        assertTrue(fails("""[{"action":"click","ref":"e1","index":-1}]""").contains("0-based"))
        assertEquals("Java", UiSteps.parse("""[{"action":"hover","ref":"e1","row":"Java"}]""").single().row)
    }

    @Test
    fun `scroll takes a target and pages or a row`() {
        val step = UiSteps.parse("""[{"action":"scroll","ref":"e5","pages":-2}]""").single()
        assertEquals(UiAction.SCROLL, step.action)
        assertEquals(-2, step.pages)
        assertTrue(fails("""[{"action":"scroll","pages":1}]""").contains("scroll needs a target"))
        assertTrue(fails("""[{"action":"click","ref":"e5","pages":1}]""").contains("pages goes with scroll"))
        assertTrue(fails("""[{"action":"scroll","ref":"e5#3","pages":1}]""").contains("pages or a row"))
    }

    @Test
    fun `a ref target is kept as a ref`() {
        assertEquals(UiTarget(ref = "e12"), UiSteps.parse("""[{"action":"hover","ref":"e12"}]""").single().target)
    }

    @Test
    fun `an unknown action fails with its index`() {
        val message = fails("""[{"action":"click","name":"OK"},{"action":"tap","name":"X"}]""")
        assertTrue(message.contains("step 2") && message.contains("tap"), message)
    }

    @Test
    fun `an unknown field fails instead of being ignored`() {
        val message = fails("""[{"action":"click","nmae":"OK"}]""")
        assertTrue(message.contains("nmae"), message)
    }

    @Test
    fun `a click without a target fails`() {
        assertTrue(fails("""[{"action":"click"}]""").contains("needs a target"))
    }

    @Test
    fun `fill needs text and press needs keys`() {
        assertTrue(fails("""[{"action":"fill","name":"Search"}]""").contains("text"))
        assertTrue(fails("""[{"action":"press"}]""").contains("keys"))
    }

    @Test
    fun `select needs a row or an index`() {
        assertTrue(fails("""[{"action":"select","name":"Settings categories"}]""").contains("row"))
        assertEquals("Editor", UiSteps.parse("""[{"action":"select","name":"t","row":"Editor"}]""").single().row)
    }

    @Test
    fun `wait needs a known condition and what it waits for`() {
        assertTrue(fails("""[{"action":"wait","for":"soon"}]""").contains("soon"))
        assertTrue(fails("""[{"action":"wait","for":"visible"}]""").contains("needs a target"))
        assertTrue(fails("""[{"action":"wait","for":"window"}]""").contains("title"))
        val idle = UiSteps.parse("""[{"action":"wait","for":"idle"}]""").single()
        assertEquals(UiWaitCondition.IDLE, idle.condition)
        assertNull(idle.target)
    }

    @Test
    fun `type and close take an optional target`() {
        val steps = UiSteps.parse("""[{"action":"type","text":"abc"},{"action":"close"}]""")
        assertNull(steps[0].target)
        assertNull(steps[1].target)
    }

    @Test
    fun `click options are read`() {
        val step = UiSteps.parse("""[{"action":"click","name":"row","button":"right","count":2,"modifiers":"ctrl+shift","offset_x":3,"offset_y":4}]""").single()
        assertEquals("right", step.button)
        assertEquals(2, step.count)
        assertEquals("ctrl+shift", step.modifiers)
        assertEquals(3, step.offsetX)
        assertEquals(4, step.offsetY)
    }

    @Test
    fun `a bad button or count fails`() {
        assertTrue(fails("""[{"action":"click","name":"a","button":"side"}]""").contains("button"))
        assertTrue(fails("""[{"action":"click","name":"a","count":3}]""").contains("count"))
    }

    @Test
    fun `goto reads its file and one locator, and its text is a snippet, not a target`() {
        val bySymbol = UiSteps.parse("""[{"action":"goto","file":"src/A.kt","symbol":"foo","nth":1}]""").single()
        assertEquals(UiAction.GOTO, bySymbol.action)
        assertEquals("src/A.kt", bySymbol.file)
        assertEquals("foo", bySymbol.symbol)
        assertEquals(1, bySymbol.nth)
        val byText = UiSteps.parse("""[{"action":"goto","file":"A.kt","text":"a + b"}]""").single()
        assertEquals("a + b", byText.text)
        assertNull(byText.target)
        val byLine = UiSteps.parse("""[{"action":"goto","file":"A.kt","line":3,"column":7}]""").single()
        assertEquals(3, byLine.line)
        assertEquals(7, byLine.column)
    }

    @Test
    fun `goto needs a file and exactly one locator`() {
        assertTrue(fails("""[{"action":"goto","symbol":"foo"}]""").contains("needs a file"))
        assertTrue(fails("""[{"action":"goto","file":"A.kt"}]""").contains("exactly one"))
        assertTrue(fails("""[{"action":"goto","file":"A.kt","line":1,"symbol":"foo"}]""").contains("exactly one"))
        assertTrue(fails("""[{"action":"goto","file":"A.kt","line":0}]""").contains("1-based"))
    }

    @Test
    fun `run needs an action id`() {
        assertEquals("RenameElement", UiSteps.parse("""[{"action":"run","id":"RenameElement"}]""").single().id)
        assertTrue(fails("""[{"action":"run"}]""").contains("action id"))
    }

    @Test
    fun `expect reads its subject, check and flags`() {
        val step = UiSteps.parse("""[{"action":"expect","name":"Size:","value":"12.0","not":true,"soft":true,"intent":"font size kept"}]""").single()
        assertEquals(UiAction.EXPECT, step.action)
        assertEquals(UiTarget(name = "Size:"), step.target)
        assertEquals("12.0", step.value)
        assertTrue(step.negate && step.soft)
        assertEquals("font size kept", step.intent)
        val state = UiSteps.parse("""[{"action":"expect","name":"Show line numbers","is":"checked"}]""").single()
        assertEquals(UiExpectState.CHECKED, state.state)
        val count = UiSteps.parse("""[{"action":"expect","class":"JButton","count":0}]""").single()
        assertEquals(0, count.expectCount)
        assertEquals(1, count.count)
    }

    @Test
    fun `expect takes exactly one subject`() {
        assertTrue(fails("""[{"action":"expect"}]""").contains("needs one subject"))
        assertTrue(fails("""[{"action":"expect","name":"OK","title":"Settings"}]""").contains("not a target and title"))
        assertEquals("Settings", UiSteps.parse("""[{"action":"expect","title":"Settings","is":"hidden"}]""").single().title)
        assertEquals("", UiSteps.parse("""[{"action":"expect","error":"","not":true}]""").single().error)
        assertEquals("Indexing", UiSteps.parse("""[{"action":"expect","notification":"Indexing"}]""").single().notification)
        assertEquals("Module JDK", UiSteps.parse("""[{"action":"expect","banner":"Module JDK","not":true}]""").single().banner)
        assertTrue(fails("""[{"action":"expect","banner":"x","error":"y"}]""").contains("not banner and error"))
        assertTrue(fails("""[{"action":"click","name":"OK","banner":"x"}]""").contains("go(es) with expect"))
    }

    @Test
    fun `expect checks fit their subject`() {
        assertTrue(fails("""[{"action":"expect","name":"t","is":"selected"}]""").contains("add row or index"))
        assertTrue(fails("""[{"action":"expect","name":"t","row":"Editor","is":"checked"}]""").contains("row states"))
        assertTrue(fails("""[{"action":"expect","name":"t","count":2,"is":"visible"}]""").contains("pass it alone"))
        assertTrue(fails("""[{"action":"expect","name":"t","value":"a","contains":"b"}]""").contains("one of value"))
        assertTrue(fails("""[{"action":"expect","name":"t","matches":"("}]""").contains("regular expression"))
        assertTrue(fails("""[{"action":"expect","title":"S","value":"x"}]""").contains("is=visible or is=hidden only"))
        assertTrue(fails("""[{"action":"expect","file":"A.kt"}]""").contains("needs value, contains, matches or caret"))
        assertTrue(fails("""[{"action":"expect","file":"A.kt","caret":"3"}]""").contains("line:column"))
        assertTrue(fails("""[{"action":"expect","error":"NPE","is":"visible"}]""").contains("text alone"))
        val file = UiSteps.parse("""[{"action":"expect","file":"A.kt","line":3,"contains":"foo"}]""").single()
        assertEquals(3, file.line)
        assertEquals("foo", file.contains)
        assertEquals(3, UiSteps.parse("""[{"action":"expect","ref":"e4#3","is":"selected"}]""").single().index)
        val cell = UiSteps.parse("""[{"action":"expect","name":"t","row":"Hard wrap at:","value":"90"}]""").single()
        assertEquals("90", cell.value)
        assertTrue(fails("""[{"action":"expect","name":"t","row":"a","value":"90","is":"selected"}]""").contains("drop is"))
    }

    @Test
    fun `editors, file and log are read as their own subjects, each with its own checks`() {
        assertTrue(UiSteps.parse("""[{"action":"get","editors":true}]""").single().editors)
        assertEquals("a.md", UiSteps.parse("""[{"action":"get","file":"a.md"}]""").single().file)
        assertEquals("trace", UiSteps.parse("""[{"action":"set","log":"#x","value":"trace"}]""").single().value)
        assertEquals("a.md", UiSteps.parse("""[{"action":"expect","editor":"a.md","is":"focused"}]""").single().editor)
        assertEquals("Opening", UiSteps.parse("""[{"action":"expect","log":"Opening","not":true}]""").single().log)
        assertTrue(fails("""[{"action":"get","editors":true,"registry":"a"}]""").contains("exactly one of"))
        assertTrue(fails("""[{"action":"set","file":"a.md","value":"x"}]""").contains("set needs exactly one of"))
        assertTrue(fails("""[{"action":"set","log":"#x","value":"loud"}]""").contains("trace, debug, all, default"))
        assertTrue(fails("""[{"action":"expect","editor":"a.md","is":"checked"}]""").contains("visible, focused or hidden"))
        assertTrue(fails("""[{"action":"expect","editor":"a.md","contains":"x"}]""").contains("check its text with file"))
        assertTrue(fails("""[{"action":"expect","log":"x","is":"visible"}]""").contains("text alone"))
        assertTrue(fails("""[{"action":"click","name":"OK","editors":true}]""").contains("editors goes with get"))
        assertTrue(fails("""[{"action":"click","name":"OK","log":"x"}]""").contains("log goes with expect, get and set"))
        assertTrue(fails("""[{"action":"click","name":"OK","editor":"a.md"}]""").contains("go(es) with expect"))
    }

    @Test
    fun `error true checks for any IDE error, as the empty text does`() {
        assertEquals("", UiSteps.parse("""[{"action":"expect","error":true,"not":true}]""").single().error)
        assertEquals("true", UiSteps.parse("""[{"action":"expect","error":"true"}]""").single().error)
    }

    @Test
    fun `expect flags stay on expect, and bug goes with expect and code`() {
        assertTrue(fails("""[{"action":"click","name":"OK","soft":true}]""").contains("soft goes with expect"))
        assertTrue(fails("""[{"action":"click","name":"OK","not":true}]""").contains("not goes with expect"))
        assertTrue(fails("""[{"action":"click","name":"OK","is":"checked"}]""").contains("go(es) with expect"))
        assertTrue(fails("""[{"action":"click","name":"OK","bug":"x"}]""").contains("bug goes with expect and code"))
        assertTrue(fails("""[{"action":"expect","name":"OK","bug":"x","soft":true}]""").contains("cannot be soft"))
        assertEquals("rename breaks", UiSteps.parse("""[{"action":"code","code":"check(false)","bug":"rename breaks"}]""").single().bug)
    }

    @Test
    fun `settings, set, write, perf and code read their fields`() {
        assertEquals("Editor > General", UiSteps.parse("""[{"action":"settings","page":"Editor > General"}]""").single().page)
        assertTrue(fails("""[{"action":"settings"}]""").contains("needs a page"))
        val registry = UiSteps.parse("""[{"action":"set","registry":"ide.a","value":true}]""").single()
        assertEquals("ide.a", registry.registry)
        assertEquals("true", registry.value)
        assertTrue(fails("""[{"action":"set","registry":"a","advanced":"b","value":"1"}]""").contains("exactly one"))
        assertTrue(fails("""[{"action":"set","registry":"a"}]""").contains("needs a value"))
        val write = UiSteps.parse("""[{"action":"write","file":"src/A.kt","text":""}]""").single()
        assertEquals("", write.text)
        assertNull(write.target)
        assertTrue(fails("""[{"action":"write","file":"A.kt"}]""").contains("needs text"))
        val perf = UiSteps.parse("""[{"action":"perf","command":"%openFile A.kt"}]""").single()
        assertEquals(UiSteps.LONG_DEFAULT_TIMEOUT_MS, perf.timeoutMs)
        val code = UiSteps.parse("""[{"action":"code","code":"println(1)","modal":"dialog","timeout_ms":900000}]""").single()
        assertEquals("dialog", code.modal)
        assertEquals(UiSteps.LONG_MAX_TIMEOUT_MS, code.timeoutMs)
        assertTrue(fails("""[{"action":"code","code":"x","modal":"any"}]""").contains("unknown modal"))
        assertTrue(fails("""[{"action":"click","name":"a","modal":"dialog"}]""").contains("modal goes with code"))
        assertTrue(fails("""[{"action":"click","name":"a","value":"x"}]""").contains("value goes with"))
    }

    @Test
    fun `screenshot takes an optional target and needs a plain file name`() {
        val step = UiSteps.parse("""[{"action":"screenshot","name":"Settings categories","save":"settings-tree"}]""").single()
        assertEquals(UiAction.SCREENSHOT, step.action)
        assertEquals("settings-tree", step.save)
        assertEquals(UiTarget(name = "Settings categories"), step.target)
        assertNull(UiSteps.parse("""[{"action":"screenshot","save":"top"}]""").single().target)
        assertTrue(fails("""[{"action":"screenshot"}]""").contains("screenshot needs save"))
        assertTrue(fails("""[{"action":"screenshot","save":"../up"}]""").contains("plain file name"))
        assertTrue(fails("""[{"action":"click","name":"OK","save":"x"}]""").contains("save goes with screenshot"))
    }

    @Test
    fun `a step takes the side it runs on in Split Mode`() {
        assertEquals("backend", UiSteps.parse("""[{"action":"expect","name":"OK","side":"backend"}]""").single().side)
        assertNull(UiSteps.parse("""[{"action":"close"}]""").single().side)
        assertTrue(fails("""[{"action":"close","side":"server"}]""").contains("unknown side"))
    }

    @Test
    fun `a step keeps the object it was written as`() {
        val step = UiSteps.parse("""[{"action":"click","ref":"e3","intent":"open it"}]""").single()
        assertEquals(setOf("action", "ref", "intent"), step.source!!.keys)
    }

    @Test
    fun `input that is not an array of objects fails`() {
        assertTrue(fails("""{"action":"click"}""").contains("JSON array"))
        assertTrue(fails("""[1]""").contains("object"))
        assertTrue(fails("""[{"action":"click",""").contains("JSON"))
    }
}
