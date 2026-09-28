/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UiCapturePathTest {
    @Test
    fun `a relative out resolves against the scenario folder`() {
        assertEquals(Path.of("C:/docs/scenarios/img/a.png"), UiCapturePaths.resolve("img/a.png", Path.of("C:/docs/scenarios")))
        assertEquals(Path.of("C:/docs/img/a.png"), UiCapturePaths.resolve("../img/a.png", Path.of("C:/docs/scenarios")))
    }

    @Test
    fun `an absolute out is kept`() {
        assertEquals(Path.of("C:/pics/a.png"), UiCapturePaths.resolve("C:/pics/a.png", null))
    }

    @Test
    fun `an out without an extension is a png, and a jpg stays a jpg`() {
        assertEquals(Path.of("C:/pics/a.png"), UiCapturePaths.resolve("C:/pics/a", null))
        assertEquals(Path.of("C:/pics/v1.2/a.png"), UiCapturePaths.resolve("C:/pics/v1.2/a", null))
        assertEquals(Path.of("C:/pics/a.jpg"), UiCapturePaths.resolve("C:/pics/a.jpg", null))
        assertEquals("png", UiCapturePaths.format(Path.of("C:/pics/a.png")))
        assertEquals("jpg", UiCapturePaths.format(Path.of("C:/pics/a.JPEG")))
    }

    @Test
    fun `a relative out outside a scenario is refused`() {
        val e = assertThrows(UiStepFailure::class.java) { UiCapturePaths.resolve("a.png", null) }
        assertTrue(e.message!!, "absolute" in e.message!!)
    }
}
