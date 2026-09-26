IDE: Find and drive UI controls with steroid_ui and ui helpers

Read the IDE's windows as a compact list of controls with refs, then click, type, select and close by ref or name with steroid_ui, or with ui helpers inside a script.

# When to use this recipe

Use it when a task needs the IDE's own UI: a dialog with no API, a tool window row, a settings page, a
popup. Prefer an API when one exists. An action ID (`ActionManager.getInstance().getAction(id)`), a service
or a `DialogWrapper` method is shorter and never breaks on a layout change. See
[action discovery](mcp-steroid://ide/action-discovery) for finding action IDs.

Start with the `steroid_ui` tool. It compiles no code, so a snapshot or a step answers in well under a
second, where a `steroid_execute_code` call spends seconds compiling. Use the `ui` helpers of
`steroid_execute_code` when the steps need logic between them, and the XPath model below for queries the
helpers do not cover.

## Snapshot and act with steroid_ui

`steroid_ui` without steps lists the project's showing windows, topmost first, including separate
windows such as Settings. Each line is one control: class, accessible name, `label="..."` for the caption
before an unnamed field, `[ref=e12]`, states such as `[disabled]` or `[checked]`, `value="..."` for text
fields and combo boxes, `text=...` for the text it paints (tabs, editor text), `tip="..."`, and
`action=<id>` for the IDE action behind a toolbar button or menu item, which a `run` step takes. A list,
tree or table lists its rows in view under it, one per line, as `#index text`, indented by tree depth and
marked `[expanded]`, `[collapsed]` or `[selected]`.

Steps act by ref or by what a control shows, and each one reports what it caused: where the press landed,
whether a button's action ran, the IDE actions, windows opened or closed. A click that opens a modal dialog
returns while the dialog is up. The response then shows what changed: a closed window as one line, an
opened window whole, and the changed lines of the rest. The first failing step stops the run, shows the
topmost window and names the nearest controls.

`select` sets a row through the list's selection, as the keyboard does, without clicking it: in Find
Action or Search Everywhere a click would run the row. Press `ENTER` afterwards to act on the row. A row
is found by its text, else by part of it, and a tree row also by its path such as
`Editor > General > Appearance`; when several rows match, the step fails and lists them by index. `press`
and `type` without a target go to the control that has the focus in the topmost window, even while the IDE
is not the active application.

For example, these steps open Settings, change two options on the Appearance page and cancel:

- `{"action":"press","keys":"ctrl+alt+S"}`
- `{"action":"select","name":"Settings categories","row":"Appearance & Behavior"}`
- `{"action":"click","name":"Appearance","class":"ActionLink"}`
- `{"action":"select","name":"Zoom:","nth":0,"row":"110%"}`
- `{"action":"check","name":"Compact mode"}`
- `{"action":"click","name":"Cancel"}`

Pass them as one JSON array in `steps`.

Several controls often share a name, because a label carries its field's name. Matching prefers the
interactive control in the topmost window that has one, and when two remain, add `nth`, a `class` or the
ref. While a modal dialog shows, only that dialog and its popups are searched. Add `"trace": true` to record a picture before and after
each step for a reproduction.

## See the UI with refs

When the text is not enough, because layout, icons or colours matter or a control has no name, take
`steroid_take_screenshot` with `marks=true`. Every interactive control on the image is outlined and
labelled with its ref, the same ref a snapshot shows. Read the ref off the picture and act by it:
`{"action":"click","ref":"e12"}`, `fill`, `select`, `check` or `inspect`. Do not click at pixel
coordinates with `steroid_input` for a control that has a ref: a ref needs no HiDPI scale arithmetic,
still finds the control after a resize or scroll, and the step reports what it caused. Keep coordinates
for what has no ref: a web view (JCEF), a canvas, a drag.

## Find the code and plugin behind a control

An `inspect` step tells where a control comes from, as the IDE's UI Inspector (Ctrl+Alt+Click in internal
mode) finds it, in two lines: its class and plugin, the action behind it with its class and plugin, the
tool window it sits in or opens with its factory, its `DialogWrapper` class, model, renderer and empty
text, and `created:` with the first frames of the code that built it. For a list, tree or table it adds the
row that `row` or `index` names, else the selected one: the row's value and user object classes, the
action behind a popup or Search Everywhere item, the intention or quick fix behind an Alt+Enter item, and
a Settings tree row's `Configurable class` and `Configurable ID`.

- `{"action":"inspect","name":"Run external linter on the fly"}` on a Settings page names the
  configurable that built it under `created:`, such as `RsExternalLinterConfigurable.createPanelInner`
