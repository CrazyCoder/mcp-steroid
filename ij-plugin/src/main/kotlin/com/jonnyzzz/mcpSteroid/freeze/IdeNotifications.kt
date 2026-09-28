/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.jonnyzzz.mcpSteroid.server.split.SplitRole
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap

/** One notification the IDE showed, as its balloon and the Notifications tool window show it. */
data class IdeNotification(
    val seq: Long,
    val atMs: Long,
    val project: String?,
    val type: String,
    val title: String,
    val content: String,
    val actions: List<String>,
)

/**
 * Tells agents about the notifications the IDE showed between their calls: a failed VCS update, a plugin that asks for a
 * restart, an SDK the project is missing, an indexing that could not finish. Each is told to a session once.
 *
 * A JetBrains Client shows the backend's notifications as well as its own, so in Split Mode the client tells them and
 * the backend keeps quiet: each notification is told once, whichever side made it.
 */
@Service(Service.Level.APP)
class IdeNotifications {
    private val shown = ArrayDeque<IdeNotification>()
    private var nextSeq = 1L
    private val seen = WeakHashMap<Any, Long>()

    /** Listens to the notifications shown without a project. */
    class AppListener : Notifications {
        override fun notify(notification: Notification) {
            getInstanceOrNull()?.add(null, notification)
        }
    }

    /** Listens to the notifications of [project], which its own message bus carries alone. */
    class ProjectListener(private val project: Project) : Notifications {
        override fun notify(notification: Notification) {
            getInstanceOrNull()?.add(project.name, notification)
        }
    }

    fun add(project: String?, n: Notification) {
        if (currentSplitRole() == SplitRole.BACKEND) return
        add(project, n.type.name, plain(n.title), plain(listOfNotNull(n.subtitle, n.content).joinToString(" ")), n.actions.mapNotNull { a ->
            a.templateText?.let(::plain)?.takeIf { it.isNotEmpty() }
        })
    }

    fun add(project: String?, type: String, title: String, content: String, actions: List<String>, atMs: Long = System.currentTimeMillis()) =
        synchronized(this) {
            if (title.isEmpty() && content.isEmpty()) return@synchronized
            shown.addLast(IdeNotification(nextSeq++, atMs, project, type, clip(title), clip(content), actions))
            while (shown.size > MAX_KEPT) shown.removeFirst()
        }

    /** The notifications shown, oldest first, the last [limit] of them. */
    fun recent(limit: Int = MAX_KEPT): List<IdeNotification> = synchronized(this) { shown.toList().takeLast(limit) }

    /**
     * The notifications [session] has not been told about, as a notice, and marks them told. A session's first call
     * hears about those of the last [RECENT_MS].
     */
    fun noticeFor(session: Any, nowMs: Long = System.currentTimeMillis()): String? {
        val fresh = synchronized(this) {
            val after = seen.put(session, nextSeq)
            shown.filter { if (after != null) it.seq >= after else nowMs - it.atMs < RECENT_MS }
        }
        return if (fresh.isEmpty()) null else render(fresh, FreezeMonitor.sideOf(currentSplitRole()))
    }

    companion object {
        const val MAX_KEPT = 50
        const val MAX_LINES = 3
        const val RECENT_MS = 10 * 60_000L
        private const val MAX_TEXT = 200
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        /** Errors and warnings first: they are what may need attention. */
        private val ORDER = listOf("ERROR", "WARNING")

        fun getInstanceOrNull(): IdeNotifications? = ApplicationManager.getApplication()?.let { service<IdeNotifications>() }

        private fun plain(html: String): String = StringUtil.removeHtmlTags(html, true).replace(Regex("\\s+"), " ").trim()

        private fun clip(s: String) = if (s.length > MAX_TEXT) s.take(MAX_TEXT) + "…" else s

        private fun line(n: IdeNotification, withProject: Boolean): String = buildString {
            append(TIME.format(Instant.ofEpochMilli(n.atMs))).append(' ').append(n.type)
            if (withProject && n.project != null) append(" in ").append(n.project)
            append(' ').append(listOf(n.title, n.content).filter { it.isNotEmpty() }.joinToString(": "))
            if (n.actions.isNotEmpty()) append(" [").append(n.actions.joinToString(" | ")).append(']')
        }

        /**
         * The notifications, errors and warnings first and then the newest, at most [MAX_LINES] lines, with how to act
         * on one and how to list them all. [side] names the process in Split Mode, as [FreezeMonitor.sideOf] gives it.
         */
        fun render(notifications: List<IdeNotification>, side: String? = null): String = buildString {
            val shown = notifications.sortedWith(compareBy<IdeNotification> { ORDER.indexOf(it.type).let { i -> if (i < 0) ORDER.size else i } }
                .thenByDescending { it.seq })
            val count = if (notifications.size == 1) "1 notification" else "${notifications.size} notifications"
            val projects = notifications.mapNotNull { it.project }.distinct().size > 1
            append("IDE NOTIFICATIONS${side?.let { " in $it" }.orEmpty()}: the IDE showed $count since your last call")
            append("; act on one with a steroid_ui click on its action in the Notifications tool window, ")
            append("{\"action\":\"toolwindow\",\"id\":\"Notifications\"}:")
            for (n in shown.take(MAX_LINES)) append("\n- ").append(line(n, projects))
            if (shown.size > MAX_LINES) append("\n- and ${shown.size - MAX_LINES} more; {\"action\":\"get\",\"notifications\":true} lists them")
            append('\n')
        }

        /** The notifications a get lists, the newest first. */
        fun renderRecent(notifications: List<IdeNotification>): String {
            if (notifications.isEmpty()) return "the IDE showed no notification since MCP Steroid started"
            val projects = notifications.mapNotNull { it.project }.distinct().size > 1
            return notifications.sortedByDescending { it.seq }.joinToString("\n") { line(it, projects) }
        }
    }
}
