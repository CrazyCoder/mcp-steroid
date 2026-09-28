/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import com.jonnyzzz.mcpSteroid.server.UiRestore
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * The project files a steroid_ui run changes, as a person would review them: each file's text before the run first
 * changed it, and the files the run created, deleted or moved, from the IDE's documents and file system events. So a
 * refactoring driven through its dialog reports the code it changed, a scenario can check that code, and a replay puts
 * it back.
 *
 * Files outside the project's content, excluded ones such as build output, `.idea` and binary files are left out, and
 * so are changes made outside the IDE, which a refresh finds on disk.
 */
internal class UiCodeChanges(private val project: Project, parent: Disposable) {
    private val base: Path? = project.basePath?.let(Path::of)

    /** Each changed file's text before the session first changed it, null for a file it created. */
    private val session = LinkedHashMap<String, String?>()
    /** The same since the last [checkpoint], which the run's expects and summary compare with. */
    private val run = LinkedHashMap<String, String?>()
    /** The same since the step started, for the step's report and its restores. */
    private val step = LinkedHashMap<String, String?>()
    @Volatile
    private var lastChangeMs = 0L

    /**
     * The files changed on disk from outside the IDE, which a refresh found, until when their editors' reloads are
     * ignored. Such a change is not the run's: it would count as the run's change, and a replay's restore would undo it.
     */
    private val external = HashMap<String, Long>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun beforeDocumentChange(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                val path = pathOf(file) ?: return
                if (synchronized(external) { (external[path] ?: 0) > System.currentTimeMillis() }) return
                note(path) { event.document.text }
            }
        }, parent)
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) {
                for (e in events) when {
                    e.isFromRefresh -> if (e is VFileContentChangeEvent) pathOf(e.file)?.let { p ->
                        synchronized(external) { external[p] = System.currentTimeMillis() + RELOAD_MS }
                    }
                    else -> recordBefore(e)
                }
            }

            override fun after(events: List<VFileEvent>) {
                for (e in events) if (!e.isFromRefresh) recordAfter(e)
            }
        })
    }

    /** A file the IDE deletes, moves, renames or writes without its document: its text before. */
    private fun recordBefore(e: VFileEvent) {
        val before = { file: VirtualFile -> forEachFile(file) { f -> pathOf(f)?.let { p -> note(p) { textOf(f) } } } }
        when (e) {
            is VFileDeleteEvent -> before(e.file)
            is VFileMoveEvent -> before(e.file)
            is VFilePropertyChangeEvent -> if (e.propertyName == VirtualFile.PROP_NAME) before(e.file)
            is VFileContentChangeEvent -> before(e.file)
            else -> Unit
        }
    }

    /**
     * A file the IDE creates, or the new place of one it moved or renamed, which did not exist before. A new empty file
     * has no content to tell its type by, so it counts whatever type it shows.
     */
    private fun recordAfter(e: VFileEvent) {
        val created = { file: VirtualFile -> forEachFile(file) { f -> pathOf(f, anyType = true)?.let { p -> note(p) { null } } } }
        when (e) {
            is VFileCreateEvent -> e.file?.let(created)
            is VFileMoveEvent -> created(e.file)
            is VFilePropertyChangeEvent -> if (e.propertyName == VirtualFile.PROP_NAME) created(e.file)
            else -> Unit
        }
    }

    /** Records [path]'s text before its first change in each span; [before] is read only when a span lacks it. */
    private fun note(path: String, before: () -> String?) = synchronized(this) {
        lastChangeMs = System.currentTimeMillis()
        if (path in session && path in run && path in step) return@synchronized
        val text = before()
        session.putIfAbsent(path, text)
        run.putIfAbsent(path, text)
        step.putIfAbsent(path, text)
    }

    /** Starts the run's span: the steps after this are what its summary and expects compare with. */
    fun checkpoint() = synchronized(this) { run.clear() }

    /** Starts a step's span. */
    fun stepStarted() = synchronized(this) { step.clear() }

    /**
     * Waits until no change came for [QUIET_MS], at most [MAX_WAIT_MS]: a refactoring whose dialog closed writes its
     * changes a moment later.
     */
    suspend fun awaitQuiet() {
        val until = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() - lastChangeMs < QUIET_MS && System.currentTimeMillis() < until) delay(POLL_MS)
    }

    /** One file's change: its text before and now, null where the file did not exist. */
    class FileChange(val path: String, val before: String?, val after: String?) {
        val kind get() = when {
            before == null -> "created"
            after == null -> "deleted"
            else -> "changed"
        }
    }

    /** The files the step changed so far. */
    suspend fun stepChanges(): List<FileChange> = changes(synchronized(this) { step.toMap() })

    /** The files the run changed since its checkpoint. */
    suspend fun runChanges(): List<FileChange> = changes(synchronized(this) { run.toMap() })

    /** The steps that put back the files [changes] names as the session found them: a write of the text, or a delete. */
    fun restores(changes: List<FileChange>): List<JsonObject> = synchronized(this) {
        changes.map { c ->
            val original = session[c.path]
            if (original == null) UiRestore.step("write", "file" to c.path, "delete" to true)
            else UiRestore.step("write", "file" to c.path, "text" to original)
        }
    }

    private suspend fun changes(before: Map<String, String?>): List<FileChange> = before.mapNotNull { (path, text) ->
        val now = currentText(path)
        if (now == text) null else FileChange(path, text, now)
    }

    private suspend fun currentText(path: String): String? {
        val file = base?.resolve(path)?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }?.takeIf { it.isValid && !it.isDirectory } ?: return null
        return readAction { textOf(file) }
    }

    /** [file]'s text as the IDE holds it: the open document's, else the file's content. */
    private fun textOf(file: VirtualFile): String? {
        if (!file.isValid || file.isDirectory) return null
        FileDocumentManager.getInstance().getCachedDocument(file)?.let { return it.text }
        return runCatching { VfsUtilCore.loadText(file) }.getOrNull()
    }

    private fun forEachFile(file: VirtualFile, action: (VirtualFile) -> Unit) {
        if (!file.isDirectory) return action(file)
        VfsUtilCore.iterateChildrenRecursively(file, null) { f -> if (!f.isDirectory) action(f); true }
    }

    /** [file]'s path relative to the project, or null for a file this tracker leaves out; a binary one unless [anyType]. */
    private fun pathOf(file: VirtualFile, anyType: Boolean = false): String? {
        if (!file.isInLocalFileSystem || file.isDirectory || (!anyType && file.fileType.isBinary)) return null
        val root = base ?: return null
        val path = runCatching { root.relativize(file.toNioPath()) }.getOrNull() ?: return null
        val relative = path.invariantSeparatorsPathString
        if (relative.startsWith("..") || relative.startsWith(".idea/") || relative.startsWith(".git/")) return null
        val index = ProjectFileIndex.getInstance(project)
        if (index.isExcluded(file)) return null
        return relative
    }

    companion object {
        /** How long after a refresh found a file changed on disk its editor's reload may come. */
        private const val RELOAD_MS = 3_000L
        private const val QUIET_MS = 300L
        private const val MAX_WAIT_MS = 3_000L
        private const val POLL_MS = 50L
        /** The diff lines a summary shows before it cuts. */
        const val MAX_LINES = 40

        /** Per file one line, as a step's report gives it: `changed App.java (+4 -1)`. */
        fun counts(changes: List<FileChange>): String = changes.joinToString("; ") { c ->
            when (c.kind) {
                "created" -> "created ${c.path} (${lineCount(c.after)} lines)"
                "deleted" -> "deleted ${c.path}"
                else -> {
                    val (added, removed) = hunks(c.before!!, c.after!!, context = 0).fold(0 to 0) { (a, r), h ->
                        (a + h.lines.count { it.startsWith("+") }) to (r + h.lines.count { it.startsWith("-") })
                    }
                    "changed ${c.path} (+$added -$removed)"
                }
            }
        }

        /**
         * The changes as a unified diff without context, each hunk headed by the line it starts at in the new text,
         * at most [maxLines] diff lines; a moved file is one deleted and one created path with the same text.
         */
        fun render(changes: List<FileChange>, maxLines: Int = MAX_LINES): String = buildString {
            var budget = maxLines
            var cut = 0
            val moves = changes.filter { it.kind == "deleted" }.mapNotNull { d -> changes.firstOrNull { it.kind == "created" && it.after == d.before }?.let { d to it } }
            val moved = moves.flatMap { listOf(it.first, it.second) }.toSet()
            for ((from, to) in moves) append("moved ${from.path} -> ${to.path}\n")
            for (c in changes) {
                if (c in moved) continue
                when (c.kind) {
                    "created" -> {
                        if ('\u0000' in c.after!!) { append("created ${c.path} (binary)\n"); continue }
                        append("created ${c.path} (${lineCount(c.after)} lines)\n")
                        for (line in c.after.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }) {
                            if (budget-- > 0) append("  +").append(line.trimEnd()).append('\n') else cut++
                        }
                    }
                    "deleted" -> append("deleted ${c.path} (${lineCount(c.before)} lines)\n")
                    else -> {
                        val hunks = hunks(c.before!!, c.after!!, context = 0)
                        val added = hunks.sumOf { h -> h.lines.count { it.startsWith("+") } }
                        val removed = hunks.sumOf { h -> h.lines.count { it.startsWith("-") } }
                        append("changed ${c.path} (+$added -$removed)\n")
                        for (h in hunks) {
                            if (budget <= 0) { cut += h.lines.size; continue }
                            append("  @@ ").append(h.newLine).append('\n')
                            for (line in h.lines) if (budget-- > 0) append("  ").append(line.trimEnd()).append('\n') else cut++
                        }
                    }
                }
            }
            if (cut > 0) append("  … $cut more diff lines; {\"action\":\"get\",\"changes\":true} lists them all\n")
        }.trimEnd()

        private fun lineCount(text: String?): Int = text?.lines()?.let { if (it.lastOrNull() == "") it.size - 1 else it.size } ?: 0

        /** A hunk of a unified diff: the 1-based line it starts at in the new text, and its lines with their markers. */
        class Hunk(val newLine: Int, val lines: List<String>)

        /** The unified diff of [before] and [after] by lines, with [context] unchanged lines around each change. */
        fun hunks(before: String, after: String, context: Int): List<Hunk> {
            val a = before.lines().toTypedArray()
            val b = after.lines().toTypedArray()
            var change = try {
                Diff.buildChanges(a, b)
            } catch (e: FilesTooBigForDiffException) {
                return listOf(Hunk(1, listOf("-(${a.size} lines)", "+(${b.size} lines): the file is too large to diff")))
            }
            val changes = mutableListOf<Diff.Change>()
            while (change != null) { changes += change; change = change.link }
            val hunks = mutableListOf<Hunk>()
            var i = 0
            while (i < changes.size) {
                // Changes closer than twice the context share a hunk, as in a unified diff.
                var j = i
                while (j + 1 < changes.size && changes[j + 1].line0 - (changes[j].line0 + changes[j].deleted) <= 2 * context) j++
                val first = changes[i]
                val last = changes[j]
                val start0 = (first.line0 - context).coerceAtLeast(0)
                val end0 = (last.line0 + last.deleted + context).coerceAtMost(a.size)
                val lines = mutableListOf<String>()
                var pos0 = start0
                for (k in i..j) {
                    val c = changes[k]
                    while (pos0 < c.line0) lines += " " + a[pos0++]
                    for (d in 0 until c.deleted) lines += "-" + a[c.line0 + d]
                    for (n in 0 until c.inserted) lines += "+" + b[c.line1 + n]
                    pos0 = c.line0 + c.deleted
                }
                while (pos0 < end0) lines += " " + a[pos0++]
                hunks += Hunk(first.line1 - (first.line0 - start0) + 1, lines)
                i = j + 1
            }
            return hunks
        }

        /**
         * Whether [wanted], diff lines each starting with `+`, `-` or a space, appear one after another in one hunk of
         * [change]'s diff, compared without indentation or trailing spaces.
         */
        fun diffHas(change: FileChange, wanted: List<String>): Boolean {
            val norm = { line: String -> line.take(1) + line.drop(1).trim() }
            val want = wanted.filter { it.isNotBlank() }.map(norm)
            val lines = when (change.kind) {
                "created" -> listOf(change.after!!.lines().map { "+$it" })
                "deleted" -> listOf(change.before!!.lines().map { "-$it" })
                else -> hunks(change.before!!, change.after!!, context = want.size).map { it.lines }
            }
            return lines.any { hunk ->
                val h = hunk.map(norm)
                (0..h.size - want.size).any { start -> want.indices.all { h[start + it] == want[it] } }
            }
        }
    }
}
