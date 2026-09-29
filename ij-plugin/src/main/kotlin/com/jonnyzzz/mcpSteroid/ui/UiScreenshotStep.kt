/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.jonnyzzz.mcpSteroid.server.UiCrop
import com.jonnyzzz.mcpSteroid.server.UiStep
import com.jonnyzzz.mcpSteroid.server.UiSteps
import com.jonnyzzz.mcpSteroid.server.UiStyle
import com.jonnyzzz.mcpSteroid.server.UiTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Rectangle
import java.awt.Window
import java.awt.image.BufferedImage
import javax.swing.JComponent

/** How many times a screenshot with fit looks for cut content: the room one step makes can show another cut. */
private const val FIT_ROUNDS = 2

/** The screenshot step: its highlights, fit, crop, the cut content it reports, and the picture it saves. */
internal class UiScreenshotStep(private val ctx: UiStepContext, private val finder: UiHighlightFinder) {
    private val project get() = ctx.project
    private val registry get() = ctx.registry
    private val forward get() = ctx.forward
    private val edtAny get() = ctx.edtAny
    private val artifacts get() = ctx.artifacts
    private val scenarioDir get() = ctx.scenarioDir
    private suspend fun resolve(target: UiTarget, timeoutMs: Long, requireEnabled: Boolean) = ctx.resolve(target, timeoutMs, requireEnabled)
    private suspend fun match(target: UiTarget) = ctx.match(target)
    private fun scopeWindows() = ctx.scopeWindows()
    private fun describeWindow(w: Window) = ctx.describeWindow(w)
    private suspend fun applyFix(fix: String) = ctx.applyFix(fix)

