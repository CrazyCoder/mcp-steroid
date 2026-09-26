# IDE actions at a code location, and `steroid_refactor`

## Goal

Let an agent run IDE actions and refactorings without writing and compiling a
Kotlin script, and without the threading mistakes that scripts make. Measured
on 2026-09-26 while refactoring this repository through the IDE:

- `idea` `rename_refactoring` took one call and no code.
- Safe Delete and an inspection quick fix each needed a 30 to 60 line script,
  about 5 to 10 s of compile before any work. Two of those scripts failed to
  compile.
- One recipe applied a quick fix inside a background write action. The write
  permit leaked and the IDE froze until it was killed.
- `steroid_ui` could not run an IDE action by id: opening Go to Line needed a
  script.

Two parts, built in this order:

- **A.** Two `steroid_ui` steps, `goto` and `run`, that put the caret or a
  selection in the editor and run any IDE action there. The existing steps then
  drive whatever dialog or in-place template the action opens. This covers
  every refactoring that has a dialog, in any language.
- **B.** A `steroid_refactor` tool for the refactorings the platform can run
  without a dialog: rename, safe delete, move, quick fixes, intentions, optimize
  imports, reformat, and a read-only usages query.

## A. `goto` and `run` steps

### `goto`

Opens a file in the editor, focuses the editor, and places the caret or a
selection.

| Field | Meaning |
| -- | -- |
| `file` | Absolute path, or relative to the project base directory. Required |
| `line`, `column` | 1-based caret position |
| `symbol` | Puts the caret at the start of the first whole-word occurrence of this identifier. `nth` (0-based) picks a later one |
| `text` | Selects the first occurrence of this exact snippet. `nth` picks a later one |

Exactly one of `line`, `symbol` or `text` is given. `column` defaults to 1.
`symbol` and `text` search the document text, so they work in any language and
need no index.

Report: `caret at <file>:<line>:<column>` or
`selected "<snippet, clipped>" at <file>:<line>:<column>`, and the name of the
PSI element at the caret when it has one.

Failures: file not found; line out of range; `symbol` or `text` not found, or
fewer than `nth + 1` occurrences, with the count found.

### `run`

Runs an IDE action by its id, in the data context of the component that has the
focus, which after `goto` is the editor with its caret or selection.

| Field | Meaning |
| -- | -- |
| `id` | Action id, such as `RenameElement`, `ChangeSignature`, `ExtractMethod`, `GotoLine`, `ShowSettings` |

The step reports one of:

- `ran <id> ("<presentation text>")`, followed by the effects the engine already
  reports: windows opened or closed, the new focus.
- `<id> is disabled here` when the action's update leaves it disabled or
  invisible in this context.
- `unknown action id <id>`, with up to five registered ids that contain the
  given one, ignoring case.
- `started an in-place template: type the value, then press ENTER` when the
  action starts a template in the editor, as in-place Rename does.

An action whose presentation text ends with an ellipsis opens a window, so the
step waits for the window as a click on such a button does, and stops early
when an in-place template starts.

### Split mode

Both steps run on the side the tool's `side` parameter names, the frontend by
default, where the editor and the actions live.

## B. `steroid_refactor`

### Parameters

| Parameter | Meaning |
| -- | -- |
| `project_name`, `task_id`, `reason` | As in the other tools |
| `op` | `rename`, `safe_delete`, `move`, `fix`, `intention`, `optimize_imports`, `reformat`, `usages` |
| `file`, `line`, `column`, `symbol`, `nth` | The target, resolved as in `goto`, then to the named PSI element at the caret or the reference's target |
| `fqn` | Alternative target: a class, or `Class.member` / `Class#member` |
| `new_name` | `rename` |
| `to` | `move`: a target directory, or for a Java class a package name |
| `inspection`, `all` | `fix`: the inspection short name; `all` applies the fix to every problem in the file |
| `name` | `intention`: the intention's text, matched exactly, then as a prefix |
| `apply` | `false` by default: a dry run that changes nothing |

### Operations

| op | Implementation | Dry run returns |
| -- | -- | -- |
| `rename` | `RenameProcessor`, previews off | Element, usages |
| `safe_delete` | `SafeDeleteProcessor` | Element, usages that block the delete |
| `move` | `MoveFilesOrDirectoriesProcessor`; a Java class to a package through `MoveClassesOrPackagesProcessor` | Element, usages |
| `fix` | Inspection by short name from the current profile; fix applied on the EDT in one command, in a write action only when `startInWriteAction()` | Problems with their fixes |
| `intention` | `IntentionManager` actions available at the caret, applied the way the editor applies them | Intentions available at the target |
| `optimize_imports` | `OptimizeImportsProcessor` on the file | Nothing to preview; the dry run says so |
| `reformat` | `ReformatCodeProcessor` on the file | Same |
| `usages` | `ReferencesSearch` in the project scope | Usages, always read-only |

Usages print as `path:line: <line text>`, the first 30, then a count. Paths are
relative to the project base directory.

`apply` returns the changed files with the lines added and removed in each, and
the undo command's name.

### Threading and dialogs

- The target resolves in a smart read action.
- A processor runs on the EDT under write intent, with usage previews off. No
  write action wraps it.
- A conflicts or other dialog that opens during `apply` is read through the UI
  engine, cancelled, and returned as the op's result: `conflicts: <messages>`.
  Nothing is changed in that case.
- Each op has a 60 s limit.

### Left to part A

Change Signature, Extract Method, Extract Variable, Introduce Parameter,
Kotlin Inline, and a Kotlin declaration move. They need a dialog or the editor,
or their API differs between releases. `goto` plus `run` covers them.

## Prompts

- `ide/ui-driving.md`: the `goto` and `run` steps, with a Change Signature
  example.
- `ide/safe-delete.md`, `ide/inspect-and-fix.md`, `lsp/rename.md`, and the
  refactoring section of the tool description lead with `steroid_refactor` and
  keep the script as the fallback for what the tool does not cover.

## Testing

- Unit: step parsing for `goto` and `run` (fields, exactly one locator, errors);
  op parsing for `steroid_refactor`; target resolution by symbol, text, line and
  `nth` against an in-memory document; the golden tool schemas.
- Live, in IntelliJ IDEA 2026.2.3 on this repository's Kotlin sources:
  - `goto` a symbol, `run` `RenameElement`, type the new name, press ENTER, and
    undo.
  - `goto` a function, `run` `ChangeSignature`, read the dialog, cancel.
  - `run` `GotoLine` with no `goto`.
  - `steroid_refactor` `rename`, `safe_delete` and `fix`, each as a dry run and
    applied, then undone.
  - A conflict: `rename` to a name that already exists in the scope.

The design can change where live tests show a better variant; the plan and this
spec are updated when it does.
