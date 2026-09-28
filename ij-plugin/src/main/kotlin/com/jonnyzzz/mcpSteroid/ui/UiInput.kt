/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.wm.IdeFocusManager
import com.jonnyzzz.mcpSteroid.vision.ClickEvent
import com.jonnyzzz.mcpSteroid.vision.InputModifier
import com.jonnyzzz.mcpSteroid.vision.InputSequenceParser
import com.jonnyzzz.mcpSteroid.vision.InputStep
import com.jonnyzzz.mcpSteroid.vision.clickEventSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ActionListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Where a click's press went, and whether a button's action ran. */
data class ClickReport(val pressed: Component?, val hitTarget: Boolean, val actionPerformed: Boolean?)

/** Which component the key events went to. */
data class KeyReport(val recipient: Component)

/**
 * Input for steroid_ui, delivered like 0.109's steroid_input: mouse events go to the window in window coordinates,
 * so AWT picks the component as for a real click, and key events go to one component. Every event is dispatched
 * through [IdeEventQueue.dispatchEvent], so keymap shortcuts run, and each dispatch runs in its own EDT task,
 * followed by a barrier task. When a dispatch opens a modal dialog, the dialog's event loop runs the barrier, so
 * the call returns while the dialog is up instead of waiting for it to close.
 */
