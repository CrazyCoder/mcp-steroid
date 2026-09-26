# IDE Actions at a Code Location and steroid_refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run IDE actions at a code location from `steroid_ui`, and common
refactorings from a new `steroid_refactor` tool, without compiling a script.

**Architecture:** Part A adds `goto` and `run` steps to the existing
`UiSteps` parser (server module) and `UiSession` engine (plugin). A pure
`CodeLocation` resolver turns `line`/`symbol`/`text` into a document range.
Part B adds `RefactorToolSpec` beside `UiToolSpec`, a `RefactorToolHandlerIJ`
service, and a `refactor/` package whose ops reuse `CodeLocation` and the UI
engine's window watching for conflicts dialogs.

**Tech Stack:** Kotlin, IntelliJ Platform 261+, kotlinx.serialization, JUnit
(BasePlatformTestCase in the plugin, plain JUnit in the server module).

**Spec:** `docs/superpowers/specs/2026-09-26-steroid-refactor-design.md`

## Global Constraints

- Plugin `since-build` 261: only APIs present in 2026.1 through 2026.3.
- Never run an action's update synchronously on the EDT with a plain
  `DataManager` context: refactoring actions resolve PSI in update and trip
  SlowOperations. Use `ActionManager.tryToExecute`.
- Never wrap a refactoring processor in a write action; run it on the EDT
  under write intent. Quick fixes: EDT, one command, write action only when
  `startInWriteAction()`.
- Commit to `main`, never push. Conventional commits, no AI attribution.
- Verify live in IntelliJ IDEA 2026.2.3 (port 6320) after each part; do not
  run the prompt test suites. Server and plugin unit tests with `--tests`
  filters are fine.
- Restore any IDE and file state a live test changes (undo, close dialogs).

## Review Focus

- A `symbol` that also occurs inside a longer identifier (`UiState` vs
  `UiStateX`): whole-word match only — CodeLocationTest pins it.
- A file with CRLF line endings: `line`/`column` and snippets resolve against
  the Document (always `\n`) — CodeLocationTest works on normalized text, and
  the engine reads `Document.text`.
- `run` with no focused editor and a context-free action (`GotoLine` needs an
  editor, `ShowSettings` does not): the step reports the rejection instead of
  hanging — live test.
- `steroid_refactor` `rename` to a name that already exists: conflicts come
  back as text and nothing changes — live test.
- A dry run must never change a file — live test checks `git status` after
  every dry run.

---

### Task 1: Parse `goto` and `run` steps

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiSteps.kt`
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/UiStepsTest.kt`

**Interfaces:**
- Produces: `UiAction.GOTO("goto")`, `UiAction.RUN("run")`; `UiStep` gains
  `file: String?`, `line: Int?`, `column: Int?`, `symbol: String?`,
  `id: String?`. For `goto`, `text` holds the snippet to select (as for
  type/fill it is not a target).

- [ ] Step 1: tests — `goto` with `file`+`symbol` parses; `goto` with two
  locators fails "exactly one of line, symbol or text"; `goto` without `file`
  fails; `run` without `id` fails; `goto` `text` is not a target.
- [ ] Step 2: run `./gradlew :mcp-steroid-server:test --tests '*UiStepsTest*'`
  — expect the new tests to fail.
- [ ] Step 3: implement: add the fields to `FIELDS`, the enum entries, parse
  `file`, `line`, `column`, `symbol`, `id`; treat `text` as non-target for
  GOTO like TYPE/FILL; validation:

```kotlin
UiAction.GOTO -> {
    require(!step.file.isNullOrBlank()) { "goto needs a file" }
    val locators = listOfNotNull(step.line, step.symbol, step.text).size
    require(locators == 1) { "goto needs exactly one of line, symbol or text" }
    step.line?.let { require(it >= 1) { "line is 1-based" } }
    step.column?.let { require(it >= 1) { "column is 1-based" } }
}
UiAction.RUN -> require(!step.id.isNullOrBlank()) { "run needs an action id, such as \"RenameElement\"" }
```

- [ ] Step 4: tests pass.
- [ ] Step 5: commit `feat(ui): parse goto and run steps`.

