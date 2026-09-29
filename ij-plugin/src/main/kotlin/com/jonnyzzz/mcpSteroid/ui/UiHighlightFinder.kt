/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.ui.UIUtil
import com.jonnyzzz.mcpSteroid.server.UiAction
import com.jonnyzzz.mcpSteroid.server.UiHighlight
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiSteps
import com.jonnyzzz.mcpSteroid.server.UiTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.SwingUtilities

/**
 * A highlight found: where to outline it, how to name it, and its label. [component] is the control it outlines,
 * whose own text is no obstacle to its badge; [pointer] draws it as the point of a click.
 */
internal sealed class Located(val what: String, val label: String?, val component: Component? = null, val pointer: Boolean = false) {
    /** Scrolls the highlight to the middle of its view when part of it is out of view. EDT. */
    abstract fun bringIntoView()

    /** EDT. */
    abstract fun screenBounds(): Rectangle
}

/**
 * A highlight in this process: its component and the area of it to outline, in its coordinates. With [visibleOnly],
 * a control larger than its view, the outline covers the part that shows when the picture is taken.
 */
internal class LocalHighlight(
    val target: Component, val area: Rectangle, what: String, label: String?, val visibleOnly: Boolean = false,
) : Located(what, label, target) {
    override fun bringIntoView() {
        if (!visibleOnly && !UiScrollAlign.inView(target, area)) UiScrollAlign.scroll(target, area, "center")
    }

    override fun screenBounds(): Rectangle =
        onScreen(target, if (visibleOnly) (target as? JComponent)?.visibleRect ?: area else area)
}

/** A highlight on a host Settings page, which the backend found and scrolled into view: its screen bounds. */
internal class BackendHighlight(val bounds: Rectangle, what: String, label: String?) : Located(what, label) {
    override fun bringIntoView() = Unit
    override fun screenBounds(): Rectangle = Rectangle(bounds)
}

/** Lines or a symbol of code: an area of [editor]'s content, which the editor scrolls into the middle of its view. */
internal class CodeHighlight(val editor: Editor, val area: Rectangle, what: String, label: String?) : Located(what, label, editor.contentComponent) {
    override fun bringIntoView() = UiCodeRange.bringIntoView(editor, area, what)
    override fun screenBounds(): Rectangle = onScreen(editor.contentComponent, area)
}

/** The screen point of the call's last click, drawn as a pointer. */
internal class PointHighlight(val point: Point, label: String?) : Located("the point of the last click", label, pointer = true) {
    override fun bringIntoView() = Unit
    override fun screenBounds(): Rectangle = Rectangle(point.x, point.y, 1, 1)
}

/** The tree table of Settings | Editor | Inspections, which an inspection highlight searches. */
internal const val INSPECTIONS_TREE = "InspectionsConfigTreeTable"
/** The row path in a select step's report, as `selected row #70 "A > B > C" in ...`. */
private val SELECTED_PATH = Regex("""selected row #\d+ "(.+?)" in """)
/** The most text controls a picture's badges keep off: an IDE window shows a few hundred. */
private const val MAX_OBSTACLES = 2_000

/** Finds the highlights of a screenshot: controls, rows, code, the last click, inspections and console lines. */
internal class UiHighlightFinder(private val ctx: UiStepContext) {
    private val project get() = ctx.project
    private val forward get() = ctx.forward
    private val edtAny get() = ctx.edtAny
    private val lastClick get() = ctx.lastClick
    private suspend fun resolve(target: UiTarget, timeoutMs: Long, requireEnabled: Boolean) = ctx.resolve(target, timeoutMs, requireEnabled)
    private suspend fun match(target: UiTarget) = ctx.match(target)
    private suspend fun pickRow(node: UiNode, step: UiStep) = ctx.pickRow(node, step)
    private fun describe(node: UiNode) = ctx.describe(node)
    private fun describeWindow(w: Window) = ctx.describeWindow(w)