class UiInput(
    /** Why no pointer reaches a control past the edge of its panel or window, and what makes room for it. */
    private val unreachable: (Component) -> String = { "it lies past the edge of its panel or window" },
) {
    private val edtAny get() = Dispatchers.EDT + ModalityState.any().asContextElement()

    /**
     * Where the pointer goes to reach [local] in [target], after the target was scrolled into view: [local] itself when
     * it shows, else the middle of the part of [area], or of the target, that shows, as a person clicks the visible part
     * of a half-hidden button. An [exact] point, a step's offset, is not moved. Fails when none of it shows. EDT.
     */
    private fun reachable(target: Component, local: Point, area: Rectangle?, exact: Boolean): Point {
        val shown = UiLayout.visiblePart(target, throughViewports = true)
        val reach = area?.let { shown.intersection(it) } ?: shown
        val what = UiComponentFacts.simpleClassName(target) + (UiComponentFacts.name(target)?.let { " \"${it.take(60)}\"" } ?: "")
        return when {
            reach.isEmpty -> throw UiStepFailure("no pointer reaches $what: ${unreachable(target)}")
            reach.contains(local) -> local
            exact -> throw UiStepFailure("the offset ${local.x},${local.y} is outside the part of $what that shows, " +
                "${reach.x},${reach.y} ${reach.width}x${reach.height}; ${unreachable(target)}")
            else -> Point(reach.centerX.toInt(), reach.centerY.toInt())
        }
    }

    /**
     * Clicks [target] at [offset], else at the centre of [area], a part of it such as a row, else at its centre. The
     * area, or the whole target, is scrolled into view first.
     */
    suspend fun click(target: Component, button: Int, count: Int, modifiers: Int, offset: Point?, area: Rectangle? = null): ClickReport {
        val aim = withContext(edtAny) {
            require(target.isShowing) { "the target is not showing" }
            (target as? JComponent)?.scrollRectToVisible(area ?: Rectangle(0, 0, target.width, target.height))
            val window = target as? Window ?: SwingUtilities.getWindowAncestor(target)
                ?: throw IllegalStateException("the target is not in a window")
            activate(window)
            val local = offset ?: area?.let { Point(it.centerX.toInt(), it.centerY.toInt()) } ?: Point(target.width / 2, target.height / 2)
            Aim(window, SwingUtilities.convertPoint(target, reachable(target, local, area, offset != null), window))
        }
        val recorder = PressRecorder(aim.window)
        val action = (target as? AbstractButton)?.let { ActionRecorder(it) }
        withContext(edtAny) {
            Toolkit.getDefaultToolkit().addAWTEventListener(recorder, AWTEvent.MOUSE_EVENT_MASK)
            action?.attach()
        }
        try {
            val approach = Point(if (aim.point.x > 0) aim.point.x - 1 else aim.point.x + 1, aim.point.y)
            for (event in gestureEvents(button, modifiers, count)) {
                val at = if (event.approach) approach else aim.point
                later {
                    IdeEventQueue.getInstance().dispatchEvent(
                        MouseEvent(aim.window, event.id, System.currentTimeMillis(), event.modifiers, at.x, at.y,
                            event.clickCount, event.popupTrigger, event.button)
                    )
                }
                if (event.id == MouseEvent.MOUSE_PRESSED) {
                    // A following key step must reach the clicked component, and only AWT knows which one it is.
                    later { recorder.pressed?.let { focus(it) } }
                }
            }
            UiSettle.barrier()
        } finally {
            withContext(edtAny) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(recorder)
                action?.detach()
            }
        }
        val pressed = recorder.pressed
        return ClickReport(
            pressed = pressed,
            hitTarget = pressed != null && (pressed === target || SwingUtilities.isDescendingFrom(pressed, target)),
            actionPerformed = action?.performed,
        )
    }

    /** Moves the pointer over the centre of [area], a part of [target] such as a row, else over the target's centre. */
    suspend fun hover(target: Component, area: Rectangle? = null) {
        withContext(edtAny) {
            require(target.isShowing) { "the target is not showing" }
            val window = SwingUtilities.getWindowAncestor(target) ?: throw IllegalStateException("the target is not in a window")
            val local = area?.let { Point(it.centerX.toInt(), it.centerY.toInt()) } ?: Point(target.width / 2, target.height / 2)
            val point = SwingUtilities.convertPoint(target, reachable(target, local, area, exact = false), window)
            for (x in listOf(point.x - 1, point.x)) {
                later {
                    IdeEventQueue.getInstance().dispatchEvent(
                        MouseEvent(window, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, point.y, 0, false, MouseEvent.NOBUTTON)
                    )
                }
            }
        }
        UiSettle.barrier()
    }

    /** Presses [chord] on [target] when given (after focusing it), otherwise on the focus owner. */
    suspend fun press(chord: InputStep.PressKey, target: Component?): KeyReport {
        val recipient = recipient(target)
        val modifierCodes = chord.modifiers.map(::modifierKeyCode)
        var held = 0
        for (code in modifierCodes) {
            val mask = held
            later { key(recipient, KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, mask or modifierMask(code)) }
            held = held or modifierMask(code)
        }
        val all = held
        later { key(recipient, KeyEvent.KEY_PRESSED, chord.keyCode, KeyEvent.CHAR_UNDEFINED, all) }
        later { key(recipient, KeyEvent.KEY_RELEASED, chord.keyCode, KeyEvent.CHAR_UNDEFINED, all) }
        for (code in modifierCodes.reversed()) {
            held = held and modifierMask(code).inv()
            val mask = held
            later { key(recipient, KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED, mask) }
        }
        UiSettle.barrier()
        return KeyReport(recipient)
    }

    /** Types [text] into [target] when given (after focusing it), otherwise into the focus owner. */
    suspend fun type(text: String, target: Component?): KeyReport {
        val recipient = recipient(target)
        for (ch in text) {
            later { typedCharEvents(recipient, ch, 0, System.currentTimeMillis()).forEach(IdeEventQueue.getInstance()::dispatchEvent) }
        }
        UiSettle.barrier()
        return KeyReport(recipient)
    }

    private suspend fun recipient(target: Component?): Component = withContext(edtAny) {
        if (target != null) {
            SwingUtilities.getWindowAncestor(target)?.let(::activate)
            focus(target)
            target
        } else {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                ?: throw IllegalStateException("no component has the keyboard focus; give the step a target")
        }
    }

    private fun focus(c: Component) {
        IdeFocusManager.findInstanceByComponent(c).requestFocus(c, true)
        c.requestFocusInWindow()
    }

    private fun activate(window: Window) {
        if (!window.isActive) {
            window.toFront()
            window.requestFocus()
        }
    }

    private fun key(c: Component, id: Int, code: Int, ch: Char, modifiers: Int) {
        IdeEventQueue.getInstance().dispatchEvent(KeyEvent(c, id, System.currentTimeMillis(), modifiers, code, ch))
    }

    private fun later(task: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(task, ModalityState.any())
    }

    private class Aim(val window: Window, val point: Point)

    /** Records the component AWT hands the press to, which is the one a real click would reach. */
    private class PressRecorder(private val window: Window) : AWTEventListener {
        @Volatile
        var pressed: Component? = null
            private set

        override fun eventDispatched(e: AWTEvent) {
            if (e is MouseEvent && e.id == MouseEvent.MOUSE_PRESSED && e.component !== window && pressed == null) pressed = e.component
        }
    }

    /** Records whether a button's action ran, which is the effect a click on it exists to cause. */
    private class ActionRecorder(private val button: AbstractButton) {
        @Volatile
        var performed = false
            private set
        private val listener = ActionListener { performed = true }

        fun attach() = button.addActionListener(listener)

        fun detach() = button.removeActionListener(listener)
    }

    companion object {
        /** Parses `ENTER`, `ctrl+shift+A` or `meta+1` with the steroid_input key syntax. */
        fun parseKeys(keys: String): InputStep.PressKey =
            InputSequenceParser().parse("press:$keys").single() as InputStep.PressKey

        /** A click's events; a double click repeats the press, release and click with a click count of 2. */
        fun gestureEvents(button: Int, modifiers: Int, count: Int): List<ClickEvent> {
            val single = clickEventSequence(button, modifiers)
            if (count == 1) return single
            return single + single.filter { !it.approach && it.id != MouseEvent.MOUSE_MOVED }.map { it.copy(clickCount = 2) }
        }

        /**
         * One typed character as a keyboard sends it: pressed, typed, released, all on [source]. A component may
         * ignore a typed event whose press it never saw (the terminal does). A character no key produces is only typed.
         */
        fun typedCharEvents(
            source: Component,
            ch: Char,
            modifiers: Int,
            now: Long,
            keyCodeFor: (Char) -> Int = { KeyEvent.getExtendedKeyCodeForChar(it.code) },
        ): List<KeyEvent> {
            val code = keyCodeFor(ch)
            if (code == KeyEvent.VK_UNDEFINED) {
                return listOf(KeyEvent(source, KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, ch))
            }
            val mods = modifiers or if (ch.isUpperCase()) InputEvent.SHIFT_DOWN_MASK else 0
            return listOf(
                KeyEvent(source, KeyEvent.KEY_PRESSED, now, mods, code, KeyEvent.CHAR_UNDEFINED),
                KeyEvent(source, KeyEvent.KEY_TYPED, now, mods, KeyEvent.VK_UNDEFINED, ch),
                KeyEvent(source, KeyEvent.KEY_RELEASED, now, mods, code, KeyEvent.CHAR_UNDEFINED),
            )
        }

        fun modifierKeyCode(modifier: InputModifier): Int = when (modifier) {
            InputModifier.SHIFT -> KeyEvent.VK_SHIFT
            InputModifier.CTRL -> KeyEvent.VK_CONTROL
            InputModifier.ALT -> KeyEvent.VK_ALT
            InputModifier.META -> KeyEvent.VK_META
        }

        fun modifierMask(keyCode: Int): Int = when (keyCode) {
            KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK
            KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK
            KeyEvent.VK_ALT -> InputEvent.ALT_DOWN_MASK
            KeyEvent.VK_META -> InputEvent.META_DOWN_MASK
            else -> 0
        }

        /** The keyboard modifiers of a step's `modifiers` field, such as `ctrl+shift`. */
        fun modifiersMask(modifiers: String?): Int {
            if (modifiers.isNullOrBlank()) return 0
            return modifiers.split('+').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.fold(0) { acc, name ->
                val modifier = InputModifier.entries.firstOrNull { it.name == name || (name == "CONTROL" && it == InputModifier.CTRL) }
                    ?: throw IllegalArgumentException("unknown modifier '$name'; use ctrl, shift, alt or meta")
                acc or modifierMask(modifierKeyCode(modifier))
            }
        }
    }
}
