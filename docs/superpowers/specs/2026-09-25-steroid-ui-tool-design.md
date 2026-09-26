# steroid_ui: drive IDE UI by what it shows, without compiling code

## Goal

An agent drives an IDE dialog, popup, tool window or Settings page in a few
fast calls: read a compact snapshot of the controls, act on them by reference
or by what they show, and get back what changed. No Kotlin compilation, no
screenshot per step, and no coordinates.

Success looks like this:

- One step (find a control, act, report the effect) returns in well under a
  second when the IDE reacts quickly. Today it costs a `steroid_execute_code`
  call of about 6 s, or a screenshot and a `steroid_input` call.
- A failed step names what the IDE found: no match, several matches, a
  disabled control, or a modal dialog in the way, with the nearest candidates.
- A click that opens a modal dialog returns, and the response describes the
  dialog.
- The same engine is available to scripts as `ui.*` helpers, so the
  `ui-driving` recipe shrinks from about 80 lines of helpers to a few calls.

## Background

### Measurements

On IntelliJ IDEA 2026.3 EAP (IU-263.5885), a read-only
`steroid_execute_code` call that built the XPath model of a project frame
spent its time as follows (from `idea.log`):

| Part | Time |
|---|---|
| Kotlin compilation | 5.6 s |
| Script run | 0.43 s |
| `XpathDataModelCreator.create(frame)`, first and second call | 314 ms, 108 ms |

The model had 371 nodes, 163 of them with a name, tooltip or painted text,
about 9,700 characters of text in total. Compilation is about 93% of a UI
step, and the recipe makes the agent send about 80 lines of helper code in
every script.

### What the IDE ships

Both IDEs on the test machine, IU-262.10968.63 and IU-263.5885, bundle these
content modules of the Performance Testing plugin:

- `intellij.performanceTesting.remoteDriver`: `XpathDataModelCreator`, which
  turns the showing Swing tree into a DOM with `accessiblename`,
  `tooltiptext`, `visible_text` (text the component paints, read through a
  recording `Graphics2D`) and the live component as user data.
- `intellij.performanceTesting.remoteDriver.compose` and `.jcef`: model
  extensions for Compose and JCEF components.
- `intellij.driver.*`: the out-of-process Driver over JMX. It is not useful
  here, because Steroid already runs inside the IDE.

Two limits apply:

1. The remote-driver module has `visibility="internal"`. The plugin loader
   refuses a module dependency on it from another namespace
   (`ModuleVisibility.checkVisibilityAndReturnErrorMessage`). Steroid plugin
   code cannot declare the dependency. It has to load the classes through the
   module's own class loader, as `ScriptClassLoader` does for scripts.
2. `IdeRobot.create()` returns `InputEventsRobot`, which posts events to
   `IdeEventQueue`, only on Wayland or with
   `-Ddriver.robot.use.input.events=true`. Otherwise it returns
   `SmoothRobot`, which moves the user's real mouse through
   `java.awt.Robot`. `InputEventsRobot` is `internal`, exists from 2026.3
   (AT-4667), and waits 500 ms between events. Steroid does not use either
   robot. It keeps its own input code and takes three techniques from
   `InputEventsRobot`: a typed character is `KEY_PRESSED`, `KEY_TYPED` and
   `KEY_RELEASED` to one focus owner; the clicks of a double click go out
   back to back; a click starts with two moves.

### What AIR does

The AIR plugin's UI lanes (`plugins/air/tests/integration`, landed
2026-09-23) drive a real IDE with a test-only bridge module that IDEs do not
ship. Its design decisions carry over, its code does not:

- A step that waits on UI state is one call that waits and resolves inside
  the IDE. A failure says which state it found, and lists what it saw
  (decision record 0098).
- A resolved component is an opaque token. The IDE holds the component and
  rejects a token whose component is gone (`AirUiTestTargetRegistry`).
- A click is checked by observation: an `AWTEventListener` records which
  component AWT handed the press to, and an `ActionListener` records whether
  a button's action ran (`AirUiTestDeliveryObserver`,
  `AirUiTestButtonActionObserver`).
- A modal dialog that nobody addressed is reported as an intruder
  (`AirModalIntruderWatch`).