    /**
     * Finds [h] in [window] or a popup above it: the Settings breadcrumb, a row of a list, tree or table, or a control.
     * A control in another window, or one that is not showing, fails the step: its outline would land elsewhere.
     */
    suspend fun locate(h: UiHighlight, window: Window, timeoutMs: Long): Located {
        if (h.lines != null || h.symbol != null) {
            val file = h.file?.let { path -> withContext(Dispatchers.IO) { CodeLocation.findFile(project, path) } ?: throw UiStepFailure("file not found: $path") }
            return withContext(edtAny) {
                val editor = highlightEditor(file, h.file, window)
                val name = editor.virtualFile?.name ?: "the editor"
                if (h.lines != null) CodeHighlight(editor, UiCodeRange.linesArea(editor, UiSteps.parseLines(h.lines!!)), "lines ${h.lines} of $name", h.label)
                else CodeHighlight(editor, UiCodeRange.symbolArea(editor, h.symbol!!, h.nth ?: 0), "\"${h.symbol}\" in $name", h.label)
            }
        }
        if (h.click) return withContext(edtAny) {
            val (clicked, point) = lastClick ?: throw UiStepFailure("a click highlight marks the call's last click, and no step clicked before it")
            if (clicked !== window && clicked !in UiCapture.popupsOf(window)) {
                throw UiStepFailure("the last click was in ${describeWindow(clicked)}, not in the pictured ${describeWindow(window)}")
            }
            PointHighlight(point, h.label)
        }
        h.inspection?.let { return inspectionHighlight(it, h.label, window, timeoutMs) }
        h.console?.let { name -> return withContext(edtAny) { consoleHighlight(name, h.contains!!, h.nth ?: 0, h.label, window) } }
        if (h.breadcrumb) return withContext(edtAny) {
            val bar = UiSettingsParts.breadcrumbs(window) ?: throw UiStepFailure("no Settings page is showing, so there is no breadcrumb to highlight")
            val crumbs = UiSettingsParts.crumbsBounds(bar)
            LocalHighlight(bar, Rectangle(0, 0, crumbs.width, crumbs.height), "breadcrumb", h.label)
        }
        backendBounds(h.target!!, h.row, h.index, window)?.let { (bounds, what) -> return BackendHighlight(bounds, what, h.label) }
        val node = resolve(h.target!!, timeoutMs, requireEnabled = false)
        val pick = pickRow(node, UiStep(UiAction.SCREENSHOT, h.target, row = h.row, index = h.index, timeoutMs = timeoutMs))
        return withContext(edtAny) {
            val c = node.component
            if (!c.isShowing) throw UiStepFailure("${describe(node)} is not showing; select its tab or page first")
            val owner = windowOf(c)
            if (owner !== window && owner !in UiCapture.popupsOf(window)) {
                throw UiStepFailure("${describe(node)} is in ${owner?.let { describeWindow(it) } ?: "no window"}, not in the pictured ${describeWindow(window)}")
            }
            val what = pick?.let { "row #${it.index} \"${it.text.take(60)}\" of ${describe(node)}" } ?: describe(node)
            if (pick != null) {
                val row = UiRows.bounds(c, pick.index) ?: throw UiStepFailure("${describe(node)} shows its items in a popup; open it first")
                LocalHighlight(c, row, what, h.label)
            } else {
                val (area, scroll) = UiScrollAlign.wholeAreaOf(c)
                // A control that fits its view is outlined whole; one larger than its view as far as it shows.
                LocalHighlight(c, area, what, h.label, visibleOnly = !scroll && area != Rectangle(0, 0, c.width, c.height))
            }
        }
    }

    /**
     * The editor a code highlight names: the one of [file] that shows, or the selected one without a file; either must
     * be in [window]. EDT.
     */
    private fun highlightEditor(file: com.intellij.openapi.vfs.VirtualFile?, path: String?, window: Window): Editor {
        val manager = FileEditorManager.getInstance(project)
        val editor = if (file == null) manager.selectedTextEditor ?: throw UiStepFailure("no editor is selected; open the file with a goto step first")
        else manager.getAllEditors(file).filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>().map { it.editor }.firstOrNull { it.contentComponent.isShowing }
            ?: throw UiStepFailure("$path shows in no editor; open it with a goto step first")
        if (!SwingUtilities.isDescendingFrom(editor.contentComponent, window)) {
            throw UiStepFailure("the editor of ${editor.virtualFile?.name ?: "the file"} is not in the pictured ${describeWindow(window)}")
        }
        return editor
    }

    /**
     * The row of inspection [shortName] on the Inspections page: its group path and display name, as the tree shows
     * them, which the row search expands.
     */
    private suspend fun inspectionHighlight(shortName: String, label: String?, window: Window, timeoutMs: Long): Located =
        locate(UiHighlight(UiTarget(cls = INSPECTIONS_TREE), row = inspectionPath(shortName), label = label), window, timeoutMs)

