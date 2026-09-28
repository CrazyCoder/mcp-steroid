/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeBuildsTest {
    private val builds = IdeBuilds()
    private val now = System.currentTimeMillis()

    @Test
    fun `a session hears a failed build once, with its first errors`() {
        val session = Any()
        builds.add("refplay", "Build refplay", failed = false, errors = emptyList(), atMs = now - 60_000)
        builds.add("refplay", "Sync refplay", failed = true, errors = listOf("a.java:3: x", "b.java:4: y", "c.java:5: z", "d.java:6: w"), atMs = now - 1_000)

        val notice = builds.noticeFor(session, now)!!
        assertTrue(notice, notice.startsWith("BUILD FAILED: a build failed since your last call"))
        assertTrue(notice, notice.contains("Sync refplay: 4 errors\n  a.java:3: x\n  b.java:4: y\n  c.java:5: z\n  and 1 more"))
        assertFalse(notice, notice.contains("Build refplay"))
        assertNull(builds.noticeFor(session, now))
    }

    @Test
    fun `a failure a later build of the same title passed is not told`() {
        val session = Any()
        builds.noticeFor(session, now)
        builds.add("refplay", "Build refplay", failed = true, errors = listOf("App.java:26: cannot find symbol"), atMs = now - 2_000)
        builds.add("refplay", "Build refplay", failed = false, errors = emptyList(), atMs = now - 1_000)
        assertNull(builds.noticeFor(session, now))
    }

    @Test
    fun `a first call hears only the recent failures`() {
        builds.add("refplay", "Build refplay", failed = true, errors = emptyList(), atMs = now - IdeBuilds.RECENT_MS - 1_000)
        assertNull(builds.noticeFor(Any(), now))
    }

    @Test
    fun `several projects are named, the newest build first`() {
        builds.add("a", "Build a", failed = true, errors = emptyList(), atMs = now - 2_000)
        builds.add("b", "Build b", failed = true, errors = listOf("x"), atMs = now - 1_000)
        val lines = IdeBuilds.render(builds.recent(), side = null).lines()
        assertEquals("BUILD FAILED: 2 builds failed since your last call; the Build tool window has the full output:", lines[0])
        assertTrue(lines[1], lines[1].endsWith("Build b in b: 1 error"))
        assertTrue(lines[3], lines[3].endsWith("Build a in a"))
    }
}
