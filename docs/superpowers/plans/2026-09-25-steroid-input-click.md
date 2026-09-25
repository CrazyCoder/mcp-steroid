# steroid_input window-level clicks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `click:` step delivers move, press, release and click to the
target window, so AWT picks the component as for real input.

**Architecture:** `SwingInputExecutor.click` in `VisionService.kt` builds the
events from a pure `clickEventSequence` helper and dispatches them with the
window as the source through `IdeEventQueue`. A temporary `AWTEventListener`
records the component that received the press, for the focus request.

**Tech Stack:** Kotlin, AWT/Swing, IntelliJ Platform 261, JUnit 4 (unit),
JUnit 5 Docker integration tests.

**Spec:** `docs/superpowers/specs/2026-09-25-steroid-input-click-design.md`

## Global Constraints

- Only `SwingInputExecutor.click` and its helpers change. Key steps,
  `Unsupported` targets, stuck-key release and the actions report stay as
  they are.
- Order: `MOUSE_MOVED`, `MOUSE_PRESSED`, `MOUSE_RELEASED`, `MOUSE_CLICKED`, one
  point.
- Press carries the button's down mask; move has no button and click count 0;
  `popupTrigger` is true for the right button on press, release and click.
- Unit tests run headless (`java.awt.headless=true`); no AWT windows there.
- Do not run the Docker integration suite locally; compile it. CI runs it.

## Review Focus

- A dialog with a visible glass pane: the click reaches the control under it
  (integration test, Task 2).
- A list popup item that is not selected: the click runs it (integration test,
  Task 2).
- A click followed by `press:SPACE` still reaches the clicked checkbox (existing
  integration test, kept green).
- A project frame click still works: its root is the root pane, not the
  window (live smoke test, Task 3).
- A right click still opens a context menu (live smoke test, Task 3).

---

### Task 1: Window-level click dispatch

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/vision/VisionService.kt`
  (`click`, `dispatchMouse`, new top-level `clickEventSequence`)
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/vision/ClickEventSequenceTest.kt`

**Interfaces:**
- Produces: `internal data class ClickEvent(val id: Int, val button: Int, val modifiers: Int, val clickCount: Int, val popupTrigger: Boolean)`
  and `internal fun clickEventSequence(button: Int, modifiers: Int): List<ClickEvent>`.

- [ ] **Step 1: Write the failing test**

```kotlin
class ClickEventSequenceTest {
    @Test
    fun `a left click is move, press with the down mask, release, click`() {
        assertEquals(
            listOf(
                ClickEvent(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON, SHIFT, 0, false),
                ClickEvent(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, SHIFT or InputEvent.BUTTON1_DOWN_MASK, 1, false),
                ClickEvent(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, SHIFT, 1, false),
                ClickEvent(MouseEvent.MOUSE_CLICKED, MouseEvent.BUTTON1, SHIFT, 1, false),
            ),
            clickEventSequence(MouseEvent.BUTTON1, SHIFT),
        )
    }

    @Test
    fun `a right click is a popup trigger with the button 3 down mask on press`() { /* BUTTON3, BUTTON3_DOWN_MASK, popupTrigger true except move */ }

    @Test
    fun `a middle click uses the button 2 down mask`() { /* BUTTON2_DOWN_MASK on press */ }
}
```

- [ ] **Step 2: Run it, expect a compile failure** —
  `./gradlew :ij-plugin:test --tests '*ClickEventSequenceTest'`

- [ ] **Step 3: Implement** `clickEventSequence`, then rewrite `click`:
  window = root if it is a `Window`, else its window ancestor; point converted
  to window space (`convertPoint` for `screenshot:`, `convertPointFromScreen`
  against the window for `screen:`); each `ClickEvent` becomes a `MouseEvent`
  with the window as source, dispatched with `IdeEventQueue.dispatchEvent`; an
  `AWTEventListener` (mouse mask) installed around the press records the
  source of the `MOUSE_PRESSED` whose source is not the window; after the press,
  `IdeFocusManager.findInstanceByComponent(target).requestFocus(target, true)`.

- [ ] **Step 4: Run** `ClickEventSequenceTest` and the vision unit tests. PASS.

- [ ] **Step 5: Commit** `fix(input): deliver clicks to the window like real mouse input`.

### Task 2: Integration tests for the two defects

**Files:**
- Modify: `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/SteroidInputDialogIntegrationTest.kt`

- [ ] **Step 1:** `openDialog(..., visibleGlassPane: Boolean = false)` makes
  the dialog's glass pane visible after `show()`. `inspectDialog` also prints
  `DEEPEST:` (the class `getDeepestComponentAt` returns at the checkbox), and
  the new test asserts it is the glass pane, so the test cannot pass vacuously.
- [ ] **Step 2:** New test: a click on the checkbox under the visible glass
  pane toggles it.
- [ ] **Step 3:** New test: a `JBPopupFactory` list popup with `Alpha`, `Beta`,
  `Gamma`; `onChosen` stores the value in a system property. The probe returns
  the popup's `window_id` (frame component id for a lightweight popup, window id
  for a heavyweight one) and the center of `Gamma` in root-component
  coordinates. It asserts `Gamma` is not selected, clicks it, and asserts the
  property is `Gamma`.
- [ ] **Step 4:** `./gradlew :test-integration:compileTestKotlin`. PASS.
- [ ] **Step 5: Commit** `test(input): cover clicks under a visible glass pane and on popup items`.

### Task 3: Live smoke test

- [ ] Build the plugin, install it into both `runIdeSplitMode` sandboxes,
  restart them.
- [ ] Through the frontend endpoint, with `steroid_input` only: select a
  Settings tree row, toggle "Show modified only" on the host Advanced Settings
  Lux page, run a Quick Switch Scheme popup item, click in the project frame
  (Project view row), and right-click in the editor to open its context menu.
- [ ] Fix what fails, with a test where one is possible; commit per fix.
