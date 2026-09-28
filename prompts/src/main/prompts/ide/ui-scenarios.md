IDE: Record, check and replay IDE scenarios with steroid_ui

Script any repeatable IDE procedure as steroid_ui steps: reproduce a bug, check a feature, take pictures for a visual review, or set the IDE up; replay it anywhere for a verdict.

# When to use this recipe

A scenario is a JSON file of `steroid_ui` steps that another agent, another IDE build or a later run can
repeat exactly. Each step carries what it is for, so a step the UI outgrew can be repaired by its purpose.
`steroid_ui` replays the file in one call and ends with a verdict. Use one whenever a procedure in the IDE is
worth doing more than once:

| Kind | What it holds | Its verdict |
|---|---|---|
| Reproduction | The steps of a user's report, and a check marked `bug` that states the behavior the report expected | `REPRODUCED` while the bug is present, `NOT REPRODUCED` once it is fixed |
| Check | Steps that exercise a feature, and `expect` steps that state what it must do, as an acceptance or regression test | `PASSED`, or `FAILED` at the check that did not hold |
| Visual review | Steps that bring the IDE to each state worth seeing, and `screenshot` steps that save a picture of it | `PASSED`, with the pictures listed in the report for review |
| Routine | Steps that set the IDE up or walk through a feature: a test environment, the settings a task needs, a demo | `PASSED` |

The kinds mix freely: a reproduction can take pictures, a check can start with a setup. Whatever the kind, a
step that cannot be done at all gives `BROKEN`, which means the scenario needs repair, not that the IDE is at
fault.