    /**
     * The row path of inspection [shortName] in the Inspections tree: its groups, then its display name. A JetBrains
     * Client's Inspections page is the backend's, and the Client's own profile may lack the inspection or name its
     * groups otherwise: the backend selects the row there, and its report names the path.
     */
    suspend fun inspectionPath(shortName: String): String {
        backendInspectionSelect(shortName)?.let { report ->
            return SELECTED_PATH.find(report)?.groupValues?.get(1) ?: throw UiStepFailure("the backend selected the inspection but named no row: $report")
        }
        return withContext(edtAny) { localInspection(shortName) }
            ?: throw UiStepFailure("no inspection has the short name \"$shortName\"; a get of an inspection lists short names as it finds them")
    }

    /** The row path of inspection [shortName] from this side's profile, or null when it has none. EDT. */
    private fun localInspection(shortName: String): String? =
        com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project).currentProfile.getInspectionTool(shortName, project)
            ?.let { tool -> (tool.groupPath.toList() + tool.displayName).joinToString(UiRows.PATH_SEPARATOR) }

    /** In a JetBrains Client, the report of a select of inspection [shortName] run on the backend, or null elsewhere. */
    suspend fun backendInspectionSelect(shortName: String): String? {
        val forward = forward ?: return null
        val step = UiSteps.parse(JsonArray(listOf(buildJsonObject {
            put("action", "select")
            put("inspection", shortName)
            put("side", "backend")
        }))).single()
        val report = forward.invoke(step)
        if (!report.passed) throw UiStepFailure("on the backend: ${report.text}")
        return report.text
    }

    /**
     * The lines of the console of run [name] that hold [text], the last of them unless [nth] counts back further, in a
     * console built on an editor. EDT.
     */
    private fun consoleHighlight(name: String, text: String, nth: Int, label: String?, window: Window): Located {
        val descriptors = com.intellij.execution.ui.RunContentManager.getInstance(project).allDescriptors
        val descriptor = descriptors.lastOrNull { it.displayName == name } ?: descriptors.lastOrNull { it.displayName.contains(name, ignoreCase = true) }
            ?: throw UiStepFailure("no run named \"$name\" has a console; runs: ${descriptors.joinToString { "\"${it.displayName}\"" }}")
        val console = descriptor.executionConsole
        (console as? com.intellij.terminal.TerminalExecutionConsole)?.let { return terminalLine(it, descriptor.displayName, text, nth, label, window) }
        val editor = (console as? com.intellij.execution.impl.ConsoleViewImpl)?.editor
            ?: throw UiStepFailure("the console of '${descriptor.displayName}' is a ${console?.let { UiComponentFacts.simpleClassName(it.component) } ?: "console"} without an editor; outline it with a locator")
        if (!editor.contentComponent.isShowing) throw UiStepFailure("the console of '${descriptor.displayName}' is not showing; show its tab first")
        val doc = editor.document
        val lines = (0 until doc.lineCount).filter { line ->
            doc.charsSequence.subSequence(doc.getLineStartOffset(line), doc.getLineEndOffset(line)).contains(text)
        }
        val line = lines.reversed().getOrNull(nth)
            ?: throw UiStepFailure(if (lines.isEmpty()) "no line of the console of '${descriptor.displayName}' holds \"$text\"" else "\"$text\" is on ${lines.size} lines; nth $nth asked")
        if (!SwingUtilities.isDescendingFrom(editor.contentComponent, window)) throw UiStepFailure("the console of '${descriptor.displayName}' is not in the pictured ${describeWindow(window)}")
        return CodeHighlight(editor, UiCodeRange.linesArea(editor, (line + 1)..(line + 1)), "line ${line + 1} of the console of '${descriptor.displayName}'", label)
    }

    /**
     * The lines of a terminal-based console, as a Node.js run shows, that hold [text], among those on its screen: the
     * last of them unless [nth] counts back further. Its cells are the panel's size shared out over the screen's
     * columns and rows. EDT.
     */
    private fun terminalLine(console: com.intellij.terminal.TerminalExecutionConsole, name: String, text: String, nth: Int, label: String?, window: Window): Located {
        val panel = console.terminalWidget.terminalPanel
        if (!panel.isShowing) throw UiStepFailure("the console of '$name' is not showing; show its tab first")
        if (!SwingUtilities.isDescendingFrom(panel, window)) throw UiStepFailure("the console of '$name' is not in the pictured ${describeWindow(window)}")
        val buffer = panel.terminalTextBuffer
        // Line by line: the buffer's screen text call is newer than the oldest IDE build the plugin supports.
        val screen = (0 until buffer.height).map { buffer.getLine(it).text }
        val lines = screen.indices.filter { screen[it].contains(text) }
        val line = lines.reversed().getOrNull(nth)
            ?: throw UiStepFailure(if (lines.isEmpty()) "no line on the screen of the console of '$name' holds \"$text\"; scroll it to the line first" else "\"$text\" is on ${lines.size} lines; nth $nth asked")
        // In fractions: at a HiDPI scale a cell is not a whole number of logical pixels, and rounding each loses a character.
        val cellHeight = panel.pixelHeight.toDouble() / maxOf(1, buffer.height)
        val cellWidth = panel.pixelWidth.toDouble() / maxOf(1, buffer.width)
        // One cell more: the terminal paints a glyph a little wider than its cell, so the last character reaches past it.
        val width = kotlin.math.ceil((screen[line].trimEnd().length + 1) * cellWidth).toInt()
        val top = (line * cellHeight).toInt()
        return LocalHighlight(panel, Rectangle(0, top, width, kotlin.math.ceil((line + 1) * cellHeight).toInt() - top), "line ${line + 1} of the screen of the console of '$name'", label)
    }

    /**
     * In a JetBrains Client showing a host Settings page, whose controls exist only on the backend, the screen bounds of
     * [target] (or its row) as the backend finds them after scrolling it to the middle of its view, and how the backend
     * names it. Null when this is no JetBrains Client, no host page shows, or the Client has a match of its own.
     */
    suspend fun backendBounds(target: UiTarget, row: String?, index: Int?, window: Window): Pair<Rectangle, String>? {
        val forward = forward ?: return null
        if (!withContext(edtAny) { UiSettingsParts.hostPage(window) } || match(target) !is UiMatch.None) return null
        val source = buildJsonObject {
            put("action", "scroll")
            target.ref?.let { put("ref", it) }
            target.name?.let { put("name", it) }
            target.text?.let { put("text", it) }
            target.cls?.let { put("class", it) }
            target.xpath?.let { put("xpath", it) }
            target.nth?.let { put("nth", it) }
            row?.let { put("row", it) }
            index?.let { put("index", it) }
            put("align", "center")
            put("side", "backend")
        }
        val step = UiSteps.parse(JsonArray(listOf(source))).single()
        val report = forward.invoke(step)
        if (!report.passed) throw UiStepFailure("on the backend's host page: ${report.text}")
        val bounds = UiScrollAlign.parseBounds(report.text) ?: throw UiStepFailure("the backend gave no screen bounds: ${report.text}")
        return bounds to "${UiScrollAlign.parseWhat(report.text) ?: target} on the backend's host page"
    }

    /**
     * The screen bounds of the text other controls in [window] and its popups show, such as neighbouring tabs, and of
     * the text of the tree rows in view, which a badge or label should not cover and a shaft should not cross. The
     * highlighted controls and what holds them are left out; text inside a
     * highlight's outline is its own, which the layout leaves out, while the other tabs of a highlighted tab row are
     * obstacles, and so are the lines of code in view around a code highlight. EDT.
     */
    fun textObstacles(window: Window, highlights: List<Located>): List<Rectangle> {
        val marked = highlights.mapNotNull { it.component }
        // The code around a code highlight is what the picture is about: its lines in view are obstacles too.
        val code = highlights.filterIsInstance<CodeHighlight>().map { it.editor }.distinct().flatMap { editor ->
            val view = editor.scrollingModel.visibleArea
            val lines = editor.xyToLogicalPosition(view.location).line..editor.xyToLogicalPosition(java.awt.Point(view.x, view.y + view.height)).line
            UiCodeRange.textSpans(editor, lines).map { onScreen(editor.contentComponent, it) }
        }
        val components = (listOf(window) + UiCapture.popupsOf(window)).asSequence().flatMap { UIUtil.uiTraverser(it).asSequence() }
        // A tree paints its rows' text without components, highlighted tree or not; a highlighted row's own text lies
        // inside its outline, which the layout leaves out.
        val rows = components.filterIsInstance<JTree>().filter { it.isShowing }
            .flatMap { tree -> UiRows.rowTexts(tree).map { onScreen(tree, it) } }
        return code + (components
            .filter { c ->
                c.isShowing && c.width > 0 && c.height > 0 && showsText(c) && marked.none { m -> SwingUtilities.isDescendingFrom(m, c) }
            }
            .map { onScreen(it, Rectangle(0, 0, it.width, it.height)) } + rows)
            .take(MAX_OBSTACLES)
            .toList()
    }

    private fun showsText(c: Component): Boolean = when (c) {
        is javax.swing.JLabel -> !c.text.isNullOrBlank()
        is SimpleColoredComponent -> c.getCharSequence(false).isNotBlank()
        is AbstractButton -> !c.text.isNullOrBlank()
        else -> false
    }
}