- `{"action":"inspect","name":"Settings categories"}` gives the selected page's configurable class and ID
- `{"action":"inspect","class":"JList","index":1}` in the Alt+Enter popup names the intention class

The first `inspect` starts recording where controls are created, which the IDE keeps doing until it
restarts. A control that was showing before has `created: not recorded`: close and reopen its window,
then inspect it again.

## Run an IDE action at a code location

Two ways to refactor, for two jobs:

- **Change code**: the `steroid_refactor` tool (rename, safe delete, move, quick fix, intention, imports,
  formatting, usages). It opens no dialog or tab, moves no caret and saves only the files it changed, so a
  person working in the same IDE is not interrupted.
- **Do what a user does**: the steps below. They open the file, move the caret and show the dialog, the
  preview or the in-place template, which is what a reproduction of a user's report needs, and they
  reach refactorings the tool does not cover.

A `goto` step opens a file in the editor and puts the caret on a symbol, a line and column, or selects an
exact snippet. A `run` step then runs any IDE action by id there, and the next steps drive the dialog,
popup or in-place template it opens. This covers every refactoring with a dialog, in any language, with
no script. Change Signature on a Kotlin function:

- `{"action":"goto","file":"src/main/kotlin/Util.kt","symbol":"parse"}`
- `{"action":"run","id":"ChangeSignature"}`: opens the "Change Signature" dialog; the response lists its
  fields, parameter table and buttons
- `{"action":"fill","name":"Name:","text":"parseAll"}`, then `{"action":"click","name":"Refactor"}`

`symbol` matches whole words; add `"nth":1` for the second occurrence. `"text":"a + b"` selects the
snippet, which the extract refactorings need. Action ids differ by language: Kotlin extracts with
`ExtractFunction`, Java with `ExtractMethod`. A `run` step reports an action that is disabled at the caret,
an unknown id with similar ids, and an in-place template (Kotlin Introduce Variable, in-place Rename):
type the value, then press ENTER, or press ESCAPE to keep the suggested one. Other useful ids:
`RenameElement`, `SafeDelete`, `Inline`, `Move`, `IntroduceVariable`, `IntroduceParameter`, `GotoLine`,
`ShowSettings`, `$Undo`.

## Drive UI from a script with ui helpers

The `ui` helpers of the script context run the same engine, with the same refs. Each action returns the
line `steroid_ui` would report and throws when it cannot do what it asks; `ui.select` takes a row's text or
its index. `ui.open { }` runs code that shows a dialog in its own EDT task and returns the dialog, so the
script keeps running while it is up:

```kotlin
import com.intellij.openapi.options.ShowSettingsUtil

val settings = ui.open { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Editor") }
println("opened: " + ((settings as? java.awt.Dialog)?.title ?: (settings as? java.awt.Frame)?.title))
println(ui.select(ui.name("Settings categories"), "Appearance & Behavior"))
println(ui.click(ui.name("Appearance") and ui.cls("ActionLink")))
println(ui.check(ui.name("Compact mode")))
println(ui.click(ui.name("Cancel")))
```

To work in a dialog that is already open with IntelliJ APIs, run the script with `modal=dialog`: its
`withContext(Dispatchers.EDT)` blocks and its `writeAction { }` run under the dialog's modality while the
dialog stays open.

The helpers and the tool use the UI model of the **Performance Testing** plugin
(`com.jetbrains.performancePlugin`), which JetBrains IDEs bundle for their own UI tests, and fall back to
Swing names and labels when it is disabled.

## Drive a dialog from a script

This example opens **Go to Line**, enters a line number, presses OK and checks the caret. `ui.open { }`
returns while the modal dialog is up, and while it shows, the helpers search only that dialog. The dialog
has two text fields, so the fill names its field by the caption beside it, which a snapshot shows as
`label="..."`:

```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager

val editor = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).selectedTextEditor }
    ?: error("Open a file in the editor first")
val dialog = ui.open {
    val action = ActionManager.getInstance().getAction("GotoLine")
    ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, null, true)
}
println("opened: " + (dialog as? java.awt.Dialog)?.title)
println(ui.fill(ui.text("Line") and ui.cls("JTextComponent"), "3"))
println(ui.click(ui.name("OK")))
println("Caret line: " + withContext(Dispatchers.EDT) { editor.caretModel.logicalPosition.line + 1 })
```