- Every scenario leaves a trace: a picture before and after each step, the
  Swing tree at each step, the input events and the log slice (decision
  record 0155).

### What Steroid has

- `steroid_input` (`SwingInputExecutor` in `VisionService.kt`) dispatches
  mouse events to the window and key events to the focus owner through
  `IdeEventQueue.dispatchEvent`, synchronously on the EDT. Since 0.109 a
  click is two moves, press, release and click, and it records the press
  recipient to give it focus.
- `steroid_take_screenshot` writes `screenshot-tree.md` from
  `SwingComponentTreeProvider`: class names and the text of labels, buttons
  and text fields only.
- The `mcp-steroid://ide/ui-driving` recipe (0.107) teaches the XPath model
  through `steroid_execute_code`.

## Decisions

1. **A new tool, `steroid_ui`.** It takes an optional root and a list of
   steps. With no steps it returns a snapshot. It is one tool, not one per
   action, to keep the tool list short. The list form lets one call chain
   several actions, like a script, with no compilation.
2. **Refs, as in Playwright MCP.** A snapshot names each listed component
   `e<N>`. Later steps and later calls address it by ref. An app-level
   registry holds the components through weak references. A ref whose
   component is no longer showing fails with "stale ref", and the response
   carries a fresh snapshot.
3. **Locators are strict.** A step addresses its target by `ref`, or by
   `name`, `text`, `class`, `xpath`, or a combination of them. A locator that
   matches several components fails and lists them with refs, unless the
   step gives `nth`.
4. **Actions wait for their target inside the IDE.** Before it acts, a step
   waits up to its `timeout_ms` (default 5,000) until its locator resolves to
   one showing, enabled component. The wait runs in the IDE, so the protocol
   is crossed once per call, not once per poll.
5. **Mouse input is posted, then awaited.** A click posts its events to
   `IdeEventQueue` and then waits for a barrier: a runnable queued with
   `ModalityState.any()`. If the click opens a modal dialog, the dialog's
   event loop runs the barrier and the step returns. The synchronous dispatch
   of `steroid_input` would block the call until the dialog closes.
6. **Key input follows the focus owner.** When a focus owner exists, key
   events are posted and `KeyboardFocusManager` delivers them, as for a real
   keyboard, so keymap shortcuts run. When none exists, which happens while
   the IDE is in the background, the events are dispatched directly to the
   step's target and the response says so. The `ui-driving` recipe records
   that posted key events are dropped with no focus owner.
7. **The model comes from the Remote Driver when it can, and from Steroid's
   own walker otherwise.** `RemoteDriverModel` loads `XpathDataModelCreator`
   through the content module's class loader and calls it with three
   reflective calls: the constructor, `getElementProcessors()` (to drop
   `RemoteDriverDataModelExtension` and the Remote Development extensions,
   which fail with "Invoker is not registered" without the JMX driver) and
   `create(Component)`. The result is a `org.w3c.dom.Document`, a JDK type.
   When the plugin is disabled or a class is missing, the fallback walker
   reads the accessible name, the text of labels, buttons and text fields,
   and the tooltip. The snapshot header names the source.
8. **Every action reports its effect.** The response of an action step lists
   where the press landed and whether it hit the target, whether a button's
   action ran, the IDE actions that ran, the windows that opened or closed,
   the new focus owner, and the snapshot change. A modal dialog that the call
   did not address is marked `unexpected`.
9. **Scripts get the same engine.** `McpScriptContext.ui` exposes snapshot,
   find, actions and waits. Refs from the tool work in scripts and the other
   way round.
10. **Screenshots reuse the snapshot.** `screenshot-tree.md` becomes the
    snapshot text with refs. A new `marks` parameter draws each ref on the
    image.
11. **Traces are opt-in.** `trace=true` writes a picture before and after
    each step, the snapshot and an event log to the execution folder. It is
    evidence for a reproduction, and it costs a capture per step.
12. **`steroid_input` stays.** It remains the tool for raw coordinates and
    key chords, and it moves onto the shared input code. Its description
    points to `steroid_ui` first.
