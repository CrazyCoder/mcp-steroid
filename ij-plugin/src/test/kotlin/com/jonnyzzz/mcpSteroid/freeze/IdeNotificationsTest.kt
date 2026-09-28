/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class IdeNotificationsTest {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    @Test
    fun `a notification is told to each session once, and a first call hears the recent ones only`() {
        val notifications = IdeNotifications()
        val now = System.currentTimeMillis()
        notifications.add("proj", "INFORMATION", "Old", "long ago", emptyList(), atMs = now - IdeNotifications.RECENT_MS - 1_000)
        notifications.add("proj", "WARNING", "Git", "Update failed", listOf("Show Log"), atMs = now)
        val session = Any()
        val first = notifications.noticeFor(session, now)!!
        assertTrue(first.contains("Update failed [Show Log]"))
        assertTrue("older than the recent window", !first.contains("long ago"))
        assertNull(notifications.noticeFor(session, now))
        notifications.add("proj", "INFORMATION", "Indexing", "done", emptyList())
        assertTrue(notifications.noticeFor(session)!!.contains("Indexing: done"))
    }

    @Test
    fun `errors and warnings come first, past the line limit the rest is counted with how to list it`() {
        val at = 1_700_000_000_000L
        val list = listOf(
            IdeNotification(1, at, "proj", "INFORMATION", "Tip", "one", emptyList()),
            IdeNotification(2, at, "proj", "INFORMATION", "Tip", "two", emptyList()),
            IdeNotification(3, at, "proj", "ERROR", "Plugin", "failed to load", listOf("Details", "Disable")),
            IdeNotification(4, at, "proj", "WARNING", "", "SDK is missing", emptyList()),
        )
        val t = time.format(Instant.ofEpochMilli(at))
        assertEquals(
            "IDE NOTIFICATIONS: the IDE showed 4 notifications since your last call; act on one with a steroid_ui click on its action in " +
                "the Notifications tool window, {\"action\":\"toolwindow\",\"id\":\"Notifications\"}:\n" +
                "- $t ERROR Plugin: failed to load [Details | Disable]\n" +
                "- $t WARNING SDK is missing\n" +
                "- $t INFORMATION Tip: two\n" +
                "- and 1 more; {\"action\":\"get\",\"notifications\":true} lists them\n",
            IdeNotifications.render(list),
        )
    }

    @Test
    fun `a notification without a title or a text is not kept, and a long text is cut`() {
        val notifications = IdeNotifications()
        notifications.add(null, "INFORMATION", "", "", emptyList())
        assertEquals(emptyList<IdeNotification>(), notifications.recent())
        notifications.add(null, "INFORMATION", "T", "x".repeat(500), emptyList())
        assertEquals(201, notifications.recent().single().content.length)
    }
}
