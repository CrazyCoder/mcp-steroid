/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.ui.ScreenUtil
import com.jonnyzzz.mcpSteroid.server.UiSteps
import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window

/**
 * Sizes a tool window or a window as a person drags its edge: to a size in logical pixels, or to "fit", the size that
 * shows its content. A window stays on its screen.
 */
object UiResize {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /**
     * Sets [view]'s width, for a side tool window, or height, for a top or bottom one. The other one comes from the IDE
     * window, and the step says so. A floating or windowed tool window is sized as its own window.
     */
    suspend fun toolWindow(view: UiLayout.ToolWindowView, width: String?, height: String?): String {
        val type = withContext(edtAny) { view.window.type }
        if (type == ToolWindowType.FLOATING || type == ToolWindowType.WINDOWED) {
            val window = withContext(edtAny) { javax.swing.SwingUtilities.getWindowAncestor(view.window.component) }
                ?: throw UiStepFailure("the ${view.id} tool window is $type but shows in no window of its own")
            return window(window, width, height, maximize = null)
        }
        val wanted = if (view.axis == "width") width else height
        val other = if (view.axis == "width") height else width
        if (wanted == null) {
            throw UiStepFailure("the ${view.id} tool window is docked at the ${if (view.axis == "width") "side" else "top or bottom"}, " +
                "so its ${if (view.axis == "width") "height" else "width"} comes from the IDE window; set its ${view.axis}")
        }
        val (before, target) = withContext(edtAny) {
            val before = view.size
            val target = if (wanted == UiSteps.FIT) view.fit() else wanted.toInt()
            if (view.axis == "width") view.window.stretchWidth(target - before) else view.window.stretchHeight(target - before)
            before to target
        }
        UiSettle.barrier()
        return withContext(edtAny) {
            val now = view.size
            buildString {
                append("the ${view.id} tool window's ${view.axis} is now $now px, was $before px")
                if (wanted == UiSteps.FIT) append("; fit is $target px: its header needs ${view.needs} px")
                if (now < target - 2) append("; it asked for $target px, and the IDE window leaves no more room")
                if (other != null) append("; its ${if (view.axis == "width") "height" else "width"} comes from the IDE window, so ${other} was left alone")
            }
        }
    }

    /**
     * Sizes [window]. [maximize] true fills the screen and false gives a frame back its size before. A size of "fit"
     * grows the window to its preferred size, which shows its content; with neither size nor maximize, the IDE window
     * fills the screen and any other window fits. A window never shrinks below its minimum size or grows past its
     * screen, and the report says when either one held it back.
     */
    suspend fun window(window: Window, width: String?, height: String?, maximize: Boolean?): String {
        val notes = mutableListOf<String>()
        val line = withContext(edtAny) {
            val before = window.bounds
            val screen = ScreenUtil.getScreenRectangle(window)
            val frame = window as? Frame
            val fill = maximize == true || maximize == null && width == null && height == null && window is IdeFrame
            when {
                fill -> {
                    frame ?: throw UiStepFailure("only a frame fills the screen; give a dialog or a popup a width and a height, or \"fit\"")
                    frame.extendedState = frame.extendedState or Frame.MAXIMIZED_BOTH
                    "maximized"
                }
                maximize == false -> {
                    frame ?: throw UiStepFailure("maximize goes with a frame; a dialog or a popup takes a width and a height")
                    frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
                    "restored"
                }
                else -> {
                    // A maximized frame ignores a new size until it is restored.
                    if (frame != null && frame.extendedState and Frame.MAXIMIZED_BOTH != 0) frame.extendedState = frame.extendedState and Frame.MAXIMIZED_BOTH.inv()
                    val fitAll = width == null && height == null
                    val w = size(width ?: UiSteps.FIT.takeIf { fitAll }, before.width, window.preferredSize.width, window.minimumSize.width, screen.width)
                    val h = size(height ?: UiSteps.FIT.takeIf { fitAll }, before.height, window.preferredSize.height, window.minimumSize.height, screen.height)
                    window.bounds = Rectangle(
                        before.x.coerceAtMost(screen.x + screen.width - w).coerceAtLeast(screen.x),
                        before.y.coerceAtMost(screen.y + screen.height - h).coerceAtLeast(screen.y),
                        w, h,
                    )
                    window.validate()
                    fun asked(side: String?, limit: (Int) -> Boolean) = side?.toIntOrNull()?.let(limit) == true
                    val min = window.minimumSize
                    val pref = window.preferredSize
                    notes += listOfNotNull(
                        "held at its minimum size, ${min.width}x${min.height}".takeIf { asked(width) { it < min.width } || asked(height) { it < min.height } },
                        "held at the screen's size".takeIf { asked(width) { it > screen.width } || asked(height) { it > screen.height } },
                        "fit keeps the size, which already shows its preferred ${pref.width}x${pref.height}".takeIf {
                            (width == UiSteps.FIT || fitAll) && w == before.width && h == before.height
                        },
                    )
                    "resized"
                }
            }.let { verb -> Triple(verb, before, screen) }
        }
        UiSettle.barrier()
        return withContext(edtAny) {
            val (verb, before, screen) = line
            val now = window.bounds
            val size = if (now == before) "is ${now.width}x${now.height} at ${now.x},${now.y} already"
            else "$verb: ${now.width}x${now.height} at ${now.x},${now.y}, was ${before.width}x${before.height} at ${before.x},${before.y}"
            "${describe(window)} $size" + notes.joinToString("") { "; $it" } +
                "; its screen's usable area is ${screen.width}x${screen.height} at ${screen.x},${screen.y}"
        }
    }

    /** One side of a window: [wanted] pixels, "fit" (at least the preferred size, never smaller than now), or unchanged. */
    private fun size(wanted: String?, current: Int, preferred: Int, minimum: Int, screen: Int): Int {
        val asked = when (wanted) {
            null -> current
            UiSteps.FIT -> maxOf(current, preferred)
            else -> wanted.toInt()
        }
        return asked.coerceAtLeast(minimum).coerceAtMost(screen)
    }

    private fun describe(w: Window): String {
        val title = ((w as? Frame)?.title ?: (w as? java.awt.Dialog)?.title)?.takeIf { it.isNotBlank() }
        val kind = when (w) {
            is IdeFrame -> "the IDE window"
            is Frame -> "the window"
            is java.awt.Dialog -> "the dialog"
            else -> "the popup"
        }
        return kind + " " + (title?.let { "\"$it\"" } ?: WindowIdUtil.compute(w, w))
    }
}
