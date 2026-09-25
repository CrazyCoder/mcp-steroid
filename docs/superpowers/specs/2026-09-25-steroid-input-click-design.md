# steroid_input clicks: deliver them like real mouse input

Sub-project 3 of the split-mode effort. Sub-project 1 is
[the split plugin](2026-09-25-steroid-split-mode-design.md).

## Goal

A `click:` step in `steroid_input` does what a user's click does, in any IDE
window: a popup menu item runs, a Settings control changes, and a Lux page in
Split Mode receives the click. Agents stop falling back to internal APIs
(`handleSelect`, `doClick`, `tryToExecute`), which prove less about what the
user sees.

## Background

A field test in Split Mode reported three input problems: popup menu items,
clicks inside Settings, and a `ShowSettingsUtil` call that left a broken state.
An investigation on the `runIdeSplitMode` pair (IU and JBC 261.22158.277) found
two defects in `SwingInputExecutor.click`. Neither is specific to Split Mode or
Lux.

1. **No mouse move before the press.** `click` dispatches `MOUSE_PRESSED`,
   `MOUSE_RELEASED` and `MOUSE_CLICKED` only. A list popup selects the row
   under the pointer on `MOUSE_MOVED`, and `ListPopupImpl.MyList` drops a press
   or release on any other row. A click on an unselected popup item does
   nothing. A move followed by the same click selected "View Mode" in the Quick
   Switch Scheme popup and opened its submenu.
2. **The wrong target in some dialogs.** `click` picks the target with
   `SwingUtilities.getDeepestComponentAt`, which ignores listeners and returns
   the topmost visible component. The Settings dialog has a visible
   `IdeGlassPaneImpl` over the whole window, so every click goes to the glass
   pane and is lost. Project frames and dialogs with a hidden glass pane are not
   affected, which is why `SteroidInputDialogIntegrationTest` passes.

Clicks that reach the right component work, on frontend Swing and on Lux pages
alike. `LuxEventSynchronizer` forwards every mouse event on a
`LuxFrontendPanel` to the backend; a click dispatched to the panel toggled a
checkbox on the "Advanced Settings (Host)" page.

The `ShowSettingsUtil` problem did not reproduce. `showSettingsDialog` on the
backend goes through `BackendShowSettingsUtil`, which sends the request to the
client. `editConfigurable` on the backend shows a backend dialog that the
frontend mirrors correctly. `showSettingsDialog` on the frontend opens the same
Settings window.

## Decisions

1. `click` dispatches its events to the target **window**, with window
   coordinates, through `IdeEventQueue`. AWT's `LightweightDispatcher` then
   picks the component exactly as it does for real input: it skips a glass pane
   that has no mouse listeners and generates `MOUSE_ENTERED` and `MOUSE_EXITED`.
   `IdeEventQueue` still runs its dispatchers, so keymap mouse shortcuts keep
   working. A window-level dispatch selected Settings tree rows through the
   visible glass pane and toggled the Lux checkbox.
2. A click is two `MOUSE_MOVED` events, then `MOUSE_PRESSED`, `MOUSE_RELEASED`
   and `MOUSE_CLICKED`. The first move lands one pixel away from the click
   point, the rest at the point. `ListPopupImpl` and `TreePopupImpl` ignore the
   first move they see (`isMouseMoved`), so that a popup opening under a
   resting pointer does not select a row. With a single move, the smoke test
   of a fresh popup failed.
3. No change for opening Settings. Without the call that broke, there is nothing
   to fix. We ask the field tester for it; the agent guidance (sub-project 4)
   points at `ShowSettingsUtil.showSettingsDialog`, which works on both sides.

Rejected: keeping our own target search and skipping the glass pane. It fixes
this case only. Other overlays, such as a visible glass pane child or a
transparent layer, would need the same special-casing, and the result would
still differ from real input.

## Design

Only `SwingInputExecutor.click` and its helpers in `VisionService.kt` change.

- **Window and point.** The window is the root component itself when it is a
  `Window`, otherwise its window ancestor. For a `screenshot:` target the point
  is converted from the root component to the window with
  `SwingUtilities.convertPoint`, so a project frame, whose root is its root
  pane, and a dialog, whose root is the window, give the same result. For a
  `screen:` target the point is converted with
  `SwingUtilities.convertPointFromScreen` against the window. The clamping to
  the root component's bounds stays.
- **Events.** Each event is built with the window as its source.
  - `MOUSE_MOVED`, twice: no button, click count 0, the step's keyboard
    modifiers. The first one pixel to the left of the point, or to the right
    at the window's left edge.
  - `MOUSE_PRESSED`: the button, click count 1, the modifiers plus the
    button's down mask (`BUTTON1_DOWN_MASK` and so on), as AWT reports a real
    press.
  - `MOUSE_RELEASED` and `MOUSE_CLICKED`: the button, click count 1, the
    modifiers without the down mask.
  - `popupTrigger` keeps its current rule: true for the right button.
- **Focus.** The clicked component must own focus afterwards, so that a
  following `press:` or `type:` step reaches it (issue #309). Only AWT knows
  which component that is. While the press is dispatched, a temporary
  `AWTEventListener` for mouse events records the source of the retargeted
  `MOUSE_PRESSED`, which is the component AWT delivered it to. Right after the
  press, the executor requests focus on that component with
  `IdeFocusManager.requestFocus`, as it does today before the press. Most
  components request focus on a press themselves; the explicit request covers
  the ones that do not. When no component received the press, only the window
  is activated.
- **Unchanged.** The window activation before the steps, key steps,
  `Unsupported` targets, the release of stuck keys, the "IDE actions performed"
  report, and what happens to a point outside the window: AWT finds no target,
  as for a real click there.

## Testing

- **Unit (`ij-plugin`)**: the event list for a left and a right click (ids,
  order, buttons, masks, click counts). AWT windows cannot be created in the
  headless unit-test IDE, so dispatch and focus are covered by the integration
  and live tests.
- **Integration (`test-integration`, Docker)**: extend
  `SteroidInputDialogIntegrationTest`.
  - A dialog whose glass pane is made visible over a checkbox: a click toggles
    the checkbox. Fails before the fix.
  - A `ListPopup` with three items: a click on the third item runs it. Fails
    before the fix.
  - The existing checkbox and modal cases stay green.
  These run on CI. Locally, run only the new test class if needed.
- **Live, once (`runIdeSplitMode`)**: through the frontend endpoint, a
  `steroid_input` click selects a Settings tree row, toggles a checkbox on the
  "Advanced Settings (Host)" Lux page, and runs a Quick Switch Scheme popup
  item.

## Out of scope

- Double clicks and drags. The click count stays 1, and there is no drag step.
- Keyboard input. Shortcuts were fixed in 0.108 and work in both modes.
- Opening Settings: waiting for the exact call from the field tester.
- Agent guidance for Split Mode input (sub-project 4).