Read [Drive UI controls](mcp-steroid://ide/ui-driving) first for targets, refs, snapshots and the input steps.
This recipe adds what a scenario needs on top: checks, setup without dialogs, pictures, recording and replay.

## The workflow

1. **Set up** the state the procedure depends on with `set`, `write` and `settings` steps, not by clicking
   through dialogs: a setting set directly is the same on every replay. The window size, the tool windows,
   the menu mode and the settings go in the scenario's `setup` block.
2. **Do it** with `steroid_ui` steps, one call at a time, reading each response. Give every step an `intent`,
   in plain words: "open the Rename dialog", "type the new name".
3. **Check** the outcome with `expect` steps that state the *correct* behavior, and save the states worth
   seeing with `screenshot`. In a reproduction, the check that fails while the bug is present gets `"bug"`: a
   sentence that names the bug.
4. **Save** the scenario. Every `steroid_ui` call with steps appends them to the task's recording file, named
   in the response (`recorded: ... to <file>`), with refs already replaced by names, captions or classes, row
   indexes by row text, Settings pages by their id and option names by their full name. The lines of that
   file, minus exploration that led nowhere, are the scenario's `steps`. Add the header fields below. The
   replay puts back the settings, sizes, menu items and files the steps change; a `cleanup` closes what they
   opened.
5. **Replay** it with `steroid_ui` and `scenario` set to the file's path. Replay it once right away: a scenario
   that does not replay in a fresh call is not done.
6. **Replay it again** whenever it matters: after a fix (`NOT REPRODUCED` confirms it), on a new IDE build, or
   before a release. `BROKEN` means a step no longer works, usually because the UI changed: repair the failing
   step by its `intent`, and replay.

## The scenario file

```
{
  "$schema": "mcp-steroid://ide/ui-scenario-schema",
  "scenario": 1,
  "title": "Line numbers turned off from Search Everywhere show as off in Settings",
  "issue": "IDEA-123456",
  "ide": "IU-262.10968.63",
  "project": "Any project with a text file open",
  "description": "Turning off 'Show line numbers' through Search Everywhere should uncheck it in Settings.",
  "requires": {"since": "262.10000"},
  "setup": {"window": {"width": 1600, "height": 1000}, "layout": "auto"},
  "steps": [
    {"action": "set", "option": "Appearance: Show line numbers:", "value": false,
     "intent": "turn line numbers off the way Search Everywhere does"},
    {"action": "settings", "page": "editor.preferences.appearance", "intent": "open Editor > General > Appearance"},
    {"action": "expect", "name": "Show line numbers", "is": "unchecked",
     "bug": "Settings shows line numbers as on after they were turned off"},
    {"action": "close", "intent": "close Settings"}
  ],
  "cleanup": [
    {"action": "close"}
  ]
}
```

The replay turns line numbers back on and gives the IDE window its size back by itself, after the cleanup.

| Field | Required | Meaning |
|---|---|---|
| `scenario` | yes | The format version, `1`. A version this IDE does not know fails the replay instead of running it half-understood |
| `title` | yes | What the scenario shows, as an issue title would say it |
| `issue` | no | The issue it reproduces or checks, such as `IDEA-123456` |
| `ide` | no | The IDE build it was recorded or last repaired on, as `IU-262.10968.63`. A replay on another build says so, as a hint that a failing step may need repair rather than that the bug is back |
| `project` | no | What must be open: a project, a file layout, a plugin. The steps do not open projects |
| `description` | no | What the scenario is about in a few sentences, such as the report it reproduces, for the next agent |
| `requires` | no | The IDE the scenario means anything on, described below. Another IDE gives `SKIPPED` |
| `setup` | no | How to lay the IDE out before the steps, described below |
| `steps` | yes | The steps, in order |
| `cleanup` | no | Steps that run after the others whether they passed or failed. Each cleanup step runs even when the one before it failed, so a `close` with nothing open stops nothing |
| `$schema` | no | `mcp-steroid://ide/ui-scenario-schema`, the JSON schema of the format, which the replay ignores |

Unknown fields fail, in the header and in every step, so a typo never passes as a no-op. Keep the file next
to the other evidence of the issue; its path is absolute or relative to the project.

`steroid_fetch_resource` with `mcp-steroid://ide/ui-scenario-schema` returns the JSON schema of the file. It
names every field and value and the fields each step needs; the replay also checks the rules that span
fields, such as a `check` that needs a target or a path.

## setup: lay the IDE out the same way on every replay

The `setup` block runs before the steps, on every replay, one that starts at a later step with `from_step`
too, so each one starts from the same layout. Its parts run in this order:

| Part | Example | Does |
|---|---|---|
| `settings` | `[{"registry":"ide.balloon.shadow.size","value":"0"},{"option":"Show line numbers","value":true}]` | A `set` step per entry |
| `menu` | `"merged"` | How the main menu shows: `merged` into the main toolbar, `hamburger` under the Main Menu button, or `toolbar`, a menu bar of its own. Only the new UI on Windows and Linux has the setting |
| `window` | `{"width":1600,"height":1000}` or `{"maximize":true}` | Sizes the IDE window, whatever dialog shows. It stays on its screen, and a screen smaller than asked holds it at the screen's size, as the report says |
| `toolwindows` | `{"Project":{"width":"fit"},"Problems View":{"height":250},"Terminal":{"hide":true}}` | A `toolwindow` step per tool window ID, with `width`, `height`, `tab` or `hide` |
| `steps` | `[{"action":"goto","file":"src/A.kt","line":1}]` | Any steps, after the parts above |
| `layout` | `"auto"` | What the steps do about a window that cuts controls, below |

A setup step that fails stops the replay before the steps with `BROKEN at setup step N`, since the steps
would meet an IDE unlike the one they expect.

`layout` decides what happens when a step opens a window, or shows or sizes a tool window, that cuts
controls, the condition a snapshot's `layout:` line reports:

| `layout` | Does |
|---|---|
| `note` (default) | The step's report gets the `layout:` line |
| `check` | The same, and it counts as a failed soft check, which the verdict lists |
| `auto` | Makes room with the step the line names, and reports `made room:`, even after a step that set the size too small. It also makes room in the IDE window before the first step, and before a click on a control past an edge, which it then retries once |

`auto` suits a scenario that must run on any screen; `check` suits one that checks a layout bug, where a cut
control is the finding.

## requires: the IDE a scenario is for

A scenario that means nothing on some IDEs says so in `requires`, and a replay there gives `SKIPPED` with
what the IDE lacks, instead of a `BROKEN` that sends the next agent repairing steps that are fine:

| Field | Example | Holds when |
|---|---|---|
| `since`, `until` | `"262.10000"`, `"262.*"` | The build is in the range, compared number by number, as a plugin's `since-build` and `until-build` are; a `*` matches any number from there on |
| `products` | `["IU","IC"]` | The product code is one of these. In Split Mode the JetBrains Client checks its own |
| `plugins` | `["org.jetbrains.kotlin"]` | Every plugin is enabled on the side that replays |
| `os` | `["windows","linux"]` | The OS is one of `windows`, `macos`, `linux` |
| `mode` | `"split"` | Split Mode, or `monolith` for a regular IDE |

## The replay puts the IDE back

A replay of a whole scenario records what each step changes, and after the cleanup puts it back, the last
change first, as `restore step` lines:

| Step | What is put back |
|---|---|
| `set` | The value before, for every kind; an inspection gets its severity and then its on or off state; a theme gets the theme before, or following the OS again |
| `check`, `uncheck` or `menu` of a checkable main menu item | Its state before; for one of a group, such as the main menu modes, the item that was checked |
| `menu` with `mode` | The menu mode before |
| `toolwindow` | The tab, the size and whether it showed |
| `window` on the IDE window | Its size, or maximized |
| `window` on a dialog or the Settings window | The size the IDE saved for its next opening: the window closes, but Settings and most dialogs reopen at their last size |
| `splitter`, and a screenshot's `fit` | The pane's size while it shows, and the proportion the IDE saved for the splitter's next opening |
| `write` | The text before, or no file when the step created it |
| Any step that changed project files: a refactoring through its dialog, a generator, typing, a `code` step | Each file's text before, and no file where a step created one |

Only the first change of each state counts, since its restore brings back what the IDE had before the run.
`setup` steps are put back the same way. The code the steps changed is put back as a person would review it,
file by file; changes made outside the IDE during the run are left alone. Local History also gets a label before
the steps, `steroid_ui: before "<title>"`, to revert to by hand. Other changes of a `code`, `perf` or `run` step,
and of a click in a dialog, are unknown to the restore; the cleanup handles those. A run that stops before the last step, with `to_step`, restores
nothing and ends with an `undo:` line that lists the restore steps.

## Replay and verdicts

Pass `scenario` instead of `steps`. `from_step` and `to_step` run part of it, numbered from 1, as when
repairing one step; the cleanup runs only when the last step runs. To replay several, pass a folder, which
replays every `*.scenario.json` under it in path order, or a JSON array of files and folders. Each file replays
as its own call would, and the result starts with a count per verdict and one verdict line per file, followed
by each file's report. The response lists each step with its
intent and ends with one verdict:

| Verdict | Meaning |
|---|---|
| `PASSED` | Every step was done and every check held |
| `FAILED at step N` | A check did not hold: the IDE behaves otherwise than the scenario says it should. The step's report says what it found |
| `BROKEN at step N` | A step could not be done, or in a reproduction, the steps did not reach the bug check. Repair the step by its intent |
| `REPRODUCED at step N: <bug>` | A bug check failed: the bug is present |
| `NOT REPRODUCED` | Every bug check passed: the bug is fixed, or the scenario no longer reaches it |
| `INCOMPLETE` | `to_step` stopped the run before a bug check |
| `SKIPPED` | The IDE does not meet the scenario's `requires`, which the line names; no step ran |

`FAILED` and `BROKEN` mark the call as an error; `REPRODUCED` does not, because showing the bug is what a
reproduction is for. A `bug` check also works in a plain `steps` call, which is how a reproduction is checked
before it is saved.

## expect: check what the IDE shows

`expect` retries its check until it holds or `timeout_ms` passes (default 5000), as Playwright's assertions
do, because the IDE updates asynchronously: a dialog fills in, a list filters, an error arrives a moment
after its cause. Never add a wait before an expect. With `"not": true` it retries until the check does
*not* hold. `"soft": true` reports a failed check and goes on, for secondary checks; a bug check cannot be
soft. Each expect has one subject:

| Subject | Checks | Example |
|---|---|---|
| A target (`name`, `text`, `class`, `ref`, `nth`) | `is`: visible, hidden, enabled, disabled, checked, unchecked, focused, editable; `value` (exact); `contains`; `matches` (a regex); `count` (how many controls match, in the topmost window that has a match) | `{"action":"expect","name":"Apply","is":"disabled"}` |
| A row of a list, tree, table or tabbed pane | `row` (or `index`) alone for present, with `is`: selected, expanded, collapsed | `{"action":"expect","name":"Settings categories","row":"Terminal","is":"selected"}` |
| A table row's cells | `row` with `value` (one cell equals it), `contains` or `matches`, over the cells after the first, which `row` names | `{"action":"expect","class":"TreeTable","row":"Hard wrap at:","value":"90"}` |
| `title`: a window | `is`: visible (default) or hidden | `{"action":"expect","title":"Rename","is":"hidden"}` |
| `file`: a project file's text | `value`, `contains` or `matches`, over `line` N when given | `{"action":"expect","file":"src/A.kt","line":3,"contains":"newName"}` |
| `file` with `caret` | The caret in the file's editor, as `line:column`, 1-based | `{"action":"expect","file":"src/A.kt","caret":"3:14"}` |
| `file` with `golden` | The whole text of the file equals a golden file's, relative to the project; a mismatch shows the diff, `-` for the golden text and `+` for the file's | `{"action":"expect","file":"src/A.kt","golden":"expected/A.kt"}` |
| `changed` | Exactly these project files changed, were created, deleted or moved since the steps started; `[]` for none, as after a refactoring its conflicts stopped | `{"action":"expect","changed":["src/A.kt","src/B.kt"]}` |
| `diff`, with `file` or alone | Lines of the run's diff, of the file or of any file, one after another in one change: each starts with `+` (added), `-` (removed) or a space (unchanged), and is compared without indentation | `{"action":"expect","file":"src/A.kt","diff":"-val x = a + b\n+val x = sum(a, b)"}` |
| `console` | The output of the latest run named so, an application, a test or a Maven or Gradle task, `""` for the latest, with `contains` or `matches` | `{"action":"expect","console":"App","contains":"total: 5"}` |
| `notification` | A notification shown since the call started, or listed in the Notifications tool window, whose title or text contains this | `{"action":"expect","notification":"Indexing"}` |
| `banner` | A banner above one of the project's open editors, of any kind, whose text contains this | `{"action":"expect","banner":"Module JDK is not defined","not":true}` |
| `error` | An IDE error logged since the call started whose summary contains this; `""` or `true` matches any | `{"action":"expect","error":"","not":true}` |
| `editor` | An editor of a project file, by path or name, that this side shows: `is` visible (default), focused, or hidden for none. It checks what the user sees, where `file` checks the text | `{"action":"expect","editor":"src/A.kt","is":"focused"}` |
| `log` | A line of this side's `idea.log`, written since the run started, that contains this, not counting MCP Steroid's own lines. It checks the mechanism behind a symptom, such as an editor opening | `{"action":"expect","log":"Opening remote editor for file=A.kt","side":"backend"}` |
| `layout` | That the topmost window has no `layout:` line (no tool window narrower than its header, no control past the edge of its panel or window or partly cut), or with a target, that nothing under it is `[outside]` or `[clipped]`. It checks a report of a hidden button or a cut-off dialog, and makes sure a later step can reach its control | `{"action":"expect","layout":true,"name":"Project Tool Window"}` |
| `memory` + `below` | A memory figure of this side under a limit: `heap_after_gc`, the heap in MB right after a full GC, which the check runs first unless the IDE disables explicit GC, as its report then says; `heap`, the heap in use in MB; `threads`; or `gc_signals`, the overloaded-GC signals of the last 15 minutes. It checks a memory leak fix or a thread leak | `{"action":"expect","memory":"heap_after_gc","below":1500}` |

A check that fails says what it wanted and what it found, and for a target that matched nothing, the
nearest controls. `{"action":"expect","error":"","not":true}` after the steps is the check for a report of
an exception or a red error balloon; it waits a second for late errors first.

## Set the IDE up without dialogs

`get` and `set` read and change configuration through the platform's own models, in milliseconds, with no
window opened. A set reports the value before and after, and a scenario's replay puts the value before back.

| Kind | Step | What it reaches |
|---|---|---|
| `option` | `{"action":"set","option":"Show line numbers","value":false}` | An on/off option as Search Everywhere lists it: most Settings checkboxes of the IDE and the project, several thousand. `get` with part of a name lists the matches, their values and their Settings page id. A set needs one match: pass the full name, such as `Appearance: Show line numbers:` |
| `registry` | `{"action":"set","registry":"ide.balloon.shadow.size","value":"0"}` | A registry key. An unknown key lists similar keys |
| `advanced` | `{"action":"set","advanced":"editor.tab.painting","value":"ARROW"}` | An advanced setting by id; an enum takes its constant's name, and a wrong one lists the constants |
| `inspection` | `{"action":"set","inspection":"UnusedDeclaration","value":"off"}` | An inspection of the project's current profile by short name: `on`, `off`, or a severity such as `ERROR`, `WARNING`, `WEAK WARNING`, `INFORMATION`. Highlighting restarts |
| `component` + `field` | `{"action":"set","component":"EditorSettings","field":"IS_WHITESPACES_SHOWN","value":"true"}` | A field of a persistent settings component, by the state name it is saved under. `get` with `component` alone shows its saved XML, which lists the fields that differ from their defaults. Only components already loaded are found, and a field that holds structured XML needs a `code` step |
| `log` | `{"action":"set","log":"#com.jetbrains.rdserver.fileEditors","value":"debug"}` | A debug log category, as Help \| Diagnostic Tools \| Debug Log Settings sets it: `trace`, `debug`, `all`, or `default` to remove the level set for it. It lasts across restarts until the replay puts the level before back. Set it before the steps whose log lines an `expect` on `log` checks |
| `theme` | `{"action":"set","theme":"Light"}` | An installed theme by its name, without case, or its id; `"sync"` follows the OS's light or dark mode again. It takes no `value`. The step waits until the theme is current and every window has repainted, 30 s by default, since a switch takes seconds. The editor color scheme follows the theme, as the Appearance page switches it. `{"action":"get","themes":true}` lists the installed themes with their ids and the current one |

`get` also reads what no `set` changes:

- `{"action":"get","editors":true}` lists the open editors of each side, with how many editors a file has
  when it is more than one, and the selected file. On a Remote Development backend it also lists what the
  backend keeps for each JetBrains Client session. Through a JetBrains Client it adds the backend's record and a
  `mismatch:` line for each disagreement.
- `{"action":"get","file":"src/A.kt"}` gives a project file's type, language, size and editor providers, and on
  a backend how many editors each Client session has of it. It shows, for example, whether `.env.local` is a
  DotEnv file in this IDE or plain text.
- `{"action":"get","memory":true}` gives this side's memory, in the order of the status bar memory indicator's
  tooltip: the heap used, committed and max, and what stayed in use after the last GC; direct buffers; non-heap
  pools and threads; memory-mapped files; the OS figures; and the GC's share of the time since the previous
  report, with how often it was overloaded in the last 15 minutes. A `LOW MEMORY` notice in front of a tool
  result points here.
- `{"action":"get","builds":true}` lists the recent builds and syncs, the IDE's own and Maven's and Gradle's, each
  with its outcome and first errors as `path:line: message`. A `BUILD FAILED` notice in front of a tool result
  names the failures since the previous call.
- `{"action":"get","problems":"src/A.kt"}` lists the errors the editor highlights in an open file as
  `path:line:column: SEVERITY text`, and `"problems":true` in every open file. `"severity":"warning"`,
  `"weak_warning"` or `"info"` adds the lower levels. The problems are what the editor's analysis found the last
  time it ran. The editor analyzes a file while its tab shows, so the step waits up to its timeout for those
  files and names any file not analyzed to the end, such as a tab behind another. A closed file has no
  problems, so the step says to open it. In Split Mode it runs on the backend. An
  `EDITOR ERRORS` notice in front of a tool result counts the errors per open file when one is new, with the
  first of each.
- `{"action":"get","notifications":true}` lists the notifications the IDE showed, the newest first, with their
  actions. An `IDE NOTIFICATIONS` notice names the ones shown since the previous call, errors and warnings first;
  in Split Mode the JetBrains Client tells them, the backend's own included.
- `{"action":"get","console":"App","lines":40}` reads the last lines of the latest run named App, `""` for the
  latest run, with its state and exit code; error lines start with `! `. A `RUN FAILED` notice names the runs that
  exited with an error since the previous call.
- `{"action":"get","changes":true}` gives the diff of the project files the steps changed so far, which each
  response otherwise ends with, cut to its first lines.

Other setup steps:

- `{"action":"settings","page":"Code Folding"}` opens Settings at a page, found by id, by path such as
  `Editor > General > Appearance`, or by name; several pages share names such as General, and the error lists
  their paths. An open Settings window switches to the page. The recording keeps the page id, which does not
  change with the UI language.
- `{"action":"toolwindow","id":"Problems View","tab":"Project Errors"}` shows and activates a tool window and
  selects a tab; `"hide":true` hides it. An unknown id lists the ids. `"width"` or `"height"` sizes it, in
  logical pixels or `"fit"`.
- `{"action":"menu","path":"View > Appearance > Status Bar"}` runs a main menu item by its path, whichever way
  the IDE shows the menu, including the macOS screen menu bar; a checkable item's report gives its state before
  and after. A path to a submenu lists its items.
- `{"action":"check","path":"View > Appearance > Status Bar"}`, or `uncheck`, runs a checkable main menu item
  only when its state differs, so the step leaves the item checked, or unchecked, however it started.
- `{"action":"menu","mode":"merged"}` sets how the main menu shows: `merged`, `hamburger` or `toolbar`.
- `{"action":"menu","path":"View > Appearance","show":true}` opens the main menu along the path as a person
  does and leaves it open for a picture; a last segment that is an item is highlighted. A menu outside the IDE
  window, such as the macOS screen menu bar, cannot be pictured and fails.
- `{"action":"window","width":1800,"height":1200}` sizes the topmost window, or the one a target or `title`
  names; `"maximize":true` fills the screen and `false` restores it. The report gives the size it had.
  `"class":"IdeFrameImpl"` names the IDE window whatever dialog shows.
- `{"action":"write","file":"src/Sample.kt","text":"..."}` creates or replaces a project file, with its
  folders, through the IDE's documents, so the editor and the index see it at once; `"delete":true` in place
  of `text` deletes it. A path outside the project folder is refused.
- `{"action":"code","code":"...","modal":"non_modal"}` runs a Kotlin body exactly as `steroid_execute_code`
  does, for setup that no step covers, and fails the step when the script fails. Its default `modal` closes
  open dialogs, so pass `non_modal` or `dialog` in the middle of a dialog flow.

## Pictures: captures with highlights

`{"action":"screenshot","save":"appearance-page"}` saves a picture of the topmost window as
`screenshots/appearance-page.png` in the call's execution folder, and the step's report gives the full path.
With a target, such as `{"action":"screenshot","name":"Settings categories","save":"tree"}`, it pictures the
window that holds the target. It paints the IDE's own windows, never the rest of the screen, so other
applications do not show through, and lets the UI settle first. A menu, list popup or completion open above
the window is in the picture at its place, and a picture of such a popup shows the window it opened from.
The report, and the `.json` file beside the picture, give what makes two pictures of one state differ: the
window's size, the screen's scale and the IDE's zoom, the theme, the editor font, the build, the OS and the crop.

| Field | Example | Does |
|---|---|---|
| `save` | `"appearance-page"` | The picture's name in the execution folder. Name each picture after the state it shows |
| `out` | `"C:/docs/img/appearance.png"` | The picture's path instead: absolute in a call with `steps`, relative to the scenario file's folder in a replay. PNG, the default for a path without an extension, or JPEG for `.jpg`. The folders are made |
| `highlight` | `["breadcrumb", {"name":"Show line numbers","label":"Turn this on"}]` | Outlines each control, numbered 1, 2, 3 in this order when there are several (see `numbers`). An item takes any locator, plus `row` or `index` for a row of a list, tree, table or tab row, and `label`, text drawn beside it. `"breadcrumb"` is the path above the Settings page. The kinds for code, clicks, inspections and consoles are below |
| `crop` | `"page"`, `"highlights"`, `"popups"`, `{"toolwindow":"Run"}`, `{"name":"Settings categories"}` | Cuts the picture to the Settings page with its breadcrumb, to the highlights, to the open menus, to a tool window by its id, or to a control's visible part. The whole window without it |
| `margin` | `8` | The padding around a crop, 16 px without it |
| `fit` | `true` | Runs the steps the `cut:` lines name before the picture, and puts the sizes back with the restore |
| `numbers` | `false` | Numbers the highlights 1, 2, 3 as steps to follow, or with `false` outlines them only, for areas with no order. Without it, several highlights are numbered and a single one is only outlined |

A highlight out of view is scrolled to the middle of its view first; a control larger than its view, such as a
tree, is outlined as far as it shows. A highlight that is not showing, such as one on another tab, fails the
step rather than outlining the wrong place. The badge with the number sits just left of its outline, and a
label right of it on the same line; where they would cover the text of another control, such as the next tab
of a tab row, they go below the outline, or above it. Number the highlights when they are steps to follow in
order; outline one area, or several with no order, without numbers. In Split Mode the JetBrains Client takes
the picture and saves the file on its machine; a highlight on a host Settings page, whose controls exist only
on the backend, is found there.

The report ends with a `cut:` line for each content the picture shows cut, with the step that makes room, so a
bad picture is known without looking at it:

```
cut: XDebuggerTree [ref=e109] shows 3 of 8 rows; {"action":"splitter","ref":"e109","size":"fit"} makes room
cut: the header "Default parameter" of TableView [ref=e223] is cut; {"action":"window","width":612} makes room
```

Run the step and take the picture again, or pass `"fit": true` to run them first. In Split Mode the lines also
cover a host Settings page, whose controls live on the backend: the JetBrains Client asks the backend for them
with `{"action":"get","layout":true,"side":"backend"}`, runs a window step on its own window, and any other
step, such as a splitter's, on the backend.

### Code, the click point, inspections and consoles

| Highlight | Outlines |
|---|---|
| `{"lines":"20-27","file":"src/a.ts"}` | Those lines as the editor paints them, wrapped lines and folds included; `file` is optional for the selected editor. `"crop":"highlights"` cuts to them and keeps the line numbers; the caret is hidden while the picture paints |
| `{"symbol":"parse","nth":1}` | A name in the editor, its first occurrence unless `nth` counts on |
| `{"click":true}` | The point of the call's last click, drawn as a mouse pointer. On the outline of what it clicked, such as a `symbol` highlight, it is part of that step, without a number or label of its own |
| `{"inspection":"NullableProblems"}` | The inspection's row on the Settings Inspections page, its groups expanded |
| `{"console":"App","contains":"Exception"}` | The last line of a run's console that holds the text, in a console built on an editor or on a terminal |

A `click` on an editor lands at its caret, at `line` and `column`, or just past the end of a `symbol`, where
the menu a right click opens leaves the word showing beside it. A picture of a context menu is one call, the
word as step 1 with the pointer on it and the menu item as step 2:

```
[{"action":"goto","file":"src/format.ts","symbol":"parseThreadUrl"},
 {"action":"click","class":"EditorComponentImpl","button":"right","symbol":"parseThreadUrl"},
 {"action":"screenshot","out":"C:/pics/refactor-menu.png","highlight":[{"symbol":"parseThreadUrl"},{"click":true},{"text":"Refactor"}],"crop":"popups"},
 {"action":"press","keys":"ESCAPE"}]
```

`"crop":"popups"` keeps the open menus and every highlight, and reaches left to the line numbers of code, so
the clicked word shows beside its menu. Badges and labels keep off the lines of code in view.

`{"action":"select","inspection":"NullableProblems"}` selects that row on the Inspections page.

### Tabs of run and debug tool windows

A tool window lists its runs as tabs: `{"action":"toolwindow","id":"Debug","tab":"App"}` switches to one. The
inner tabs of a run, such as Threads & Variables and Console, are rows of their `JBRunnerTabs`, as the tabs of
`GridCellTabs` and `EditorTabs` are: `{"action":"select","class":"JBRunnerTabs","row":"Console"}` switches, and
a highlight with the same `row` outlines the tab. `"crop":{"toolwindow":"Debug"}` cuts the picture to the tool
window whichever run it shows. A `run` step of `RunClass`, `DebugClass`, `Rerun` or `Stop` says which runs it
started or stopped, or that it started none, as a configuration that already runs does.

### Splitters

A snapshot lists each splitter with its state, such as `OnePixelSplitter [ref=e40] horizontal 0.25` or
`ThreeComponentsSplitter [ref=e41] horizontal first 240 px, last 0 px`.

- `{"action":"splitter","ref":"e40","proportion":0.3}` gives the first pane that share.
- `{"action":"splitter","ref":"e41","size":240}` gives the pane that holds the target that many pixels; the
  target can be a control inside the pane, whose nearest splitter moves.
- `{"action":"splitter","ref":"e109","size":"fit"}` gives the pane the room its content lacks, along the axis
  where the control is cut, within what the other panes' minimum sizes leave.

The report gives the pane's size and the proportion before and after, what held the divider back, and when the
other pane now cuts content, the window step that gives both room. The restore puts the divider back, and the
proportion the IDE saves for the next opening.

When `out` replaces a PNG, the report adds `unchanged`, or `changed: N pixels differ`, so a replay of
documentation pictures names the stale ones.

### Frame the picture

Set the frame up with steps before the `screenshot`:

- `{"action":"window","width":1400,"height":900}` sizes Settings or the dialog, so every replay pictures the
  same frame. The restore puts back the size the IDE saved for its next opening.
- `{"action":"scroll","name":"Show whitespaces","align":"top"}` scrolls a long page so the control is at the top
  of its view; `"center"` puts it in the middle.
- `{"action":"menu","path":"View > Appearance","show":true}` opens a menu and leaves it open, with
  `"crop":"popups"` on the picture.
- `{"action":"set","theme":"Light"}` pictures the IDE in a theme; `get` with `"themes":true` lists the installed
  ones.

### One call that leaves the IDE as it was

A `steroid_ui` call with `"restore": true` closes the windows and menus its steps opened, and puts back what
they changed, as a replay does, whether the steps passed or failed. A picture for a user or an article is one
such call:

```
[{"action":"settings","page":"editor.preferences.appearance"},
 {"action":"screenshot","out":"C:/pics/line-numbers.png","highlight":["breadcrumb",{"name":"Show line numbers"}],"crop":"page"}]
```

### Documentation screenshots

A scenario per documentation page keeps its pictures current: replay it on a new build and the pictures are
saved again, each report saying whether it changed. Fix the size and the theme so that every replay pictures
the same thing, and give `out` relative to the scenario file, so the pictures land in the documentation's
folder wherever it is checked out:

```
{
  "scenario": 1,
  "title": "Pictures of Editor > General > Appearance",
  "setup": {"steps": [{"action": "set", "theme": "Light"}]},
  "steps": [
    {"action": "settings", "page": "editor.preferences.appearance"},
    {"action": "window", "width": 1100, "height": 750},
    {"action": "screenshot", "out": "img/appearance.png", "crop": "page"},
    {"action": "screenshot", "out": "img/line-numbers.png", "highlight": [{"name": "Show line numbers"}], "crop": "highlights", "margin": 24}
  ],
  "cleanup": [{"action": "close"}]
}
```

Replay a folder of such scenarios to refresh them all. Name controls by `name` or `text` in a scenario's
highlights: a ref from a snapshot does not carry over to another session.

Use pictures for what text cannot check: icons, colors, a theme, how a layout looks, and for people. For
anything a snapshot shows, an `expect` is the stronger check, because it fails on its own, and cut or hidden
controls are one of those: `{"action":"expect","layout":true}`.

## Editor steps from the Performance Testing plugin

`{"action":"perf","command":"..."}` runs playback commands of the Performance Testing plugin, which
JetBrains IDEs bundle for their own UI tests, one command per line. An unknown command fails before
anything runs and lists similar ones; a failing assert fails the step with its message.

| Command | Does |
|---|---|
| `%openFile <path from the project root>` | Opens a file in the editor |
| `%goto <line> <column>` | Moves the caret |
| `%moveCaret <text>` | Puts the caret before the text's first occurrence |
| `%executeEditorAction <action id>` | Runs an editor action, such as `EditorLineEnd` or `EditorDuplicate` |
| `%delayType <ms>\|<text>` | Types text key by key, as a user does, so typing handlers, completion and auto-popups run |
| `%replaceText -startOffset <n> -endOffset <n> -newText <text>` | Replaces a range of the document |
| `%doLocalInspection` | Runs the editor's highlighting and inspections on the open file and waits for them |
| `%assertCaretPosition <line> <column>`, `%assertCurrentFile <name>` | Fail the step when the caret or the file differ |

Prefer `goto` to put the caret on a symbol and `expect` to check a result: they report more. Reach for
`perf` when the report is about typing itself, as `%delayType` drives the same handlers a keyboard does.

## Navigating dialogs

Most dialogs are two steps: a `run` of the action that opens them, then `select` in their navigation list.
Project Structure, for example:

- `{"action":"run","id":"ShowProjectStructureSettings"}`
- `{"action":"select","name":"Project structure categories","row":"Facets"}` (or Modules, Libraries,
  Artifacts, SDKs, Global Libraries, Problems)
- the middle tree lists the items: `{"action":"select","class":"Tree","nth":0,"row":"mcp"}`

Settings has its own `settings` step. A `select` waits for its row as long as its timeout, so a tree that
filters to a search typed just before is ready by the time it selects.

## Make a scenario replay the same way every time

These follow the practices of Playwright and other UI test tools:

- **Target what the user sees.** A step by `name` survives a layout change; a `ref` lives only in one call,
  and the recording replaces refs for this reason. A name matches without a caption's trailing colon and
  with either spelling of an ellipsis. Avoid `xpath` and `nth` where a name or a class tells controls apart.
- **Never sleep.** Steps wait for their target, `select` for its row, a step that opens a window for the
  window, and `expect` retries. A fixed delay is either too short on a slow machine or wasted on a fast one.
- **State the correct behavior.** A check says what should happen, never what goes wrong, so the same file
  reports `REPRODUCED` before a fix and `NOT REPRODUCED` after it, or `FAILED` on the build that broke it.
  Keep to one bug check per reported problem.
- **Set up by value, not by clicks.** A `set` step pins a setting the bug depends on whatever the machine had.
  Clicks are for the part of the report that is about the UI.
- **Pin the layout.** A `setup` block with the window's size and the width of each tool window the steps use
  gives every machine the same layout: a scenario recorded on a large screen otherwise meets cut controls on a
  small one, and its pictures do not line up. Add `"layout":"auto"` to make room wherever it still runs short.
- **Say which IDE it is for.** `requires` turns a replay on an IDE without the feature into `SKIPPED`.
- **Leave the IDE as you found it.** The replay puts back what the steps changed; close what they opened in
  `cleanup`.
- **Keep the intent current.** When a step changes during a repair, its intent is what it must still achieve.
  Update `ide` to the build the repair was made on.

## Split Mode

In Split Mode, replay a scenario through the JetBrains Client's endpoint. Each step runs on one side, and the
client sends a step to the Remote Development backend when:

- the step says `"side": "backend"`, as a step on a window the backend draws needs: a host Settings page's
  controls, the Commit tool window, a refactoring dialog; or
- the step needs the project itself, which only the backend holds, and names no side: `write`, `code`,
  `goto`, an `expect` on a `file`, a `banner` or a `console`, `get` or `set` of an `inspection`, and `get` of a
  `file`, the `builds` or a `console`. The file `goto` opens on the backend
  shows in the client's editor, which then has the focus, so a `run` after it acts on that file. The
  `goto` fails when the client shows no editor of the file.

Everything else runs in the client: its windows, Settings dialog, tool windows and pictures. A step on the
backend reports `on the backend:`, and the verdict covers both sides. A relative scenario path resolves against
the backend's project folder. A client snapshot says so on a window whose controls the backend draws, such
as a Rename dialog: its steps need `"side": "backend"`. These things differ by side:

- `get` and `set` of an `option`, `registry` or `advanced` setting reach the side the step runs on, and both
  sides keep their own values. Turning line numbers off in the client leaves the backend's Settings page
  showing them on. Pick the side the report is about.
- An `expect` on `error`, `notification` or `log` sees the side it runs on: add `"side": "backend"` for the
  backend's. Either way it counts from the start of the run, not of the step. A `get` or `set` of a `log` level
  also reaches the side it runs on.
- After steps that can open, close or switch editors, a call through the JetBrains Client compares its editors
  with the backend's record of them and starts with an `EDITOR STATE` notice when they disagree, each
  disagreement once per task. A file the backend keeps an extra editor of opens neither from the Project view nor
  from a navigation until its tab is clicked.
- A backend endpoint refuses a step with `"side": "frontend"`, because it cannot reach the client.
- A restore runs on the side its step ran on: the backend reports the restores of a step the client sent it.
- The steps' code changes are not tracked, so there is no `code changes:` summary and no `changed` or `diff`
  check: the client holds no project files, and the backend runs each step it is sent as a call of its own.
  Check a file's text with an `expect` on `file`.

See [Split Mode](mcp-steroid://skill/split-mode) for what each side draws.

## Compatibility contract

A scenario file is evidence that is kept with an issue and replayed months later, so the format changes only
by growing. Within format 1:

- A released step, field, value or verdict is never removed or renamed.
- A released step keeps its meaning and its defaults. A step that parsed when it was released keeps parsing.
- A new step, field or value is optional, and a scenario that does not use it behaves as before.

Every MCP Steroid version replays every format 1 scenario written for it or for an older version. An older
version refuses a scenario that uses a newer step or field, because unknown names fail. The error then says
that the scenario can come from a newer MCP Steroid and that the plugin needs an update. It never skips a step
it does not know.

`scenario` goes up only for a change that an older reader would misread, and only when no additive change can
do the same job. A new format keeps the old one readable: the reader accepts both versions.

`UiScenarioFormatTest` holds the contract. Its fixture, `ui-scenarios/format-1.scenario.json` in the server
tests, uses every released step, field and value. Removing or renaming one fails the parse, and adding one fails
the coverage check until the fixture uses it. The fixture takes new lines only: editing or deleting a line to
make the test pass is a breaking change. `UiScenarioSchemaTest` checks that the schema names every field and
value the parser takes and that the fixture is valid against it.

## Extending the format

A new step, field or check is added in `UiSteps` (parsing and validation, with a message that names the
problem), `UiSession` or one of the classes it dispatches to (`UiExpect`, `UiConfig`, `UiIdeSteps`), the step
list of the `steroid_ui` tool description, this recipe, the schema `ui-scenarios/scenario-1.schema.json` in the
server's resources, and a line in the format fixture. A step that changes the IDE's state gives the steps that
put it back to `UiRestore`.

# See also

- [Find and drive UI controls with steroid_ui and ui helpers](mcp-steroid://ide/ui-driving)
- [Discover IDE actions at caret](mcp-steroid://ide/action-discovery)
- [Split Mode: what runs on the client and what runs on the backend](mcp-steroid://skill/split-mode)