A step that runs an action or presses a button whose name ends with an ellipsis, such as **Settings…**,
waits for its window, because such a window can take over a second to prepare on the first open after the
IDE starts. A window that opens later still is reported by the next step, as `meanwhile opened ...`. To wait for a particular window, use `ui.waitForWindow(title)` or the `wait` step
with `"for":"window"`.

## Find controls with XPath

The model is a DOM of the showing components, one `<div>` per component, with these attributes:

| Attribute | Content |
|---|---|
| `class` | Simple class name: `ActionButton`, `JButton`, `SeTextField` |
| `javaclass` | Fully qualified class name |
| `classhierarchy` | Superclasses up to `JComponent`, joined with ` -> `, without the class itself: `contains(@classhierarchy,'javax.swing.JTree') or @javaclass='javax.swing.JTree'` |
| `accessiblename` | Accessible name: button labels, tool window names, tree descriptions |
| `tooltiptext` | Tooltip, often a full file path or the action name |
| `visible_text` | Text the component paints: tree and list rows, tabs, editor text, separate strings joined with a double pipe |

An `"xpath"` target in `steroid_ui`, or `ui.xpath(...)` in a script, selects over this model, for a query
that a name, text or class cannot express:

```kotlin
val button = ui.find(ui.xpath("//div[@class='ActionButton' and @accessiblename='Search Everywhere']"))
println("Search Everywhere button: ${button.javaClass.name}")
```

To read the model yourself, create `XpathDataModelCreator`, remove its `RemoteDriverDataModelExtension`
processor and the ones whose `isRemDevExtension` is true, and call `create(root)`. Those processors call
the JMX test driver, which a normal IDE does not run, so the build fails with "Invoker is not registered"
while they stay. Painting every component to read its text is slow: a whole Settings window takes
seconds, a small dialog milliseconds.

## Read tree, list and table rows

A `steroid_ui` snapshot lists the rows in view of each list, tree and table, and `select` takes any row
by its text, its tree path or its index. Read the rows from a script when you need all of them. The
fixtures of the same plugin read them through the cell renderers, so they return the text a row shows.
Give them a read-only AssertJ robot: their click methods drive `java.awt.Robot`, which moves the user's
real mouse.

```kotlin
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.EDT
import com.jetbrains.performancePlugin.remotedriver.fixtures.JTreeTextFixture
import org.assertj.swing.core.BasicRobot

val tree = withContext(Dispatchers.EDT) { ProjectView.getInstance(project).currentProjectViewPane?.tree }
    ?: error("The Project tool window has no tree")
val rows = JTreeTextFixture(BasicRobot.robotWithCurrentAwtHierarchyWithoutScreenLock(), tree)
println(rows.collectExpandedPaths().take(20).joinToString("\n") { "${it.row}: ${it.path.joinToString(" / ")}" })
println("Selected: " + rows.collectSelectedPaths().map { it.path })
```

`JListTextFixture(robot, list).contents()` and `JTableTextFixture(robot, table).contents()` return list and
table rows the same way.

## Close what you opened

`ui.close()`, or the `close` step, closes the topmost dialog, popup or separate window such as Settings:
it cancels a dialog or a popup, and closes a window as its close button does. Pass a target to close the
window that holds it. Each call closes one window, so call it once per window you opened.

## Pitfalls

- Deliver input through `steroid_ui` or the `ui` helpers. The plugin's own robots either move the real
  mouse (`SmoothRobot`) or exist only in 2026.3 and later (`IdeRobot`).
- Match on accessible names, painted text or classes, not on the position of a child. Layouts change
  between versions; names change less often.
- A hidden tool window is not in the model. Click its stripe button, such as
  `{"action":"click","name":"Project","class":"SquareStripeButton"}`, or call
  `ToolWindowManager.getInstance(project).getToolWindow(id)?.show()` first.
- With `modal=smart_non_modal`, a dialog your script opens fails the call unless the script calls
  `allowModalDialog()` first. Open dialogs with `ui.open { }`, and run scripts that work inside an already
  open dialog with `modal=dialog`.
- In Split Mode, run the script on the side that owns the window's components. A dialog or Settings page
  that the backend owns is only a picture in the JetBrains Client. See
  [Split Mode](mcp-steroid://skill/split-mode).

# See also

- [Split Mode: what runs on the client and what runs on the backend](mcp-steroid://skill/split-mode)
- [Discover IDE actions at caret](mcp-steroid://ide/action-discovery)
- [Open Project (With Dialog Handling)](mcp-steroid://open-project/open-with-dialogs)
- [Execute code tool description](mcp-steroid://skill/execute-code-tool-description)