### Task 2: Resolve a code location

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/CodeLocation.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/CodeLocationTest.kt`

**Interfaces:**
- Produces: `object CodeLocation { fun resolve(text: String, line: Int?, column: Int?, symbol: String?, snippet: String?, nth: Int): IntRange }`
  — a caret is an empty range (`offset until offset`); throws
  `UiStepFailure` with the reason. Pure, no IDE.

- [ ] Step 1: tests — line/column to offset; column past line end clamps to
  line end; line out of range fails naming the line count; symbol whole-word
  (`UiState` not inside `UiStateX`), `nth` 1 picks the second; snippet
  selection range; snippet missing fails with "found 0"; `nth` beyond the
  count fails naming the count.
- [ ] Step 2: run `./gradlew :ij-plugin:test --tests '*CodeLocationTest*'`,
  expect failures.
- [ ] Step 3: implement with `Regex("(?<![\\w$])" + Regex.escape(symbol) + "(?![\\w$])")`
  for symbols and `indexOf` loops for snippets.
- [ ] Step 4: tests pass.
- [ ] Step 5: commit with Task 3.

### Task 3: `goto` and `run` in the engine

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSession.kt`
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiTool.kt` (description)

**Interfaces:**
- Consumes: `CodeLocation.resolve`, `UiStep.file/line/column/symbol/id`.

- [ ] Step 1: `gotoStep`: resolve the file (absolute, else under
  `project.basePath`) with `LocalFileSystem.refreshAndFindFileByPath`; on the
  EDT read `FileDocumentManager.getDocument(vf).text`, `CodeLocation.resolve`,
  `FileEditorManager.openTextEditor(OpenFileDescriptor(project, vf, start), true)`,
  set the selection when the range is not empty, `IdeFocusManager.requestFocus(editor.contentComponent, true)`.
  Report `caret at path:line:column` or `selected "…" at …`.
- [ ] Step 2: `runActionStep`: `ActionManager.getAction(id)`; unknown → up to
  five `getActionIdList("")` entries containing the id, ignoring case. The
  component is `keyRecipient()`. On the EDT call
  `ActionManager.tryToExecute(action, null, component, "steroid_ui", true)`
  and await the callback (bounded by the step timeout): rejected →
  `"<id> is disabled here"`; done → `"ran <id> (\"<text>\")"`. Wire it through
  `withEffects` so window opening, the ellipsis wait and the in-place
  template stop apply; when `inplaceActive()` is true afterwards, append
  `"started an in-place template: type the value, then press ENTER"`.
- [ ] Step 3: `runStep` dispatch: GOTO and RUN go through `withEffects`.
- [ ] Step 4: tool description: two bullets for `goto` and `run` and one
  example (`goto` symbol, `run` RenameElement, `type`, `press` ENTER).
- [ ] Step 5: `./gradlew :mcp-steroid-server:test --tests '*UiTool*' --tests '*UiSteps*' :ij-plugin:test --tests '*CodeLocationTest*'`.
- [ ] Step 6: build, install into 2026.2.3, live tests from the spec (rename
  in place and undo, Change Signature dialog then cancel, GotoLine alone, a
  disabled action, an unknown id).
- [ ] Step 7: commit `feat(ui): goto a code location and run an IDE action there`.

### Task 4: Prompts for part A

**Files:**
- Modify: `prompts/src/main/prompts/ide/ui-driving.md`

- [ ] Step 1: a "Run an IDE action at a code location" section with the
  verified Change Signature example.
- [ ] Step 2: commit `docs(prompts): run IDE actions at a code location`.

### Task 5: `steroid_refactor` tool spec

**Files:**
- Create: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/RefactorTool.kt`
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/McpSteroidTools.kt`
- Create: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigRefactorToolHandler.kt`
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/StubMcpSteroidTools.kt`
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/RefactorToolSpecSchemaTest.kt`, the devrig golden schema

**Interfaces:**
- Produces: `enum class RefactorOp(val wire: String)` with RENAME, SAFE_DELETE,
  MOVE, FIX, INTENTION, OPTIMIZE_IMPORTS, REFORMAT, USAGES;
  `data class RefactorParams(taskId, reason, op: RefactorOp, file, line, column, symbol, nth, fqn, newName, to, inspection, all: Boolean, name, apply: Boolean)`;
  `interface RefactorToolHandler { suspend fun handleRefactor(projectName: String, params: RefactorParams): ToolCallResult }`.

- [ ] Step 1: schema test in the style of `UiToolSpecSchemaTest`.
- [ ] Step 2: implement the spec (params registered like `UiToolSpec`), register
  in `commonToolSpecs()`, devrig forwarding handler.
- [ ] Step 3: update the devrig golden schema; run
  `./gradlew :mcp-steroid-server:test`.
- [ ] Step 4: commit with Task 6.

### Task 6: Refactor engine

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/refactor/RefactorTarget.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/refactor/RefactorOps.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/RefactorToolHandler.kt` (`RefactorToolHandlerIJ`)
- Modify: `ij-plugin/src/main/resources/META-INF/plugin.xml`

- [ ] Step 1: `RefactorTarget.resolve(project, params)`: file + `CodeLocation`
  → element at offset → nearest `PsiNamedElement` or reference target; `fqn`
  → `JavaPsiFacade.findClass`, then a member by name. Smart read action.
- [ ] Step 2: ops. Usages via `ReferencesSearch` (first 30, `path:line: text`).
  `rename`: `RenameProcessor(project, element, newName, false, false)` with
  `setPreviewUsages(false)`. `safe_delete`: `SafeDeleteProcessor.createInstance`.
  `move`: `MoveFilesOrDirectoriesProcessor` for files/dirs, Java class to
  package via `MoveClassesOrPackagesProcessor`. `fix`: the live-verified form.
  `intention`: `IntentionManager.getInstance().availableIntentions` filtered by
  `isAvailable(project, editor, file)` on an editor opened at the target,
  applied with `ShowIntentionActionsHandler.chooseActionAndInvoke`.
  `optimize_imports` / `reformat`: `OptimizeImportsProcessor` /
  `ReformatCodeProcessor` `.run()`.
- [ ] Step 3: apply runs on the EDT under write intent; watch for a dialog with
  `UiSettle.awaitWindowChange`; if one opens, read its text through `UiModel`,
  close it with `DialogWrapper.close(CANCEL_EXIT_CODE)`, return
  `conflicts: …`. Result lists changed files with `+a -r` from a document
  snapshot before and after.
- [ ] Step 4: `./gradlew :ij-plugin:test --tests '*CodeLocationTest*'`, build,
  install, live tests from the spec, checking `git status` after dry runs.
- [ ] Step 5: commit `feat(refactor): steroid_refactor for dialog-free refactorings`.

### Task 7: Prompts for part B

**Files:**
- Modify: `prompts/src/main/prompts/ide/safe-delete.md`, `ide/inspect-and-fix.md`,
  `lsp/rename.md`, `skill/execute-code-tool-description.md`

- [ ] Step 1: lead each with the `steroid_refactor` call, keep the script as
  the fallback.
- [ ] Step 2: commit `docs(prompts): lead refactoring recipes with steroid_refactor`.
