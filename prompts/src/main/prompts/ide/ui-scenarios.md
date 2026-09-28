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
   through dialogs: a setting set directly is the same on every replay.
2. **Do it** with `steroid_ui` steps, one call at a time, reading each response. Give every step an `intent`,
   in plain words: "open the Rename dialog", "type the new name".
3. **Check** the outcome with `expect` steps that state the *correct* behavior, and save the states worth
   seeing with `screenshot`. In a reproduction, the check that fails while the bug is present gets `"bug"`: a
   sentence that names the bug.
4. **Save** the scenario. Every `steroid_ui` call with steps appends them to the task's recording file, named
   in the response (`recorded: ... to <file>`), with refs already replaced by names, captions or classes, row
   indexes by row text, Settings pages by their id and option names by their full name. The lines of that
   file, minus exploration that led nowhere, are the scenario's `steps`. Add the header fields below and a
   `cleanup` that puts the IDE back.
5. **Replay** it with `steroid_ui` and `scenario` set to the file's path. Replay it once right away: a scenario
   that does not replay in a fresh call is not done.
6. **Replay it again** whenever it matters: after a fix (`NOT REPRODUCED` confirms it), on a new IDE build, or
   before a release. `BROKEN` means a step no longer works, usually because the UI changed: repair the failing
   step by its `intent`, and replay.

## The scenario file

