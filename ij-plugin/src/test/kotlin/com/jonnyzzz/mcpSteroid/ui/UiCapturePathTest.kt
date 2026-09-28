/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class UiCapturePathTest {
    /** An absolute folder on any OS: "C:/..." is relative on Linux and macOS. */
    private val root: Path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("capture-paths")
    private val scenarios = root.resolve("docs").resolve("scenarios")

    @Test
    fun `a relative out resolves against the scenario folder`() {
        assertEquals(scenarios.resolve("img").resolve("a.png"), UiCapturePaths.resolve("img/a.png", scenarios))
        assertEquals(root.resolve("docs").resolve("img").resolve("a.png"), UiCapturePaths.resolve("../img/a.png", scenarios))
    }

    @Test
    fun `an absolute out is kept`() {
        val pic = root.resolve("pics").resolve("a.png")
        assertEquals(pic, UiCapturePaths.resolve(pic.toString(), null))
    }

    @Test
    fun `an out without an extension is a png, and a jpg stays a jpg`() {
        val pics = root.resolve("pics")
        assertEquals(pics.resolve("a.png"), UiCapturePaths.resolve(pics.resolve("a").toString(), null))
        assertEquals(pics.resolve("v1.2").resolve("a.png"), UiCapturePaths.resolve(pics.resolve("v1.2").resolve("a").toString(), null))
        assertEquals(pics.resolve("a.jpg"), UiCapturePaths.resolve(pics.resolve("a.jpg").toString(), null))
        assertEquals("png", UiCapturePaths.format(pics.resolve("a.png")))
        assertEquals("jpg", UiCapturePaths.format(pics.resolve("a.JPEG")))
    }

    @Test
    fun `a relative out outside a scenario is refused`() {
        val e = assertThrows(UiStepFailure::class.java) { UiCapturePaths.resolve("a.png", null) }
        assertTrue(e.message!!, "absolute" in e.message!!)
    }
}