13. **Work inside an open modal dialog, under its modality.** A new
    `steroid_execute_code` mode, `modal=dialog`, runs the script with the
    modality of the topmost modal dialog as its context modality, so its
    EDT hops and write actions run while that dialog is open. `steroid_ui`
    and the `ui.*` helpers do their EDT work under the modality of the
    target's window. See [Modal dialogs](#modal-dialogs).
14. **Watch modality through the platform topic.** Steroid subscribes to
    `ModalityStateListener.TOPIC` instead of polling the window list. It
    feeds the `unexpected` modal report, `wait for=window`, and the
    dialog monitor of `smart_non_modal`.
15. **Open a dialog without blocking the caller.** `ui.open { … }` runs its
    block in a separate EDT task and returns the dialog that the block
    opened, reported by the topic. The script keeps running while the
    dialog is up.

Rejected:

- **Separate tools per action.** Seven or more tools, each with the same
  root and locator parameters, and no chaining.
- **`IdeRobot`.** It moves the real mouse outside Wayland, and its
  event-posting robot is internal, 2026.3 only, and slow.
- **Porting the AIR bridge.** It is test code with test-lane assumptions
  (authenticated HTTP, VM supervision, scenario scoping). Its ideas are
  reused, not its code.
- **A compile cache for scripts.** It helps repeated identical scripts only.
  UI steps differ in every call.
- **`TestDialogManager` to answer message dialogs.** `MessagesServiceImpl`
  consults it only when the application is in unit-test or headless mode,
  and its setters assert that mode.
- **`ModalityState.any()` for scripts.** The platform allows `any()` only for
  purely UI work: no PSI, VFS, project model or indexes, and no modal
  dialogs (`isModalAwareContext`).

## Design

### Tool interface

`steroid_ui` parameters:

| Parameter | Meaning |
|---|---|
| `project_name`, `task_id`, `reason` | As for the other tools |
| `window_id` | Optional. The window to snapshot and act in. Default: every showing window of the project, topmost first: popups, dialogs, then the frame |
| `root` | Optional locator that narrows the snapshot to a subtree, such as a tool window or a Settings page |
| `steps` | Optional JSON array of steps. Omitted or empty: snapshot only |
| `snapshot` | `diff` (default when there are steps), `full` (default with no steps) or `none` |
| `max_nodes` | Snapshot size cap, default 400. Cut subtrees are summarised as `… N more` |
| `trace` | `true` to record a trace. Default `false` |
| `side` | Split Mode only: `frontend` (default) or `backend` |

A step is an object with `action` and its fields. A target is `ref`, or any
of `name` (exact accessible name), `text` (substring of painted text, case
sensitive), `class` (simple class name, or a superclass such as
`JTextComponent`), `xpath` (over the model), plus `nth` (0-based). Every step
takes `timeout_ms`.

| Action | Fields | Effect |
|---|---|---|
| `click` | target, `button` (`left`, `right`, `middle`), `count` (1 or 2), `modifiers` | Scrolls the target into view, then clicks its centre, or `offset_x`/`offset_y` inside it |
| `hover` | target | Two moves onto the target |
| `type` | optional target, `text` | Focuses the target when given, then types into the focus owner |
| `fill` | target, `text` | Replaces the text of a text component: focus, select all, type |
| `press` | `keys` (`ENTER`, `ESCAPE`, `ctrl+shift+A`, `meta+1`) | Presses the chord on the focus owner |
| `check`, `uncheck` | target | Clicks a checkbox or toggle only when its state differs |
| `select` | target (a list, tree, table or combo box), `row` (text) or `index` | Scrolls the row into view and clicks it. A combo box is opened first and the row is picked in its popup |
| `close` | optional target | Closes the dialog or popup that holds the target, or the topmost one, through `DialogWrapper.doCancelAction` or `JBPopup.cancel` |
| `wait` | `for` (`visible`, `hidden`, `enabled`, `window`, `idle`), target or `title` | Waits in the IDE until the condition holds |
| `snapshot` | optional target as root | Adds a full snapshot at this point |

The CLI form is `devrig ui --steps='[…]'`. The step grammar is JSON, not the
comma syntax of `steroid_input`, because targets contain free text.

### Snapshot format

One line per listed component, indented by depth:

```
window w-3f2a "Settings" (dialog, modal) source=remote-driver
- JBSplitter
  - Tree "Settings categories" [ref=e4] rows=Appearance & Behavior|Keymap|Editor|+9
  - JBCheckBox "Show tool window bars" [ref=e17] [checked]
  - JBTextField "Font size" [ref=e18] value="13"
  - JButton "OK" [ref=e31] [default]
  - JButton "Apply" [ref=e32] [disabled]
```

- A component is listed when it has a name, a tooltip or painted text, or
  when it is interactive (buttons, text components, lists, trees, tables,
  combo boxes, toggles). An unnamed wrapper with one child is skipped, as in
  the recipe's `uiSnapshot`.
- States: `disabled`, `checked`, `selected`, `focused`, `expanded`,
  `editable`, `default`. Text components show `value`, cut at 80
  characters. Lists, trees and tables show their visible rows through the
  cell renderer, cut at 8.
- `full` adds each component's screen bounds, which `steroid_input` accepts
  as `@screen:` coordinates.
- A `diff` lists added, removed and changed lines only. When a window opened,
  it lists that window in full.
- A component drawn by a Split Mode backend (a Lux panel in the client) is
  listed as such, with the hint to call again with `side=backend`.

### Components

All new code lives in `ij-plugin`, package `com.jonnyzzz.mcpSteroid.ui`,
except the tool spec, which goes to `mcp-steroid-server` with the others.

- **`UiToolSpec`** (`mcp-steroid-server`): the schema, JSON step parsing and
  validation, and `UiToolHandler`. Unit-testable without an IDE.
