/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeBannersTest {
    private val jdkA = IdeBanner("proj", "src/A.java", "Module JDK is not defined", listOf("Setup SDK"), "WARNING")
    private val jdkB = jdkA.copy(file = "src/B.java")
    private val gradle = IdeBanner("proj", "build.gradle", "Gradle build script found", listOf("Load Gradle Project", "Skip"), "WARNING")

    @Test
    fun `a banner is told to a session once, and again after it went away and came back`() {
        val banners = IdeBanners()
        val session = Any()
        banners.current = listOf(jdkA)
        assertTrue(banners.noticeFor(session)!!.contains("Module JDK is not defined"))
        assertNull(banners.noticeFor(session))
        assertTrue("another session hears it too", banners.noticeFor(Any())!!.contains("Module JDK"))
        banners.current = emptyList()
        assertNull(banners.noticeFor(session))
        banners.current = listOf(jdkA)
        assertTrue(banners.noticeFor(session)!!.contains("Module JDK is not defined"))
    }

    @Test
    fun `a refresh that cannot read the banners keeps the last reading instead of failing the call`() {
        // No IDE runs in this test, so reading the open projects throws, as a missing platform class would.
        val banners = IdeBanners()
        banners.current = listOf(jdkA)
        runBlocking { banners.refresh() }
        assertEquals(listOf(jdkA), banners.current)
    }

    @Test
    fun `only banners new to the session are told`() {
        val banners = IdeBanners()
        val session = Any()
        banners.current = listOf(jdkA)
        banners.noticeFor(session)
        banners.current = listOf(jdkA, gradle)
        val notice = banners.noticeFor(session)!!
        assertTrue(notice.contains("Gradle build script found"))
        assertTrue(!notice.contains("Module JDK"))
    }

    @Test
    fun `the same banner on several files is one line that names the files and the links`() {
        val notice = IdeBanners.render(listOf(jdkA, jdkB, gradle), side = null)
        assertEquals(
            "EDITOR BANNERS: 2 banners above open editors, which usually means the project is not set up; act on one with a steroid_ui " +
                "click on its link's text, such as {\"action\":\"click\",\"text\":\"<link>\"}:\n" +
                "- WARNING Module JDK is not defined [Setup SDK] in src/A.java, src/B.java\n" +
                "- WARNING Gradle build script found [Load Gradle Project | Skip] in build.gradle\n",
            notice,
        )
    }

    @Test
    fun `more banners than lines are counted, and a side is named`() {
        val many = (1..5).map { IdeBanner("proj", "f$it.txt", "Problem $it", emptyList(), "ERROR") }
        val notice = IdeBanners.render(many, side = "the backend")
        assertTrue(notice.startsWith("EDITOR BANNERS in the backend: 5 banners"))
        assertTrue("a Split Mode backend's links need a backend click", notice.contains("\"text\":\"<link>\",\"side\":\"backend\"}"))
        assertTrue(notice.contains("- and 2 more banners"))
        assertEquals(IdeBanners.MAX_LINES + 2, notice.trimEnd().lines().size)
    }
}
