/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.JsonObject

enum class UiAction(val wire: String) {
    CLICK("click"),
    HOVER("hover"),
    TYPE("type"),
    FILL("fill"),
    PRESS("press"),
    CHECK("check"),
    UNCHECK("uncheck"),
    SELECT("select"),
    CLOSE("close"),
    WAIT("wait"),
    SNAPSHOT("snapshot"),
    GOTO("goto"),
    RUN("run"),
    INSPECT("inspect"),
    SCROLL("scroll"),
    EXPECT("expect"),
    SETTINGS("settings"),
    GET("get"),
    SET("set"),
    TOOLWINDOW("toolwindow"),
    WINDOW("window"),
    MENU("menu"),
    WRITE("write"),
    PERF("perf"),
    CODE("code"),
    SCREENSHOT("screenshot"),
    SPLITTER("splitter"),
}

enum class UiWaitCondition(val wire: String) {
    VISIBLE("visible"),
    HIDDEN("hidden"),
    ENABLED("enabled"),
    WINDOW("window"),
    IDLE("idle"),
}

/** The state an expect step checks with "is". The row states need a "row" or an "index". */
enum class UiExpectState(val wire: String, val ofRow: Boolean = false) {
    VISIBLE("visible"),
    HIDDEN("hidden"),
    ENABLED("enabled"),
    DISABLED("disabled"),
    CHECKED("checked"),
    UNCHECKED("unchecked"),
    FOCUSED("focused"),
    EDITABLE("editable"),
    SELECTED("selected", ofRow = true),
    EXPANDED("expanded", ofRow = true),
    COLLAPSED("collapsed", ofRow = true),
}

/** What a step acts on. Every given field must match; [nth] picks one of several matches, from 0. */
data class UiTarget(
    val ref: String? = null,
    val name: String? = null,
    val text: String? = null,
    val cls: String? = null,
    val xpath: String? = null,
    val nth: Int? = null,
) {
    override fun toString(): String = listOfNotNull(
        ref?.let { "ref=$it" },
        name?.let { "name=\"$it\"" },
        text?.let { "text=\"$it\"" },
        cls?.let { "class=$it" },
        xpath?.let { "xpath=$it" },
        nth?.let { "nth=$it" },
    ).joinToString(" ")
}


/** What a screenshot shows of its window: the Settings page, the area of its highlights, the open popups, or one control. */
sealed interface UiCrop {
    object Page : UiCrop {
        override fun toString() = "page"
    }
    object Highlights : UiCrop {
        override fun toString() = "highlights"
    }
    /** The menus and popups open above the window, such as a main menu a menu step with show opened. */
    object Popups : UiCrop {
        override fun toString() = "popups"
    }
    data class Control(val target: UiTarget) : UiCrop
    /** A tool window by its id, whatever its selected tab names it. */
    data class ToolWindow(val id: String) : UiCrop {
        override fun toString() = "toolwindow $id"
    }
}

