/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

/** A file open in editors, by its project-relative path, and how many editors show it on that side. */
data class UiOpenFile(val path: String, val editors: Int) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The open editors of one side: a regular IDE, a JetBrains Client, the Remote Development backend's own editors, or
 * one JetBrains Client session as the backend keeps it. [client] marks a backend's record of a Client session.
 */
data class UiEditorSide(val label: String, val files: List<UiOpenFile>, val selected: String?, val client: Boolean = false)

/**
 * The text form of editor state that a get of editors reports, and the checks that compare a JetBrains Client's
 * editors with the backend's record of them. A Client reads the backend's state from this text, so the format and
 * its parser live together here.
 */
object UiEditorState {
    private const val PREFIX = "editors of "
    private const val SESSION = "JetBrains Client session "
    private const val SEPARATOR = " | "
    private const val SELECTED = "; selected "
    private const val NONE = "none"
    private val COUNT = Regex("""^(.*) \((\d+) editors\)$""")

    fun sessionLabel(id: String): String = SESSION + id

    /** One line per side, such as `editors of the IDE: src/A.kt | docs/B.md (2 editors); selected src/A.kt`. */
    fun render(sides: List<UiEditorSide>): String = sides.joinToString("\n") { side ->
        val files = side.files.joinToString(SEPARATOR) { if (it.editors > 1) "${it.path} (${it.editors} editors)" else it.path }.ifEmpty { NONE }
        PREFIX + side.label + ": " + files + (side.selected?.let { SELECTED + it }.orEmpty())
    }

    /** The sides of [render]'s lines in [text]; other lines are skipped. */
    fun parse(text: String): List<UiEditorSide> = text.lines().mapNotNull { line ->
        if (!line.startsWith(PREFIX)) return@mapNotNull null
        val body = line.removePrefix(PREFIX)
        val colon = body.indexOf(": ").takeIf { it >= 0 } ?: return@mapNotNull null
        val label = body.substring(0, colon)
        val rest = body.substring(colon + 2)
        val (filesText, selected) = rest.indexOf(SELECTED).let { if (it < 0) rest to null else rest.substring(0, it) to rest.substring(it + SELECTED.length) }
        val files = if (filesText == NONE) emptyList() else filesText.split(SEPARATOR).map { part ->
            COUNT.matchEntire(part)?.let { UiOpenFile(it.groupValues[1], it.groupValues[2].toInt()) } ?: UiOpenFile(part, 1)
        }
        UiEditorSide(label, files, selected, client = label.startsWith(SESSION))
    }

    /**
     * Where the JetBrains Client's editors ([client]) and the backend's record of them ([backend]) disagree: a file
     * the backend keeps more editors for than the Client shows, which a later open can pick and show nothing, and,
     * with one Client session, a file only one side counts as open. Files are matched by path, else by name, since
     * each side reports paths against its own project folder.
     */
    fun mismatches(client: UiEditorSide, backend: List<UiEditorSide>): List<String> {
        val sessions = backend.filter { it.client }
        fun shown(file: UiOpenFile) = client.files.firstOrNull { it.path == file.path } ?: client.files.firstOrNull { it.name == file.name }
        val out = mutableListOf<String>()
        for (session in sessions) {
            for (file in session.files) {
                val there = shown(file)?.editors ?: 0
                if (file.editors > maxOf(there, 1)) {
                    out += "the backend keeps ${file.editors} editors of ${file.path} for the JetBrains Client, which shows $there; opening it may show nothing"
                }
            }
        }
        val session = sessions.singleOrNull() ?: return out
        for (file in session.files) {
            if (shown(file) == null) out += "the backend counts ${file.path} as open in the JetBrains Client, which shows no editor of it"
        }
        for (file in client.files) {
            val known = session.files.any { it.path == file.path } || session.files.any { it.name == file.name }
            if (!known) out += "the JetBrains Client shows ${file.path}, which the backend does not count as open"
        }
        return out
    }
}
