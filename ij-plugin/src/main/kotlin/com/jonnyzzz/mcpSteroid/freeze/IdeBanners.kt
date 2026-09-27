/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.freeze

import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.client.ClientKind
import com.intellij.openapi.client.ClientSessionsManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotifications
import com.intellij.ui.EditorNotificationsImpl
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.UIUtil
import com.jonnyzzz.mcpSteroid.server.split.SplitRole
import com.jonnyzzz.mcpSteroid.server.split.currentSplitRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Color
import java.util.WeakHashMap

/** A banner above an open editor, such as "Module JDK is not defined" with its "Setup SDK" link. */
data class IdeBanner(val project: String, val file: String, val text: String, val links: List<String>, val severity: String)

/**
 * Tells agents about the warning and error banners above the IDE's open editors, which usually mean the project is
 * not set up: a missing SDK, a build file that is not linked, a file the IDE cannot read. Each banner is told to a
 * session once, and again only after it went away and came back.
 *
 * The banners are read on the EDT, which a freeze blocks, so they are refreshed after a call with a short timeout and
 * read from the last refresh otherwise.
 */
@Service(Service.Level.APP)
class IdeBanners {
    /** The banners of the last [refresh]. */
    @Volatile
    internal var current: List<IdeBanner> = emptyList()
    private val seen = WeakHashMap<Any, Set<IdeBanner>>()

    /**
     * Reads the banners of every open project's editors again, unless the EDT does not answer in time. It runs after
     * every call, and reads internal platform classes, so a failure, even a linkage error on another IDE build, leaves
     * the last reading and is logged once instead of failing the call.
     */
    suspend fun refresh() {
        try {
            withTimeoutOrNull(REFRESH_MS) {
                current = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.flatMap { read(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                thisLogger().warn("Cannot read the editor banners; the banner notice stays off", e)
            }
        }
    }

    @Volatile
    private var failureLogged = false

    /** The banners [session] has not been told about, as a notice, and marks every current banner as told. */
    fun noticeFor(session: Any): String? {
        val now = current
        val fresh = synchronized(seen) {
            val before = seen[session].orEmpty()
            seen[session] = now.toSet()
            now.filter { it !in before }
        }
        return if (fresh.isEmpty()) null else render(fresh, FreezeMonitor.sideOf(currentSplitRole()))
    }

    companion object {
        const val MAX_LINES = 3
        private const val REFRESH_MS = 500L
        private const val MAX_FILES = 3
        private const val MAX_TEXT = 160

        /** The severities worth telling unasked: a banner of either means something is wrong. */
        private val REPORTED = setOf(EditorNotificationPanel.Status.Warning, EditorNotificationPanel.Status.Error)

        fun getInstanceOrNull(): IdeBanners? = ApplicationManager.getApplication()?.let { service<IdeBanners>() }

        /**
         * The warning and error banners above [project]'s open editors, or every banner with [all]. The banners are
         * read from the platform's own record of each editor's notifications, which holds them in Split Mode too,
         * where the backend hosts them for the JetBrains Client outside the editor's own panel.
         *
         * A Split Mode backend keeps the editors of each JetBrains Client apart from the host's, which a call made to
         * the backend directly sees, and which has none open. So each client session is read under its own client id.
         */
        suspend fun read(project: Project, all: Boolean = false): List<IdeBanner> {
            val sessions = runCatching { ClientSessionsManager.getProjectSessions(project, ClientKind.ALL) }.getOrDefault(emptyList())
            val contexts = sessions.map { it.clientId.asContextElement() }.ifEmpty { listOf(null) }
            return contexts.flatMap { client ->
                val context = Dispatchers.EDT + ModalityState.any().asContextElement()
                withContext(if (client == null) context else context + client) { readHere(project, all) }
            }.distinct()
        }

        /** The banners of the editors the current client has open. EDT. */
        private fun readHere(project: Project, all: Boolean): List<IdeBanner> {
            if (project.isDisposed) return emptyList()
            val notifications = EditorNotifications.getInstance(project) as? EditorNotificationsImpl ?: return emptyList()
            val manager = FileEditorManager.getInstance(project)
            return manager.openFiles.flatMap { file ->
                manager.getEditors(file).flatMap { editor ->
                    @Suppress("TestOnlyProblems") // The only reader of an editor's notifications; it is read-only.
                    notifications.getNotificationPanels(editor).values
                }.mapNotNull { component ->
                    val panel = component as? EditorNotificationPanel
                        ?: UIUtil.findComponentsOfType(component, EditorNotificationPanel::class.java).firstOrNull()
                        ?: return@mapNotNull null
                    if (!component.isVisible) return@mapNotNull null
                    val status = statusOf(panel)
                    if (!all && status !in REPORTED) return@mapNotNull null
                    val text = panel.text.orEmpty().trim().ifEmpty { return@mapNotNull null }
                    val links = UIUtil.findComponentsOfType(component, HyperlinkLabel::class.java).mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
                    IdeBanner(project.name, shortPath(project, file), text.take(MAX_TEXT), links, status?.name?.uppercase() ?: "BANNER")
                }.distinct()
            }
        }

        /**
         * The status a panel was made with. It keeps no field for it, but it takes the status's background color,
         * which Info and Promo share. The panel's copy is a theme color of another class that neither is nor equals
         * the status's object, so the colors are compared by their current RGB value. A panel made without a status has
         * none.
         */
        private fun statusOf(panel: EditorNotificationPanel): EditorNotificationPanel.Status? = runCatching {
            val background = EditorNotificationPanel::class.java.getDeclaredField("myBackgroundColor").apply { isAccessible = true }.get(panel) as? Color
                ?: return null
            val statusBackground = EditorNotificationPanel.Status::class.java.getDeclaredField("background").apply { isAccessible = true }
            EditorNotificationPanel.Status.entries.firstOrNull { (statusBackground.get(it) as Color).rgb == background.rgb }
        }.getOrNull()

        private fun shortPath(project: Project, file: VirtualFile): String =
            project.guessProjectDir()?.let { VfsUtilCore.getRelativePath(file, it) }?.takeIf { it.isNotEmpty() } ?: file.presentableUrl

        /** Same banners on one line with the files that show them, at most [MAX_LINES] lines. */
        fun render(banners: List<IdeBanner>, side: String? = null): String = buildString {
            val groups = banners.groupBy { Triple(it.severity, it.text, it.links) }.entries.toList()
            val count = if (groups.size == 1) "1 banner" else "${groups.size} banners"
            append("EDITOR BANNERS${side?.let { " in $it" }.orEmpty()}: $count above open editors, which usually means the project is not set up")
            // The backend draws a banner's links in Split Mode, so a click on one has to run there.
            append("; act on one with a steroid_ui click on its link's text, such as {\"action\":\"click\",\"text\":\"<link>\"")
            append(if (side == FreezeMonitor.sideOf(SplitRole.BACKEND)) ",\"side\":\"backend\"}:" else "}:")
            for ((key, group) in groups.take(MAX_LINES)) {
                val (severity, text, links) = key
                val files = group.map { if (it.project == group.first().project) it.file else "${it.project}: ${it.file}" }.distinct()
                append("\n- ").append(severity).append(' ').append(text)
                if (links.isNotEmpty()) append(" [").append(links.joinToString(" | ")).append(']')
                append(" in ").append(files.take(MAX_FILES).joinToString())
                if (files.size > MAX_FILES) append(" and ${files.size - MAX_FILES} more files")
            }
            if (groups.size > MAX_LINES) append("\n- and ${groups.size - MAX_LINES} more banners")
            append('\n')
        }
    }
}