    /**
     * Saves a picture of the window that holds the target, or of the topmost window, with the popups open above it:
     * to `out`, or as `<save>.png` in the call's execution folder. The highlights are outlined, numbered when there are several, each
     * scrolled into the middle of its view first when it is out of view, and the crop cuts the picture to the
     * Settings page, the highlights or a control. `<name>.json` beside it records what makes two pictures of the same
     * state differ: the window's size, the scale, the theme, the editor font, the IDE build and the crop. A picture
     * that replaces a file says whether it changed.
     */
    suspend fun run(step: UiStep): String {
        val file = step.out?.let { UiCapturePaths.resolve(it, scenarioDir) }
            ?: (artifacts ?: throw UiStepFailure("screenshot has no folder to save to in this call")).resolve("screenshots").resolve(step.save!! + ".png")
        // A popup that a right click in an editor opens can show after that step's report: the picture and its
        // highlights wait for it, or a highlight of a menu item finds the main menu's item of the same name.
        UiSettle.settle()
        val node = step.target?.let { resolve(it, step.timeoutMs, requireEnabled = false) }
        val window = withContext(edtAny) { (node?.let { windowOf(it.component) } ?: scopeWindows().firstOrNull())?.let(UiCapture::pictured) }
            ?: throw UiStepFailure("no window is showing")
        // The highlights are what the picture is about: fit makes room for them, and lets other long lines stay cut. A
        // row's area is taken where it is found, so after a fit that resized something they are found again.
        val found = step.highlight.orEmpty().map { finder.locate(it, window, step.timeoutMs) }
        val made = if (step.fit) fitForPicture(step, window) { found.map { it.screenBounds() } } else emptyList()
        val highlights = if (made.isEmpty()) found else step.highlight.orEmpty().map { finder.locate(it, window, step.timeoutMs) }
        val essential = { highlights.map { it.screenBounds() } }
        val hostCuts = hostProblems(window, withContext(edtAny) { essential() })
        val cropOnBackend = (step.crop as? UiCrop.Control)?.let { finder.backendBounds(it.target, null, null, window)?.first }
        val cropControl = if (cropOnBackend != null) null else (step.crop as? UiCrop.Control)?.let { resolve(it.target, step.timeoutMs, requireEnabled = false) }
        withContext(edtAny) { highlights.forEach { it.bringIntoView() } }
        UiSettle.settle()
        val (canvas, facts, line) = withContext(edtAny) {
            if (!window.isShowing) throw UiStepFailure("${describeWindow(window)} closed before its picture")
            // Numbers give steps an order: several steps are numbered, a single one is only outlined, unless asked. A
            // click point on the outline of what was clicked is part of that step. Each mark takes its highlight's
            // arrow, outline, number and style, over the step's style; the highlights are found in their given order.
            val specs = step.highlight.orEmpty()
            val marks = UiCallouts.steps(highlights.mapIndexed { i, h ->
                val spec = specs.getOrNull(i)
                val style = (spec?.style ?: UiStyle()).over(step.style)
                UiCallouts.Mark(
                    i + 1, h.screenBounds(), h.label, h.pointer,
                    arrow = spec?.arrow, outline = spec?.outline ?: true, numberable = spec?.number ?: true,
                    color = java.awt.Color(style.color ?: UiStyle.DEFAULT_COLOR), width = (style.width ?: UiStyle.DEFAULT_WIDTH).toFloat(),
                )
            }, step.numbers)
            val numbered = marks.any { it.numbered }
            val obstacles = if (marks.isEmpty()) emptyList() else finder.textObstacles(window, highlights)
            // A picture of code shows the code, not where the caret happens to be.
            val codeEditors = highlights.filterIsInstance<CodeHighlight>().map { it.editor }.distinct()
            val showCarets = codeEditors.map(UiCodeRange::hideCaret)
            // The marks are laid out on the window as painted, before any mark: a callout looks for empty space in it.
            val raw = try {
                UiCapture.paint(window)
            } finally {
                showCarets.forEach { it() }
            }
            val painted = if (marks.isEmpty()) raw else UiCallouts.highlight(raw, marks, obstacles)
            // Code cut to its lines keeps their line numbers: the crop reaches left to the editor's gutter.
            fun withGutter(area: Rectangle) = codeEditors.fold(area) { a, editor ->
                val gutter = (editor as? com.intellij.openapi.editor.ex.EditorEx)?.gutterComponentEx?.takeIf { it.isShowing }
                val x = gutter?.locationOnScreen?.x
                if (x == null || x >= a.x) a else Rectangle(x, a.y, a.x + a.width - x, a.height)
            }
            val area = when (val crop = step.crop) {
                null -> null
                UiCrop.Page -> UiSettingsParts.page(window)?.let { UiCallouts.withMarks(raw, it, marks, obstacles) }
                    ?: throw UiStepFailure("crop \"page\" needs a Settings page, and ${describeWindow(window)} shows none")
                UiCrop.Highlights -> withGutter(UiCallouts.markArea(raw, marks, obstacles))
                UiCrop.Popups -> UiCapture.popupArea(window)?.let { area ->
                    // What a menu was opened from belongs with it: the click point, and the code it clicked.
                    withGutter(UiCapture.union(listOf(area) + if (marks.isEmpty()) emptyList() else listOf(UiCallouts.markArea(raw, marks, obstacles))))
                } ?: throw UiStepFailure("crop \"popups\" needs an open menu or popup above ${describeWindow(window)}")
                is UiCrop.ToolWindow -> {
                    val views = UiLayout.toolWindows(project)
                    val view = views.firstOrNull { it.id.equals(crop.id, ignoreCase = true) }
                        ?: throw UiStepFailure("no tool window ${crop.id} is showing; showing: ${views.joinToString { it.id }}")
                    val decorator = view.window.decorator
                    if (windowOf(decorator) !== window) throw UiStepFailure("the ${view.id} tool window is in another window than the picture")
                    UiCallouts.withMarks(raw, onScreen(decorator, Rectangle(0, 0, decorator.width, decorator.height)), marks, obstacles)
                }
                is UiCrop.Control -> if (cropOnBackend != null) UiCallouts.withMarks(raw, cropOnBackend, marks, obstacles) else {
                    val c = cropControl!!.component
                    if (windowOf(c) !== window) throw UiStepFailure("the crop ${crop.target} is in another window than the picture")
                    // A tree or list in a scroll pane is as tall as all its rows: the part in view is what shows.
                    val shown = (c as? JComponent)?.visibleRect ?: Rectangle(0, 0, c.width, c.height)
                    UiCallouts.withMarks(raw, shown.apply { translate(c.locationOnScreen.x, c.locationOnScreen.y) }, marks, obstacles)
                }
            }
            val canvas = area?.let { UiCapture.crop(painted, UiCapture.cropArea(it, step.margin ?: UiSteps.DEFAULT_MARGIN, painted.bounds)) } ?: painted
            val facts = UiPictureFacts.of(window)
            val arrows = if (marks.any { it.arrow != null }) UiCallouts.arrows(raw, marks, obstacles) else marks.map { null }
            val what = listOfNotNull(
                highlights.takeIf { it.isNotEmpty() }?.indices?.joinToString(", ", prefix = if (numbered) "highlights: " else "highlights, outlined without numbers: ") { i ->
                    val (h, m) = highlights[i] to marks[i]
                    when {
                        m.joined -> "${h.what}, a bare pointer on the outline it clicked"
                        m.numbered -> "${m.number} ${h.what}"
                        else -> h.what
                    } + arrows[i]?.let(::arrowNote).orEmpty()
                },
                step.crop?.let { "crop ${if (it is UiCrop.Control) it.target.toString() else it.toString()}" },
                "the caret is hidden in the picture".takeIf { codeEditors.isNotEmpty() },
                made.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "made room: "),
            )
            // What the picture shows cut, each with the step that fixes it, so a bad picture is known without reading it.
            // What the crop names, before its margin and the badges it grew by: those show the edge of what lies around
            // it, whose cuts are not the picture's.
            val cut = pictureProblems(window, if (area == null) canvas.bounds else pictureScope(step, window, marks.map { it.bounds }), hostCuts, essential())
                .map { "\ncut: " + it.line.removePrefix("layout: ") }
            Triple(canvas, facts, "saved ${canvas.image.width}x${canvas.image.height} picture of ${describeWindow(window)} to $file (${facts.describe()})" +
                what.joinToString("") { "; $it" } + cut.joinToString(""))
        }
        val change = withContext(Dispatchers.IO) {
            try {
                java.nio.file.Files.createDirectories(file.parent)
                val before = if (java.nio.file.Files.isRegularFile(file)) runCatching { javax.imageio.ImageIO.read(file.toFile()) }.getOrNull() else null
                val existed = java.nio.file.Files.exists(file)
                val format = UiCapturePaths.format(file)
                // A JPEG has no alpha channel: ImageIO writes nothing for an ARGB picture.
                val image = if (format == "png") canvas.image else BufferedImage(canvas.image.width, canvas.image.height, BufferedImage.TYPE_INT_RGB).also {
                    it.createGraphics().apply { drawImage(canvas.image, 0, 0, java.awt.Color.WHITE, null); dispose() }
                }
                java.nio.file.Files.newOutputStream(file).use { if (!javax.imageio.ImageIO.write(image, format, it)) throw java.io.IOException("no $format writer") }
                val json = file.resolveSibling(file.fileName.toString().substringBeforeLast('.') + ".json")
                java.nio.file.Files.writeString(json, facts.json(crop = step.crop?.let {
                    when (it) {
                        is UiCrop.Control -> "control"
                        is UiCrop.ToolWindow -> "toolwindow"
                        else -> it.toString()
                    }
                } ?: "window"))
                when {
                    !existed -> ""
                    before == null -> "; replaced a file that was not a readable picture"
                    // A JPEG never reads back as it was painted, so only a PNG is compared.
                    format != "png" -> ""
                    else -> when (val n = UiCapture.differingPixels(before, canvas.image)) {
                        0 -> "; unchanged"
                        Int.MAX_VALUE -> "; changed: the size was ${before.width}x${before.height}"
                        else -> "; changed: $n pixels differ"
                    }
                }
            } catch (e: java.io.IOException) {
                // An AccessDeniedException's message is only the path: its class says what went wrong.
                throw UiStepFailure("cannot write the picture to $file (${e.javaClass.simpleName}" + (e.message?.takeIf { it != file.toString() }?.let { ": $it" } ?: "") + ")")
            }
        }
        return line + change
    }

    /**
     * The layout problems of [window], and [host], the backend's of a host Settings page it shows, whose cut content
     * lies in [area], a picture's screen area. With [essential] areas, the highlights, content cut outside them does
     * not count. EDT.
     */
    /** Where an arrow went, for the report: its side and length, and what placement changed of a forced side. */
    private fun arrowNote(p: UiCallouts.ArrowPlan): String = ", arrow from ${p.side.wire} ${p.length}px" +
        (p.flippedFrom?.let { ", flipped from ${it.wire}" } ?: "") + (p.shortenedFrom?.let { ", shortened from ${it}px" } ?: "")

    private fun pictureProblems(
        window: Window, area: Rectangle, host: List<UiLayout.Problem> = emptyList(), essential: List<Rectangle> = emptyList(),
    ): List<UiLayout.Problem> =
        (UiLayout.problems(window, UiModel.build(window).root, { registry.refFor(it.component) }, project, essential) + host)
            .filter { it.area?.intersects(area) == true }

    /**
     * The layout problems of the showing windows, one line each, which a JetBrains Client reads for a host page; with
     * the step's `within` areas, only content cut in them counts.
     */
    suspend fun layoutReport(step: UiStep): String = withContext(edtAny) {
        val essential = step.within?.let(UiSteps::parseAreas).orEmpty()
        val lines = Window.getWindows().filter { it.isShowing }.flatMap { w ->
            UiLayout.problems(w, UiModel.build(w).root, { registry.refFor(it.component) }, project, essential).map { UiHostLayout.encode(it, w.size) }
        }
        if (lines.isEmpty()) "no layout problems" else lines.joinToString("\n")
    }

    /**
     * In a JetBrains Client showing a host Settings page, the backend's layout problems of the page, with the fixes the
     * Client runs; none elsewhere. With [essential] areas, the highlights, only content cut in them counts. A backend
     * that does not answer leaves the picture without them rather than failing it.
     */
    private suspend fun hostProblems(window: Window, essential: List<Rectangle>): List<UiLayout.Problem> {
        val forward = forward ?: return emptyList()
        if (!withContext(edtAny) { UiSettingsParts.hostPage(window) }) return emptyList()
        val step = UiSteps.parse(JsonArray(listOf(buildJsonObject {
            put("action", "get")
            put("layout", true)
            if (essential.isNotEmpty()) put("within", essential.joinToString(";") { "${it.x},${it.y},${it.width},${it.height}" })
            put("side", "backend")
        }))).single()
        val report = forward.invoke(step).takeIf { it.passed } ?: return emptyList()
        val client = withContext(edtAny) { window.size }
        return UiHostLayout.decode(report.text).map { p ->
            val fix = UiHostLayout.clientFix(p.fix, p.window, client)
            UiLayout.Problem(if (p.fix != null && fix != null) p.line.replace(p.fix, fix) else p.line, fix, null, p.area)
        }
    }

    /**
     * For a screenshot with fit: runs the steps that make room for what the picture would show cut, the part the crop
     * names or the whole window, and looks again once, as the room one step makes can show another cut. With
     * highlights, [essential] gives their screen areas, and only content cut in them counts. The restores go with the
     * step's. Returns what each step did.
     */
    private suspend fun fitForPicture(step: UiStep, window: Window, essential: () -> List<Rectangle>): List<String> {
        val done = mutableListOf<String>()
        val tried = mutableSetOf<String>()
        val before = withContext(edtAny) { window.width }
        repeat(FIT_ROUNDS) {
            val areas = withContext(edtAny) { essential() }
            val host = hostProblems(window, areas)
            val fixes = withContext(edtAny) {
                val scope = pictureScope(step, window)
                pictureProblems(window, scope, host, areas).mapNotNull { it.fix }
                    .mapNotNull { UiLayout.capWindowFix(it, before, window.width) }.filter { tried.add(it) }
            }
            if (fixes.isEmpty()) return done
            fixes.forEach { done += applyFix(it) }
            UiSettle.settle()
        }
        return done
    }

    /**
     * The screen area a screenshot's crop names, without its margin and badges: a control, a tool window, the Settings
     * page, the open popups, the highlights at [marks], or the window. EDT.
     */
    private suspend fun pictureScope(step: UiStep, window: Window, marks: List<Rectangle> = emptyList()): Rectangle = when (val crop = step.crop) {
        UiCrop.Popups -> UiCapture.popupArea(window)?.let { UiCapture.union(listOf(it) + marks) }
        UiCrop.Highlights -> marks.takeIf { it.isNotEmpty() }?.let(UiCapture::union)
        is UiCrop.Control -> match(crop.target).let { m -> (m as? UiMatch.One)?.node?.component?.takeIf { it.isShowing }?.let { onScreen(it, Rectangle(0, 0, it.width, it.height)) } }
        is UiCrop.ToolWindow -> UiLayout.toolWindows(project).firstOrNull { it.id.equals(crop.id, ignoreCase = true) }?.window?.decorator
            ?.takeIf { it.isShowing }?.let { onScreen(it, Rectangle(0, 0, it.width, it.height)) }
        UiCrop.Page -> UiSettingsParts.page(window)
        else -> null
    } ?: Rectangle(window.locationOnScreen, window.size)
}