- **`UiToolHandlerIJ`**: resolves the project and window, writes the call to
  execution storage like `VisionInputToolHandlerIJ`, bounds the call with a
  timeout (the sum of the steps' `timeout_ms` plus a fixed allowance), and
  runs `UiSession`.
- **`UiModel`**: builds the model for a root on the EDT with
  `ModalityState.any()`, from `RemoteDriverModel` or `FallbackUiWalker`.
  Output: a tree of `UiNode(component, className, name, text, tooltip,
  states, rows, children)`. Pure data after construction.
- **`RemoteDriverModel`**: the class loader lookup and the three reflective
  calls. It reports why it is unavailable, and the header shows it.
- **`UiRefRegistry`** (app service): weak component-to-ref map, ref
  generation, stale check, a cap of 5,000 live refs with oldest-first
  eviction.
- **`UiLocator`**: resolves a target against a `UiNode` tree. Strict
  matching, `nth`, and "nearest candidates" for a miss (same class and
  similar name or text).
- **`UiSnapshotFormatter`**: `full` and `diff` text, and the size cap.
- **`UiInput`**: the input code shared with `steroid_input`, moved out of
  `SwingInputExecutor`. Posted mouse gestures with a barrier, key chords,
  typing as pressed, typed and released, the press-recipient observer, and
  a button action observer.
- **`UiSettle`**: after an action, waits for the barrier, then until no
  window opens or closes for 150 ms, at most 1,000 ms. `wait for=idle` uses
  the same check with the step's timeout.
- **`UiSession`**: runs the steps in order. For each one it resolves the
  target, acts, settles and collects the effect report. The first failure
  stops the list; the response gives the step index, the failure and a
  fresh snapshot.
- **`UiTrace`**: when `trace=true`, writes into the execution folder
  `NN-before.png` and `NN-after.png` (through `VisionService` capture of the
  window), `NN-snapshot.txt`, `trace.jsonl` (step, target, ref, delivery,
  effects, timings) and `trace.md`, an index that links the files. The
  response gives the folder path.
- **`McpScriptContext.ui`**: a `UiScriptApi` with `snapshot(root)`,
  `find(target)`, `click`, `type`, `fill`, `press`, `select`, `check`,
  `close` and `waitFor`. Targets are built with `ui.ref("e12")`,
  `ui.name("OK")`, `ui.text("…")`, `ui.cls("JTree")`, `ui.xpath("…")`, and
  combined with `and`. The helpers are suspend functions that switch to the
  EDT themselves.

### Changed code

- `VisionService.SwingInputExecutor` delegates clicks and keys to `UiInput`.
  `steroid_input` keeps its synchronous dispatch for key steps, so its
  behaviour does not change except for typing, which gains the pressed and
  released events.
- `SwingComponentTreeProvider` writes the snapshot of the captured window.
  `steroid_take_screenshot` gains `marks` (default `false`): each ref's
  bounds and label are drawn on a copy of the image, `screenshot-marked.png`,
  which is returned instead of the plain image.
- `ModalMode` gains `DIALOG` (`modal=dialog`), and `ScriptExecutor` runs its
  pre-flight and context as described in [Modal dialogs](#modal-dialogs).
  The `modal` parameter description of `steroid_execute_code` lists it.
  `DialogKiller`'s monitor moves onto `ModalityWatcher`.
- `SplitRouting.ROUTED_TOOLS` gains `steroid_ui` with home `FRONTEND`. It
  accepts `side`, like `steroid_execute_code`.
- devrig: `DevrigUiToolHandler` forwards the call as
  `DevrigVisionInputToolHandler` does, and `ExpectedSteroidTools` and
  `DevrigServerInstructions` list the tool.
- Prompts:
  - The `steroid_ui` description carries the step table and one example.
  - `ide/ui-driving.md` leads with `steroid_ui`, then shows the `ui.*`
    helpers for logic that needs a script. The helper blocks are removed.
  - `skill/split-mode.md` says which side `steroid_ui` runs on.
  - The `steroid_input` description points to `steroid_ui`.
- `TODO.md`: the "Screenshot metadata from the remote-driver UI model" item
  is resolved by this work.

### Modal dialogs

Today a modal dialog stops Steroid in four places:

| Where | Why |
|---|---|
| `smart_non_modal` pre-flight | It closes leftover dialogs and fails when one survives |
| A script's `withContext(Dispatchers.EDT)` and `writeAction` | The script has no context modality, so the EDT dispatcher falls back to non-modal work, which the platform holds until every modal dialog closes |
| A script that shows a dialog on the EDT (`Messages.show…`, `DialogWrapper.show()`) | The call runs the dialog's event loop and returns only when the dialog closes. `runBoundedByTimeout` closes it at the deadline |
| `steroid_input` click that opens a dialog | The click is dispatched synchronously inside an EDT task, which then runs the dialog's loop |

The platform's own answer is the modality state. `EdtCoroutineDispatcher`
dispatches with the coroutine's context modality
(`ModalityState.asContextElement()`), and a task under the modality of a
dialog runs while that dialog is open. This is how a dialog's own code does
its work. `ModalityState.current()`, read on the EDT, is the modality of the
topmost modal dialog. `ModalityState.stateForComponent(c)` is the modality
of the dialog that holds `c`.

**`modal=dialog`.** A fourth mode of `steroid_execute_code`:

1. Pre-flight: read `ModalityState.current()` on the EDT. When it is
   non-modal, fail with "no modal dialog is open; use smart_non_modal". Do
   not close dialogs, commit documents or refresh VFS: the VFS refresh would
   change the project model under a dialog the caller does not own, which
   `awaitRefreshUnlessModal` already avoids.
2. Run the script body with `state.asContextElement()`. Its
   `Dispatchers.EDT` hops, `writeAction { }`, and platform code that reads
   `ModalityState.defaultModalityState()` run under the dialog.
3. When a nested modal dialog opens during the run, work under the outer
   dialog waits again. The script can read the nested dialog with
   `ui.snapshot()` and answer it, because `ui.*` helpers use the target's
   own modality.
4. The response header names the dialog the script ran under.

`smart_non_modal`'s failure message for a surviving dialog names this mode.
The `ui-driving` recipe replaces `allowModalDialog()` plus `unleashed` with
`modal=dialog` for scripts that work in an open dialog.

**Modality events.** A `ModalityWatcher` app service subscribes to
`ModalityStateListener.TOPIC` (public API in `core-api`, an app-level topic).
`beforeModalityStateChanged(entering, modalEntity)` gives the dialog window
or the modal progress. The watcher keeps the stack of modal entities and a
flow of changes. Users:

- `UiSession`: the windows that opened or closed during a step, and the
  `unexpected` mark for a modal no step addressed.
- `wait for=window`: completes on the event, not on a poll.
- The `smart_non_modal` monitor, which polls every second today, reacts to
  the event.
- `ui.open { }` below.

**`ui.open { block }`.** Runs `block` with
`ApplicationManager.getApplication().invokeLater(block, state)`, where
`state` is the script's context modality or non-modal, and suspends until
the watcher reports a modal entity entered after the call, or the timeout
ends. It returns the dialog window and a snapshot of it. A block that opens
no modal dialog fails with the timeout. The dialog's event loop then runs in
its own EDT task, and the script continues. This replaces the recipe's
`invokeLater` plus `Window.getWindows()` polling.

**Answering a dialog.** Through `steroid_ui` or `ui.*`: click a button by
name, or `close`, which calls `DialogWrapper.doCancelAction()`. Message
dialogs, including the alert dialogs of `AlertMessagesManager`, are
`DialogWrapper`s with named buttons, so the same steps answer them.

### Errors

Every failure is a tool error result with the step index and a snapshot:

| Failure | Message content |
|---|---|
| No match after `timeout_ms` | The locator, "no match", and up to five nearest candidates with refs |
| Several matches | The locator and every match with its ref, up to ten |
| Target disabled | The ref and "disabled" |
| Stale ref | The ref, and "call without steps for a fresh snapshot" |
| Modal dialog in the way | Its title and buttons, and "unexpected" when no step addressed it |
| Press landed elsewhere | The target, the component that received the press, and the window on top at the point |
| Barrier timeout (EDT busy) | The step, the time waited, and a thread dump file in the execution folder |

A wrong effect is not a failure. A click whose button action did not run is
reported as `action_performed: false`, and the agent decides.

## Testing

- **Unit (`mcp-steroid-server`)**: step parsing and validation, including
  unknown actions, missing targets and bad key chords.
- **Unit (`ij-plugin`)**: `UiLocator` strictness, `nth` and candidates;
  `UiSnapshotFormatter` output, diff and size cap; `UiRefRegistry` stale
  detection and eviction; key chord to event list; the typed character
  triple. Built on `UiNode` trees and plain Swing components that need no
  display.
- **Integration (`test-integration`, Docker)**: a new
  `SteroidUiIntegrationTest` with a test dialog.
  - A snapshot lists the dialog's controls with refs.
  - `fill` a text field, `check` a checkbox, `click` OK: the dialog closes
    and its values are applied.
  - A `click` on a button that opens a modal dialog returns within 2 s, and
    the response describes the new dialog.
  - `select` in a list popup and in a combo box.
  - A locator with no match fails with candidates. A stale ref fails as
    stale.
  - `trace=true` writes the files listed above.
  - `ui.*` helpers from `steroid_execute_code` do the same dialog run.
  - `modal=dialog`: with the test dialog open, a script's `writeAction`
    changes a document and returns while the dialog stays open. The same
    script under `non_modal` fails at the gate.
  - `ui.open { }` around an action that shows a `Messages` dialog returns
    the dialog, and a `steroid_ui` click on its button closes it and ends
    the action.
  These run on CI. Locally, run only this class.
- **Fallback**: the integration test runs once more with the Performance
  Testing plugin disabled, and the snapshot header shows the fallback.
- **Live, once**: in a sandbox IDE on 2026.3 EAP and on 2026.1, open
  Settings, select a page by name, toggle a checkbox, cancel. In a Split Mode
  pair, the same through the client endpoint. Record the times of a snapshot
  and of an action step. This also confirms that 2026.1 ships the
  remote-driver module, which has been checked on 2026.2 and 2026.3 only.

## Out of scope

- Drag and drop, and scrolling as its own action. `click` and `select`
  scroll their target into view.
- Video traces. Stills are enough for evidence. Video needs an encoder that
  Steroid does not ship.
- A trace viewer. `trace.md` links the files, and any Markdown viewer shows
  them.
- JetDesk guidance (`.claude/docs/steroid-ui-driving.md`) for the new tool.
  It follows the release, as a JetDesk change.
- Compose and JCEF content beyond what the remote-driver extensions put in
  the model.
