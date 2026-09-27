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
| `title`: a window | `is`: visible (default) or hidden | `{"action":"expect","title":"Rename","is":"hidden"}` |
| `file`: a project file's text | `value`, `contains` or `matches`, over `line` N when given | `{"action":"expect","file":"src/A.kt","line":3,"contains":"newName"}` |
| `file` with `caret` | The caret in the file's editor, as `line:column`, 1-based | `{"action":"expect","file":"src/A.kt","caret":"3:14"}` |
| `notification` | A notification shown since the call started, or listed in the Notifications tool window, whose title or text contains this | `{"action":"expect","notification":"Indexing"}` |
| `error` | An IDE error logged since the call started whose summary contains this; `""` matches any | `{"action":"expect","error":"","not":true}` |

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
| `advanced` | `{"action":"set","advanced":"editor.tab.painting","value":"UNDERLINE"}` | An advanced setting by id; an enum takes its constant's name |
| `inspection` | `{"action":"set","inspection":"UnusedDeclaration","value":"off"}` | An inspection of the project's current profile by short name: `on`, `off`, or a severity such as `ERROR`, `WARNING`, `WEAK WARNING`, `INFORMATION`. Highlighting restarts |
| `component` + `field` | `{"action":"set","component":"EditorSettings","field":"IS_WHITESPACES_SHOWN","value":"true"}` | A field of a persistent settings component, by the state name it is saved under. `get` with `component` alone shows its saved XML, which lists the fields that differ from their defaults. Only components already loaded are found, and a field that holds structured XML needs a `code` step |

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

In Split Mode a `steroid_ui` call runs on one side, the JetBrains Client by default. Steps that drive
windows belong there. Settings, files, inspections and `code` steps act on the backend, where the project
lives: run them in a separate call with `side` set to `backend`, or split the scenario at that point with
`from_step` and `to_step`. See [Split Mode](mcp-steroid://skill/split-mode).

## Extending the format

The format is meant to grow. A new step, field or check is added in `UiSteps` (parsing and validation, with
a message that names the problem), `UiSession` or one of the classes it dispatches to (`UiExpect`, `UiConfig`,
`UiIdeSteps`), the step list of the `steroid_ui` tool description, and this recipe. Because unknown fields
fail, an IDE with an older plugin refuses a scenario that uses a newer step instead of skipping it. Raise
`scenario` only for a change an older reader would misread; a new step is not one.

# See also

- [Find and drive UI controls with steroid_ui and ui helpers](mcp-steroid://ide/ui-driving)
- [Discover IDE actions at caret](mcp-steroid://ide/action-discovery)
- [Split Mode: what runs on the client and what runs on the backend](mcp-steroid://skill/split-mode)
