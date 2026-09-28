IDE: Drive editor actions at a code fragment as a person does, with steroid_ui

Put the caret or a selection on code, list what applies there, run a context action, a refactoring or a generator through its popup and dialog, and read the code it changed, in any language.

# When to use this recipe

Use it when the task is what a person does in the editor: Alt+Enter on a symbol, the right-click menu, Refactor
This, Generate, Extract on a selection, then the dialog, preview or in-place template that follows. It reproduces a
user's report step by step, and it reaches every refactoring and generator the IDE has, with no script.

To change code without opening a dialog or moving the caret, `steroid_refactor` is shorter: rename, safe delete,
move, quick fix, intention, imports and formatting. Read [Drive UI controls](mcp-steroid://ide/ui-driving) first for
targets, refs and snapshots.

## Put the caret or a selection on the code

A `goto` step opens the file and puts the caret on a whole-word `symbol` (add `"nth":1` for the second one), on a
`line` and `column`, or selects the exact `text`, which the Extract refactorings need:

- `{"action":"goto","file":"src/main/java/demo/App.java","symbol":"main"}`
- `{"action":"goto","file":"src/main/java/demo/App.java","text":"app.count * 2 + 1"}`

The steps after it act where the caret is.

## List what applies here

Each of these lists the items for the code at the caret. Pick the cheapest one that answers the question:

| Step | What it lists |
|------|---------------|
| `{"action":"menu","path":"Refactor"}` | Every refactoring with its action id, shortcut and `[disabled]` where it does not apply, in one report and no popup |
| `{"action":"run","id":"Refactorings.QuickListPopupAction"}` | Refactor This: only the refactorings that apply, each row with `action=<id>` |
| `{"action":"run","id":"ShowIntentionActions"}` | The context actions and quick fixes of Alt+Enter, after the editor's analysis finishes |
| `{"action":"run","id":"Generate"}` | Generate: constructors, getters and setters, overrides, and the language's other generators |
| `{"action":"run","id":"ShowPopupMenu"}` | The editor's context menu, as a right-click shows it, each item with its action id |

`{"action":"menu","path":"Code"}` lists the Code menu the same way. A row or item with an action id runs directly
with `{"action":"run","id":"<id>"}`, the same as picking it.

In a popup list, `select` a row and press `ENTER` to run it. In the Alt+Enter popup, `RIGHT` opens a row's options
(edit or disable the intention) instead. In the context menu, a click on a submenu such as Refactor waits for its
items and says how many it shows. `{"action":"close"}` or `ESCAPE` closes a popup or menu.

## Run it and drive what it opens

A `run` step reports the dialog, popup or in-place template the action opened; the next steps drive it:

- A dialog field is filled by its label: `{"action":"fill","name":"Name:","text":"computeTotal"}`. A table row
  editor, such as a new parameter in Change Signature, lists its fields under the table; fill them by ref.
- A click on the dialog's default button, such as Refactor or OK, waits for the dialog to close, which it does once
  the refactoring ran. A dialog that stays open shows why: a table cell still being edited, or a problem the dialog
  reports.
- An in-place template (in-place rename, Kotlin Introduce Variable): type the value, then press `ENTER`, or press
  `ESCAPE` to keep the suggested one.
- A refactoring that also finds text occurrences opens the Refactoring Preview tool window; click its Refactor
  button to apply.

Common action ids, the same in every language that supports the refactoring:

| Id | Does |
|----|------|
| `RenameElement`, `ChangeSignature`, `Move`, `CopyElement`, `SafeDelete`, `Inline` | Rename, change signature, move, copy, safe delete, inline |
| `IntroduceVariable`, `IntroduceConstant`, `IntroduceField`, `IntroduceParameter` | Introduce a variable, constant, field or parameter from the selection |
| `ExtractMethod` (Java), `ExtractFunction` (Kotlin) | Extract the selection into a method or function |
| `OverrideMethods`, `ImplementMethods`, `SurroundWith`, `CommentByLineComment` | Code generation and editing |
| `ReformatCode`, `OptimizeImports`, `EditorSelectWord` | Formatting, imports, and Extend Selection |
| `GotoDeclaration`, `FindUsages`, `ShowUsages`, `QuickJavaDoc`, `ParameterInfo` | Navigation and information |
| `$Undo`, `$Redo` | Undo, redo |

An unknown id fails with similar ids; a disabled one says it does not apply at the caret.

## Read what the steps changed

Each step that changed project files ends with what it changed, such as
`changed src/main/java/demo/App.java (+5 -1)`, and the response ends with the diff of the whole call:

```
code changes:
changed src/main/java/demo/App.java (+5 -1)
  @@ 14
  -        int total = app.count * 2 + 1;
  +        int total = computeTotal(app);
```

`{"action":"get","changes":true}` gives the whole diff when the summary is cut. The diff covers the edits the IDE
makes: refactorings, generators, typing, and files created, deleted or moved. It leaves out build output, `.idea`,
and changes made outside the IDE. In Split Mode the backend tracks them; a JetBrains Client alone reports none.

To take a change back, run `$Undo`: the report names the command it undoes, such as `"Undo Typing"`. With the caret
away from the change, the first Undo only brings the caret back to it, and the report says so; run it again to undo.

## Check the result in a scenario

A scenario that runs a refactoring checks its result as a test does, and a replay puts the code back afterwards:

- `{"action":"expect","changed":["src/main/java/demo/App.java"]}`: exactly these files changed; `[]` for none.
- `{"action":"expect","file":"src/main/java/demo/App.java","diff":"-int total = app.count * 2 + 1;\n+int total = computeTotal(app);"}`:
  these diff lines appear together, compared without indentation; a line starting with a space is an unchanged one.
- `{"action":"expect","file":"src/main/java/demo/App.java","golden":"expected/App.java"}`: the whole file equals a
  golden file; a mismatch shows the diff between them.

A replay of the whole scenario writes back each file it changed and deletes the files it created. Local History also
gets a label before the steps, `steroid_ui: before "<title>"`, to revert to by hand. See
[IDE scenarios](mcp-steroid://ide/ui-scenarios).

## Pitfalls

- Run an action by its id rather than pressing its shortcut. Keymaps differ: Shift+F10 opens the context menu in most
  Windows applications, but runs the program in the IDE's default keymap.
- The lists depend on the caret: a refactoring that is disabled on a method call may apply on its declaration.
- The Alt+Enter popup lists the context actions the analysis found; a popup opened by a key press while the analysis
  runs lists fewer. `run ShowIntentionActions` waits for it.

# See also

- [Find and drive UI controls with steroid_ui and ui helpers](mcp-steroid://ide/ui-driving)
- [Record, check and replay IDE scenarios with steroid_ui](mcp-steroid://ide/ui-scenarios)
- [Discover IDE actions at caret](mcp-steroid://ide/action-discovery)