```
{
  "scenario": 1,
  "title": "Line numbers turned off from Search Everywhere show as off in Settings",
  "issue": "IDEA-123456",
  "ide": "IU-262.10968.63",
  "project": "Any project with a text file open",
  "description": "Turning off 'Show line numbers' through Search Everywhere should uncheck it in Settings.",
  "steps": [
    {"action": "set", "option": "Appearance: Show line numbers:", "value": false,
     "intent": "turn line numbers off the way Search Everywhere does"},
    {"action": "settings", "page": "editor.preferences.appearance", "intent": "open Editor > General > Appearance"},
    {"action": "expect", "name": "Show line numbers", "is": "unchecked",
     "bug": "Settings shows line numbers as on after they were turned off"},
    {"action": "close", "intent": "close Settings"}
  ],
  "cleanup": [
    {"action": "close"},
    {"action": "set", "option": "Appearance: Show line numbers:", "value": true}
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `scenario` | yes | The format version, `1`. A version this IDE does not know fails the replay instead of running it half-understood |
| `title` | yes | What the scenario shows, as an issue title would say it |
| `issue` | no | The issue it reproduces or checks, such as `IDEA-123456` |
| `ide` | no | The IDE build it was recorded or last repaired on, as `IU-262.10968.63`. A replay on another build says so, as a hint that a failing step may need repair rather than that the bug is back |
| `project` | no | What must be open: a project, a file layout, a plugin. The steps do not open projects |
| `description` | no | What the scenario is about in a few sentences, such as the report it reproduces, for the next agent |
| `steps` | yes | The steps, in order |
| `cleanup` | no | Steps that run after the others whether they passed or failed. Each cleanup step runs even when the one before it failed, so a `close` with nothing open stops nothing |

Unknown fields fail, in the header and in every step, so a typo never passes as a no-op. Keep the file next
to the other evidence of the issue; its path is absolute or relative to the project.

## Replay and verdicts

Pass `scenario` instead of `steps`. `from_step` and `to_step` run part of it, numbered from 1, as when
repairing one step; the cleanup runs only when the last step runs. The response lists each step with its
intent and ends with one verdict:

| Verdict | Meaning |
|---|---|
| `PASSED` | Every step was done and every check held |
| `FAILED at step N` | A check did not hold: the IDE behaves otherwise than the scenario says it should. The step's report says what it found |
| `BROKEN at step N` | A step could not be done, or in a reproduction, the steps did not reach the bug check. Repair the step by its intent |
| `REPRODUCED at step N: <bug>` | A bug check failed: the bug is present |
| `NOT REPRODUCED` | Every bug check passed: the bug is fixed, or the scenario no longer reaches it |
| `INCOMPLETE` | `to_step` stopped the run before a bug check |

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
| `notification` | A notification shown since the call started, or listed in the Notifications tool window, whose title or text contains this | `{"action":"expect","notification":"Indexing"}` |
| `banner` | A banner above one of the project's open editors, of any kind, whose text contains this | `{"action":"expect","banner":"Module JDK is not defined","not":true}` |
| `error` | An IDE error logged since the call started whose summary contains this; `""` or `true` matches any | `{"action":"expect","error":"","not":true}` |
| `editor` | An editor of a project file, by path or name, that this side shows: `is` visible (default), focused, or hidden for none. It checks what the user sees, where `file` checks the text | `{"action":"expect","editor":"src/A.kt","is":"focused"}` |
| `log` | A line of this side's `idea.log`, written since the run started, that contains this, not counting MCP Steroid's own lines. It checks the mechanism behind a symptom, such as an editor opening | `{"action":"expect","log":"Opening remote editor for file=A.kt","side":"backend"}` |
| `memory` + `below` | A memory figure of this side under a limit: `heap_after_gc`, the heap in MB right after a full GC, which the check runs first unless the IDE disables explicit GC, as its report then says; `heap`, the heap in use in MB; `threads`; or `gc_signals`, the overloaded-GC signals of the last 15 minutes. It checks a memory leak fix or a thread leak | `{"action":"expect","memory":"heap_after_gc","below":1500}` |

A check that fails says what it wanted and what it found, and for a target that matched nothing, the
nearest controls. `{"action":"expect","error":"","not":true}` after the steps is the check for a report of
an exception or a red error balloon; it waits a second for late errors first.

## Set the IDE up without dialogs

`get` and `set` read and change configuration through the platform's own models, in milliseconds, with no
window opened. A set reports the value before and after, which is what a cleanup step restores.

| Kind | Step | What it reaches |
|---|---|---|
| `option` | `{"action":"set","option":"Show line numbers","value":false}` | An on/off option as Search Everywhere lists it: most Settings checkboxes of the IDE and the project, several thousand. `get` with part of a name lists the matches, their values and their Settings page id. A set needs one match: pass the full name, such as `Appearance: Show line numbers:` |
| `registry` | `{"action":"set","registry":"ide.balloon.shadow.size","value":"0"}` | A registry key. An unknown key lists similar keys |
| `advanced` | `{"action":"set","advanced":"editor.tab.painting","value":"ARROW"}` | An advanced setting by id; an enum takes its constant's name, and a wrong one lists the constants |
| `inspection` | `{"action":"set","inspection":"UnusedDeclaration","value":"off"}` | An inspection of the project's current profile by short name: `on`, `off`, or a severity such as `ERROR`, `WARNING`, `WEAK WARNING`, `INFORMATION`. Highlighting restarts |
| `component` + `field` | `{"action":"set","component":"EditorSettings","field":"IS_WHITESPACES_SHOWN","value":"true"}` | A field of a persistent settings component, by the state name it is saved under. `get` with `component` alone shows its saved XML, which lists the fields that differ from their defaults. Only components already loaded are found, and a field that holds structured XML needs a `code` step |
| `log` | `{"action":"set","log":"#com.jetbrains.rdserver.fileEditors","value":"debug"}` | A debug log category, as Help \| Diagnostic Tools \| Debug Log Settings sets it: `trace`, `debug`, `all`, or `default` to remove the level set for it. It lasts across restarts, so a cleanup step sets `default`. Set it before the steps whose log lines an `expect` on `log` checks |

`get` also reads three things no `set` changes:

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

Other setup steps:

- `{"action":"settings","page":"Code Folding"}` opens Settings at a page, found by id, by path such as
  `Editor > General > Appearance`, or by name; several pages share names such as General, and the error lists
  their paths. An open Settings window switches to the page. The recording keeps the page id, which does not
  change with the UI language.
- `{"action":"toolwindow","id":"Problems View","tab":"Project Errors"}` shows and activates a tool window and
  selects a tab; `"hide":true` hides it. An unknown id lists the ids.
- `{"action":"write","file":"src/Sample.kt","text":"..."}` creates or replaces a project file, with its
  folders, through the IDE's documents, so the editor and the index see it at once. A path outside the
  project folder is refused.
- `{"action":"code","code":"...","modal":"non_modal"}` runs a Kotlin body exactly as `steroid_execute_code`
  does, for setup that no step covers, and fails the step when the script fails. Its default `modal` closes
  open dialogs, so pass `non_modal` or `dialog` in the middle of a dialog flow.

## Pictures for a visual review

`{"action":"screenshot","save":"appearance-page"}` saves a picture of the topmost window as
`screenshots/appearance-page.png` in the call's execution folder, and the step's report gives the full path.
With a target, such as `{"action":"screenshot","name":"Settings categories","save":"tree"}`, it pictures the
window that holds the target. It paints only that window, never the rest of the screen, and lets the UI
settle first. Read the saved file to review it, or keep it next to the scenario to compare with a later run.
`save` is required: name each picture after the state it shows, so a replay's pictures line up with the
earlier ones.

Use pictures for what text cannot check: layout, icons, colors, clipping, a theme. For anything a snapshot
shows, an `expect` is the stronger check, because it fails on its own.

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
- **Leave the IDE as you found it.** Restore every `set` in `cleanup`, and close what the steps opened.
- **Keep the intent current.** When a step changes during a repair, its intent is what it must still achieve.
  Update `ide` to the build the repair was made on.

## Split Mode

In Split Mode, replay a scenario through the JetBrains Client's endpoint. Each step runs on one side, and the
client sends a step to the Remote Development backend when:

- the step says `"side": "backend"`, as a step on a window the backend draws needs: a host Settings page's
  controls, the Commit tool window, a refactoring dialog; or
- the step needs the project itself, which only the backend holds, and names no side: `write`, `code`,
  `goto`, an `expect` on a `file` or a `banner`, `get` or `set` of an `inspection`, and `get` of a `file`. The file `goto` opens on the backend
  shows in the client's editor, which then has the focus, so a `run` after it acts on that file. The
  `goto` fails when the client shows no editor of the file.

Everything else runs in the client: its windows, Settings dialog, tool windows and pictures. A step on the
backend reports `on the backend:`, and the verdict covers both sides. A relative scenario path resolves against
the backend's project folder. A client snapshot says so on a window whose controls the backend draws, such
as a Rename dialog: its steps need `"side": "backend"`. Three things differ by side:

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
make the test pass is a breaking change.

## Extending the format

A new step, field or check is added in `UiSteps` (parsing and validation, with a message that names the
problem), `UiSession` or one of the classes it dispatches to (`UiExpect`, `UiConfig`, `UiIdeSteps`), the step
list of the `steroid_ui` tool description, this recipe, and a line in the format fixture.

# See also

- [Find and drive UI controls with steroid_ui and ui helpers](mcp-steroid://ide/ui-driving)
- [Discover IDE actions at caret](mcp-steroid://ide/action-discovery)
- [Split Mode: what runs on the client and what runs on the backend](mcp-steroid://skill/split-mode)