data class UiStep(
    val action: UiAction,
    val target: UiTarget?,
    val button: String? = null,
    val count: Int = 1,
    val modifiers: String? = null,
    val offsetX: Int? = null,
    val offsetY: Int? = null,
    val text: String? = null,
    val keys: String? = null,
    val row: String? = null,
    val index: Int? = null,
    /** For scroll: how many pages to scroll the target's scroll pane, down when positive. */
    val pages: Int? = null,
    val condition: UiWaitCondition? = null,
    val title: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val symbol: String? = null,
    val id: String? = null,
    /** Which occurrence a goto symbol or text means, from 0; a target carries its own. */
    val nth: Int = 0,
    val timeoutMs: Long = UiSteps.DEFAULT_TIMEOUT_MS,
    /** What the step is for, in the words of the report it reproduces. Echoed in the step's report. */
    val intent: String? = null,
    /** On an expect or code step: the bug that is present when the step fails. */
    val bug: String? = null,
    /** On an expect step: a failure is reported and the run goes on. */
    val soft: Boolean = false,
    /** On an expect step: the check must not hold. */
    val negate: Boolean = false,
    val state: UiExpectState? = null,
    val value: String? = null,
    val contains: String? = null,
    val matches: String? = null,
    /** On an expect step: how many controls the target matches. */
    val expectCount: Int? = null,
    /** On an expect step with a file: the caret as `line:column`, both 1-based. */
    val caret: String? = null,
    val notification: String? = null,
    /** On an expect step: a banner above an open editor whose text contains this, such as "Module JDK is not defined". */
    val banner: String? = null,
    val error: String? = null,
    /** On an expect step: a file whose editor the IDE shows, by its path or name. */
    val editor: String? = null,
    /**
     * On an expect step: a text of an `idea.log` line written since the run started. On a get or a set: a log
     * category, such as `#com.jetbrains.rdserver.fileEditors`, whose level a set changes.
     */
    val log: String? = null,
    /** On a get step: the open editors, per side and per JetBrains Client session. */
    val editors: Boolean = false,
    /** On a get step: this side's memory, as the status bar's memory indicator shows it, and the GC's load. */
    val memory: Boolean = false,
    /** On an expect step: the memory figure it checks, one of [UiSteps.MEMORY_METRICS], against [below]. */
    val memoryMetric: String? = null,
    /** On an expect of memory: the figure must be under this, in MB for sizes. */
    val below: Long? = null,
    /** On a get step: the builds and syncs that finished, with the errors of each. */
    val builds: Boolean = false,
    /** On a get step: the notifications the IDE showed, as the Notifications tool window lists them. */
    val notifications: Boolean = false,
    /** On a get step: the problems the editor highlights in an open file by its path, or in every open file for "". */
    val problems: String? = null,
    /** On a get of problems: the least severe level to list, one of [UiSteps.SEVERITIES]; errors only without it. */
    val severity: String? = null,
    /** On a get step: the diff of the project files the run changed so far. */
    val changes: Boolean = false,
    /**
     * On a get or expect step: the output of the latest run by its name, as its Run or Debug tab shows it, such as
     * "App", or "" for the latest run. A get reads its last [lines] lines; an expect checks it with contains or matches.
     */
    val console: String? = null,
    /** On a get of a console: how many of its last lines to read. */
    val lines: Int? = null,
    /** On an expect step: exactly the project files the run changed, created, deleted or moved; empty for none. */
    val changed: List<String>? = null,
    /**
     * On an expect step: lines the run's diff has, one after another, each starting with `+` (added), `-` (removed) or a
     * space (unchanged), matched without their indentation; with a file, in that file's diff.
     */
    val diff: String? = null,
    /** On an expect step with a file: a file holding the whole text the file must have, relative to the project. */
    val golden: String? = null,
    val page: String? = null,
    val registry: String? = null,
    val advanced: String? = null,
    /** A get or set of an option as Search Everywhere lists it, such as "Show line numbers". */
    val option: String? = null,
    /** A get or set of an inspection in the project's current profile, by its short name. */
    val inspection: String? = null,
    /** A get or set of a persistent settings component by its state name, such as "EditorSettings". */
    val component: String? = null,
    /** The option of [component] a get reads or a set changes. */
    val field: String? = null,
    /** On a toolwindow step: the content tab to select. */
    val tab: String? = null,
    /** On a toolwindow step: hide the tool window instead of showing it. */
    val hide: Boolean = false,
    /** On a toolwindow or window step: the width in logical pixels, or "fit". */
    val width: String? = null,
    /** On a toolwindow or window step: the height in logical pixels, or "fit". */
    val height: String? = null,
    /** On a window step: true fills the screen, false gives the window back its size before. */
    val maximize: Boolean? = null,
    /**
     * On an expect step: no control in the window, or under the target, lies past an edge. On a get step: the layout
     * problems of the showing windows, one JSON line each.
     */
    val layout: Boolean = false,
    /**
     * On a get of the layout: the screen areas, `x,y,width,height;...`, that must show whole, such as a picture's
     * highlights; content cut outside them does not count.
     */
    val within: String? = null,
    /**
     * On a menu step: the item or submenu, as `View > Appearance > Compact Mode`. On a check or uncheck step: a
     * checkable main menu item, which the step runs only when its state differs.
     */
    val path: String? = null,
    /** On a menu step: how the IDE shows its main menu, one of [UiSteps.MENU_MODES]. */
    val mode: String? = null,
    /** On a write step: delete the file instead of writing it. */
    val delete: Boolean = false,
    /** On a screenshot step: the picture's file name in the execution folder, without folder or extension. */
    val save: String? = null,
    /**
     * On a screenshot step: the picture's path, a PNG, or a JPEG for a `.jpg` path; relative to the scenario file's
     * folder in a scenario.
     */
    val out: String? = null,
    /** On a screenshot step: the controls to outline, numbered in this order. */
    val highlight: List<UiHighlight>? = null,
    /** On a screenshot step: what part of the window the picture shows; the whole window without it. */
    val crop: UiCrop? = null,
    /** On a screenshot step with a crop: the padding around it, in pixels; [UiSteps.DEFAULT_MARGIN] without it. */
    val margin: Int? = null,
    /** On a scroll step: where the target goes in its view, one of [UiSteps.ALIGNS]. */
    val align: String? = null,
    /** On a menu step: open the path's menus and leave them open instead of running an item. */
    val show: Boolean = false,
    /** On a set step: the installed theme to switch to, by name or id, or [UiSteps.THEME_SYNC]. */
    val theme: String? = null,
    /** On a get step: the installed themes. */
    val themes: Boolean = false,
    /** On a window step: the key the IDE saves the window's size under, which the step writes too; a restore uses it. */
    val dimension: String? = null,
    /** On a splitter step: the first pane's share, one of [UiSteps.PROPORTIONS]. */
    val proportion: Double? = null,
    /** On a splitter step: the pane's size along the splitter's axis in logical pixels, or "fit". */
    val size: String? = null,
    /** On a splitter step: the key a `JBSplitter` saves its proportion under, which a restore writes. */
    val key: String? = null,
    /** On a screenshot step: make room for content the picture would show cut, and put the sizes back afterwards. */
    val fit: Boolean = false,
    /**
     * On a screenshot step: number the highlights 1, 2, 3, as steps to follow in order, or outline them only; without
     * it, several highlights are numbered and a single one is not.
     */
    val numbers: Boolean? = null,
    /**
     * Split Mode: `backend` runs the step on the Remote Development backend, where the project, its files and the
     * windows the backend draws are; the call's own side otherwise. Ignored in a regular IDE.
     */
    val side: String? = null,
    val command: String? = null,
    val code: String? = null,
    val modal: String? = null,
    /** The step as it was written, which a recording rewrites into a portable step. */
    val source: JsonObject? = null,
)
