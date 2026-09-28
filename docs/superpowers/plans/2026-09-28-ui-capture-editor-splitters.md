# Captures of Code, Clicks, Tabs and Splitters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `steroid_ui` captures to code ranges, click points, tree tables, IDE tabs and splitters, and make cut content visible in text reports with a step that fixes it.

**Architecture:** Step fields and validation live in `mcp-steroid-server` (`UiSteps.kt`, schema, tool description). The IDE side lives in `ij-plugin/.../ui`: new `UiCodeRange.kt` (code highlights), `UiSplitters.kt` (splitter step, state, fit), `UiRunWatch.kt` (run and stop reports); `UiRows.kt` learns tree tables and `JBTabs`; `UiCapture.kt` learns pointer marks, obstacle-aware labels and off-window fill; `UiLayout.kt` learns the new cut checks; `UiSession.kt` wires them into `click`, `screenshot` and the new `splitter` action. JetDesk's `capture.js` gains options.

**Tech Stack:** Kotlin, IntelliJ Platform (Swing, `Splitter`, `ThreeComponentsSplitter`, `JBTabs`, `TreeTable`, `EditorEx`), JUnit; Node for JetDesk.

**Spec:** `docs/superpowers/specs/2026-09-28-ui-capture-editor-splitters-design.md`

## Global Constraints

- Plugin supports IDE builds 261, 262 and 263: no API newer than 261.
- Work on `main` in the fork, inline, no subagents; commit per task; never stage `VERSION`; push only when asked.
- Never run a bare `./gradlew :prompts:test`; prompt checks use `-Pmcp.prompts.ide.filter=none --tests '*ContractTest' --tests '*ResourceIndexTest' --tests '*PromptQualityTest'`, plus `*MarkdownArticleContractTest*`.
- Prompts use bare ``` fences, not ```json.
- Live tests run in the 2026.2.3 sandbox (`meta/sandbox-ide/`), port 6321, project `mcp-3k4rv292`; Split Mode with `start.sh --split`.
- Every new report line follows the existing style: plain sentences, no em dash, refs as `[ref=eN]`.
- Before each commit: `get_file_problems` (errorsOnly false) on changed `.kt` files through the `idea` MCP with `projectPath: c:/work/attaches/support-repro/mcp-steroid`.
- Restore contract: whatever a step changes, a call's `restore` and a scenario replay put back.

## Review Focus

1. A code range with folded regions or soft-wrapped lines inside it: the outline must cover the text as painted, not the logical columns; test in Task 4 with a folded line and a wrapped line.
2. A `splitter` step whose pane is collapsed to 0 or whose proportion is clamped by min/max proportion: the report must say what held it back, not claim the size; test in Task 5.
3. `fit` on a screenshot inside a dialog that the call's restore then closes: the saved proportion (`splitterProportionKey`) must still be put back; test live in Task 10.
4. A label that has no free spot in any direction: placement must fall back without an exception and keep the badge inside the picture; test in Task 3.
5. `select` on a `JBTabs` tab whose text repeats (two runs of the same configuration): the error must list tabs by index as rows do; test live in Task 2.

---

### Task 1: Step fields, validation, schema and tool description

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiSteps.kt`
- Modify: `mcp-steroid-server/src/main/resources/.../scenario-1.schema.json` (path as found by `rg -l scenario-1.schema.json`)
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiTool.kt` (description)
- Test: `mcp-steroid-server/src/test/kotlin/.../UiStepsTest.kt`, `UiScenarioSchemaTest.kt`, the golden schema test

**Interfaces:**
- Produces:
  - `UiAction.SPLITTER("splitter")`
  - `UiStep.proportion: Double?`, `UiStep.size: String?` (px or `"fit"`), `UiStep.key: String?` (a `JBSplitter` proportion key, for restores), `UiStep.fit: Boolean` (screenshot)
  - `UiHighlight` gains `lines: String?`, `symbol: String?`, `file: String?`, `click: Boolean`, `inspection: String?`, `console: String?`, `contains: String?`, `nth` stays in the target
  - `UiCrop.ToolWindow(id: String)`
  - `UiSteps.PROPORTIONS = 0.05..0.95`, `UiSteps.parseLines(spec: String): IntRange`

- [ ] **Step 1: Write failing tests in `UiStepsTest`**

```kotlin
@Test fun `splitter takes a proportion or a size`() {
    val s = UiSteps.parse("""[{"action":"splitter","ref":"e4","proportion":0.3}]""").single()
    assertEquals(UiAction.SPLITTER, s.action); assertEquals(0.3, s.proportion)
    assertEquals("fit", UiSteps.parse("""[{"action":"splitter","name":"Variables","size":"fit"}]""").single().size)
    assertFails("proportion or size") { UiSteps.parse("""[{"action":"splitter","ref":"e4"}]""") }
    assertFails("not both") { UiSteps.parse("""[{"action":"splitter","ref":"e4","proportion":0.3,"size":"200"}]""") }
    assertFails("0.05") { UiSteps.parse("""[{"action":"splitter","ref":"e4","proportion":0.99}]""") }
    assertFails("goes with splitter") { UiSteps.parse("""[{"action":"click","ref":"e4","proportion":0.3}]""") }
}
@Test fun `splitter restore by key needs no target`() {
    UiSteps.parse("""[{"action":"splitter","key":"x.split","proportion":0.4}]""")
    assertFails("key") { UiSteps.parse("""[{"action":"splitter","key":"x.split","size":"200"}]""") }
}
@Test fun `code highlights`() {
    val h = UiSteps.parse("""[{"action":"screenshot","out":"C:/a.png","highlight":[{"lines":"20-27","file":"a.ts"},{"symbol":"parse"},{"click":true,"label":"right-click"},{"inspection":"NullableProblems"},{"console":"App","contains":"tick 2"}]}]""").single().highlight!!
    assertEquals("20-27", h[0].lines); assertEquals("parse", h[1].symbol); assertTrue(h[2].click); assertEquals("NullableProblems", h[3].inspection); assertEquals("App", h[4].console)
    assertEquals(20..27, UiSteps.parseLines("20-27")); assertEquals(5..5, UiSteps.parseLines("5"))
    assertFails("lines") { UiSteps.parse("""[{"action":"screenshot","out":"C:/a.png","highlight":[{"lines":"27-20"}]}]""") }
    assertFails("one kind") { UiSteps.parse("""[{"action":"screenshot","out":"C:/a.png","highlight":[{"lines":"2","symbol":"x"}]}]""") }
    assertFails("contains") { UiSteps.parse("""[{"action":"screenshot","out":"C:/a.png","highlight":[{"console":"App"}]}]""") }
}
@Test fun `crop to a tool window and fit`() {
    val s = UiSteps.parse("""[{"action":"screenshot","out":"C:/a.png","crop":{"toolwindow":"Run"},"fit":true}]""").single()
    assertEquals(UiCrop.ToolWindow("Run"), s.crop); assertTrue(s.fit)
    assertFails("goes with screenshot") { UiSteps.parse("""[{"action":"click","ref":"e1","fit":true}]""") }
}
@Test fun `click on an editor takes line column or symbol`() {
    UiSteps.parse("""[{"action":"click","class":"EditorComponentImpl","button":"right","symbol":"parse"}]""")
    UiSteps.parse("""[{"action":"click","class":"EditorComponentImpl","line":3,"column":5}]""")
    assertFails("line or symbol") { UiSteps.parse("""[{"action":"click","class":"EditorComponentImpl","line":3,"symbol":"x"}]""") }
}
```

- [ ] **Step 2: Run, expect failures**

Run: `./gradlew :mcp-steroid-server:test --tests '*UiStepsTest*'`
Expected: FAIL, unknown field `proportion` / unknown action `splitter`.

- [ ] **Step 3: Implement**

In `UiSteps.kt`: add `SPLITTER("splitter")` to `UiAction`; `FIELDS += "proportion","size","key","fit"`; `HIGHLIGHT_FIELDS += "lines","symbol","file","click","inspection","console","contains"`; `parseCrop` accepts `{"toolwindow":"<id>"}` alone; fields on `UiStep`; `parseHighlight` builds the new kinds, a highlight has exactly one kind (locator, breadcrumb, lines, symbol, click, inspection, console), `lines`/`symbol` take `file`, `console` needs `contains`; `parseLines` parses `"a"` or `"a-b"` with `1 <= a <= b`. Validation:

```kotlin
if (step.proportion != null || step.size != null || step.key != null) require(step.action == UiAction.SPLITTER) { "proportion, size and key go with splitter, not $action" }
if (step.fit) require(step.action == UiAction.SCREENSHOT) { "fit goes with screenshot, not $action" }
if (step.action == UiAction.SPLITTER) {
    require((step.proportion != null) != (step.size != null)) { "splitter needs proportion or size, not both" }
    step.proportion?.let { require(it in PROPORTIONS) { "proportion is from 0.05 to 0.95, the first pane's share; was $it" } }
    step.size?.let { require(it == FIT || it.toIntOrNull()?.let { n -> n in SIZES } == true) { "size is \"fit\" or a size in logical pixels from ${SIZES.first} to ${SIZES.last}, was $it" } }
    if (step.key != null) require(step.proportion != null) { "a splitter restore by key takes a proportion" }
    else require(step.target != null) { "splitter needs a target: the splitter, or a control in the pane to size" }
}
if (step.action == UiAction.CLICK && (step.line != null || step.symbol != null)) require((step.line != null) != (step.symbol != null)) { "a click in an editor takes line or symbol, not both" }
```

Relax the `GOTO`-only `line`/`symbol`/`column` rules where they reject click. Schema: add `splitter` to the action enum, the fields with descriptions, the highlight kinds and the crop object. `UiTool.kt` description: one sentence naming the `splitter` step, code/click highlights and the `cut:` lines; update the golden file.

- [ ] **Step 4: Run tests**

Run: `./gradlew :mcp-steroid-server:test`
Expected: PASS (golden schema test updated in this step).

- [ ] **Step 5: Commit** — `feat(ui): step fields for code, click and tab highlights, splitters and fit`

---

### Task 2: Rows of tree tables and IDE tabs

**Files:**
- Modify: `ij-plugin/.../ui/UiRows.kt`, `ij-plugin/.../ui/UiSession.kt` (`pickRow`, `expandPath`, `tabOf`)
- Test: `ij-plugin/src/test/kotlin/.../ui/UiRowsTest.kt` (create if absent)

**Interfaces:**
- Produces: `UiRows.treeOf(c: Component): JTree?` (a `JTree`, or a `TreeTable`'s tree); `UiRows.tabsOf(c): JBTabs?`; rows, bounds, select, isSelected, view for both; `UiRows.cells` skips icon-only cells.

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun `tree table rows read as a tree`() = runInEdtAndWait {
    val root = DefaultMutableTreeNode("root").apply { add(DefaultMutableTreeNode("Java").apply { add(DefaultMutableTreeNode("Probable bugs").apply { add(DefaultMutableTreeNode("Nullability")) }) }) }
    val table = TreeTable(ListTreeTableModel(root, arrayOf(TreeColumnInfo("Name")))).apply { setRootVisible(false) }
    assertEquals(listOf("Java"), UiRows.rows(table))
    val view = UiRows.view(table)!!
    assertEquals(false, view.rows[0].expanded)
    assertNotNull(UiRows.treeOf(table))
}
@Test fun `an icon-only cell is left out`() = runInEdtAndWait {
    val table = JBTable(DefaultTableModel(arrayOf(arrayOf<Any>("Lossy encoding", EmptyIcon.ICON_16)), arrayOf("n", "i")))
    table.columnModel.getColumn(1).cellRenderer = DefaultTableCellRenderer().apply { }
    table.columnModel.getColumn(1).cellRenderer = TableCellRenderer { _, v, _, _, _, _ -> JLabel(v as Icon) }
    assertEquals(emptyList<String>(), UiRows.cells(table, 0))
}
```

- [ ] **Step 2: Run** — `./gradlew :ij-plugin:test --tests '*UiRowsTest*'` — Expected: FAIL (expanded null; cell prints `EmptyIcon`).

- [ ] **Step 3: Implement**

```kotlin
fun treeOf(c: Component): JTree? = c as? JTree ?: (c as? TreeTable)?.tree
fun tabsOf(c: Component): JBTabs? = c as? JBTabs
```

In `rows`, `bounds`, `view`, `row`, `select`, `scrollTo`, `isSelected`, `visibleRange`: a `TreeTable` branch that uses its tree for text, depth and expansion, and the table for bounds (`getCellRect(i, 0, true)`) and selection; a `JBTabs` branch: `tabs.tabs.map { it.text }`, bounds from `(tabs as JBTabsImpl).getTabLabel(info)` converted to the tabs component, `select(info, false)`, selected is `selectedInfo == info`. `cell()` returns null when the renderer is a label with an icon and no text, and `cells` drops nulls. `text()` reads an `EditorTextField` (`c.text`) before the container fallback. In `UiSession.pickRow`/`find`/`expandPath`, use `UiRows.treeOf(c)` where they test `c is JTree`; `expandPath` takes the tree and the row index maps 1:1 onto the tree table. `tabOf` also aims at a `JBTabs` tab by the target's name or text.

- [ ] **Step 4: Run tests** — Expected: PASS.

- [ ] **Step 5: Live check** in the sandbox: `select` with row `Java > Probable bugs > Nullability problems` on `InspectionsConfigTreeTable`; `select` of row `Process Console` on `JBRunnerTabs` in a debug session; snapshot shows the tabs as rows with `[selected]` and no `EmptyIcon`.

- [ ] **Step 6: Commit** — `feat(ui): tree table paths and IDE tabs as rows`

---

### Task 3: Pointer marks, labels that avoid text, off-window fill

**Files:**
- Modify: `ij-plugin/.../ui/UiCapture.kt`
- Test: `ij-plugin/src/test/kotlin/.../ui/UiCaptureTest.kt`

**Interfaces:**
- Produces: `UiCapture.Mark(number, bounds, label, pointer: Boolean = false)`; `UiCapture.highlight(canvas, marks, obstacles: List<Rectangle> = emptyList())`; `markArea(canvas, marks, obstacles)`; `badgeBounds(mark, size, within, placed, obstacles = emptyList())`; `paint(window)` fills with the window background first.

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun `a badge that would cover a neighbour's text goes below the outline`() {
    val mark = Rectangle(100, 20, 80, 24)
    val neighbourLeft = Rectangle(20, 20, 76, 24); val neighbourRight = Rectangle(184, 20, 80, 24)
    val b = UiCapture.badgeBounds(mark, 18, Rectangle(0, 0, 400, 200), emptyList(), listOf(neighbourLeft, neighbourRight))
    assertTrue(b.y >= mark.y + mark.height, "below: $b")
}
@Test fun `with no free spot the badge still lands inside the picture`() {
    val mark = Rectangle(10, 10, 50, 20)
    val b = UiCapture.badgeBounds(mark, 18, Rectangle(0, 0, 80, 40), emptyList(), listOf(Rectangle(0, 0, 80, 40)))
    assertTrue(Rectangle(0, 0, 80, 40).contains(b))
}
@Test fun `a pointer mark draws no outline box`() { /* paint a white canvas, highlight a pointer mark at (50,50,1,1): pixel at the tip is the outline color, a pixel 10 px left of it on the tip row stays white */ }
@Test fun `the area off the window is the window's background`() { /* a JWindow with a red background and an owned popup beyond its bottom: paint(), the pixel between them is red, not black */ }
```

Write the last two with real pixels (`image.getRGB`).

- [ ] **Step 2: Run** — FAIL (no obstacles parameter).

- [ ] **Step 3: Implement**

`badgeBounds`: candidates in order: the current left/right spot, then below the outline at its left edge (`Rectangle(mark.x, mark.y + mark.height + GAP, size, size)`), then above (`mark.y - GAP - size`), then inside the corner. The first candidate inside `within` that meets no `placed` and no `obstacles` rectangle wins; with none, the current rule's spot. Labels go right of the badge on its line; when that box meets an obstacle and the badge is left or right of the outline, move badge and label below (then above). Pointer mark: `outlines()` leaves a pointer mark's rectangle as the tip point; `highlight()` draws an arrow polygon (tip at the point, 12 x 19 logical px, outline color with the white edge) instead of the rounded rectangle, and the badge goes right of the arrow. `paint()`: `g.color = window.background ?: UIUtil.getPanelBackground(); g.fillRect(0, 0, area.width, area.height)` before painting windows.

- [ ] **Step 4: Run tests** — PASS.
- [ ] **Step 5: Commit** — `feat(ui): pointer marks, labels that keep off other text, no black beyond the window`

---

### Task 4: Code highlights, clicks at the caret, the click mark

**Files:**
- Create: `ij-plugin/.../ui/UiCodeRange.kt`
- Modify: `ij-plugin/.../ui/UiSession.kt` (click, `locateHighlight`, `screenshotStep`), `ij-plugin/.../ui/UiEditorSteps.kt` (reuse `CodeLocation` for a symbol)
- Test: `ij-plugin/src/test/kotlin/.../ui/UiCodeRangeTest.kt`

**Interfaces:**
- Produces:
  - `UiCodeRange.outline(lines: List<LineSpan>): Rectangle?` where `data class LineSpan(val left: Int?, val right: Int, val top: Int, val bottom: Int)` (left null for a blank line) — pure, tested.
  - `UiCodeRange.linesArea(editor: Editor, range: IntRange): Rectangle` in the content component's coordinates, soft wraps and folds as painted (EDT).
  - `UiCodeRange.symbolArea(editor, symbol, nth): Rectangle`.
  - `UiCodeRange.caretPoint(editor): Point`.
  - `UiCodeRange.hideCaret(editor): () -> Unit` returns the undo.
  - Session field `lastClick: Pair<Window, Point>?` (screen point).

- [ ] **Step 1: Failing tests (pure part)**

```kotlin
@Test fun `the outline spans the leftmost text to the widest line`() {
    val r = UiCodeRange.outline(listOf(LineSpan(40, 300, 0, 20), LineSpan(null, 0, 20, 40), LineSpan(20, 500, 40, 60)))!!
    assertEquals(Rectangle(20, 0, 480, 60), r)
}
@Test fun `all blank lines give no outline`() = assertNull(UiCodeRange.outline(listOf(LineSpan(null, 0, 0, 20))))
```

- [ ] **Step 2: Run** — FAIL (no class).

- [ ] **Step 3: Implement**

`linesArea`: for each logical line in range, skip it when inside a folded region's collapsed tail (`editor.foldingModel.isOffsetCollapsed`); visual lines from `editor.logicalToVisualPosition(LogicalPosition(line,0)).line` to the visual line of the line end; for each visual line: `top = editor.visualLineToY(v)`, `bottom = top + editor.lineHeight`, right = `editor.visualPositionToXY(VisualPosition(v, EditorUtil.getLastVisualLineColumnNumber(editor, v))).x`; left = x of the first non-whitespace offset of the logical line (`offsetToXY`) on its first visual line, null for a blank line. `outline` merges. A range taller than `editor.scrollingModel.visibleArea.height` fails: "lines 20-90 are 1400 px high and the editor shows 700 px, 35 lines; outline fewer lines". Scrolling: `scrollingModel.disableAnimation(); scrollTo(LogicalPosition(mid, 0), ScrollType.CENTER); enableAnimation()`.

In `UiSession`: a `CodeHighlight(editor, area, what, label)` `Located` whose `bringIntoView` scrolls as above and `screenBounds` converts from `editor.contentComponent`. `locateHighlight` handles `lines`, `symbol` (the editor for `file`, else the selected text editor of the project frame), `inspection` (find `InspectionsConfigTreeTable` in the window, find the row whose tool's short name matches through `InspectionConfigTreeNode.Tool` via reflection-free `getDefaultDescriptor().getKey()`… implement by walking the tree model nodes and comparing `toolWrapper.shortName`, expand its path, then a row highlight), `console` (Task 7), `click` (a pointer mark at `lastClick`, failing when null or in another window). `screenshotStep`: while painting, for each editor with a code highlight, `hideCaret` and undo in `finally`; `crop: highlights` with a code highlight widens `area.x` to the gutter's screen x (`(editor as EditorEx).gutterComponentEx`); report "the caret is hidden in the picture".

Click: in `actStep` CLICK, when the node is an `EditorComponentImpl` and there is no offset: with `line`/`column`/`symbol`, move the caret there first (`CodeLocation.resolve` as `goto` does); offset = `caretPoint(editor)` (caret visual position XY plus half a line height). Record `lastClick = window to screen point` for every click.

- [ ] **Step 4: Run tests** — PASS. Live: probe scenarios 1 and 2 of the spec; compare with `01-editor.png`, `02-context.png`.
- [ ] **Step 5: Commit** — `feat(ui): code range and click point highlights, clicks at the caret`

---

### Task 5: The splitter step and splitters in the snapshot

**Files:**
- Create: `ij-plugin/.../ui/UiSplitters.kt`
- Modify: `UiNode.kt` (`split: String?`, listed when set), `RemoteDriverModel.kt` and `FallbackUiWalker.kt` (fill it), `UiSnapshotFormatter.kt` (print it), `UiSession.kt` (dispatch `SPLITTER`)
- Test: `ij-plugin/src/test/kotlin/.../ui/UiSplittersTest.kt`

**Interfaces:**
- Produces:
  - `UiSplitters.describe(c: Component): String?` — `"horizontal 0.25"` / `"vertical 0.60"` for `Splitter`, `ThreeComponentsSplitter` (`"horizontal first 240 px, last 0 px"`), `JSplitPane`.
  - `class Pane(val splitter: JComponent, val child: Component, val axis: Axis)` with `Axis.WIDTH/HEIGHT`.
  - `UiSplitters.paneOf(c: Component, axis: Axis? = null): Pane?` — nearest splitter ancestor, the child that holds `c`; with `axis`, the nearest whose axis matches.
  - `UiSplitters.size(p: Pane): Int`, `UiSplitters.setSize(p: Pane, px: Int): Result` and `setProportion(splitter, Double): Result`, `data class Result(val before: Int, val after: Int, val proportionBefore: Double, val proportionAfter: Double, val heldBack: String?)`.
  - `UiSplitters.fitSize(p: Pane): Int` — preferred size along the axis, within what the other panes' minimum sizes leave.
  - `UiSplitters.cutAxis(c: Component): Axis?` — the axis along which `c`'s content is cut (preferred > shown).

- [ ] **Step 1: Failing tests** (real Swing, sized by hand, `validate()`)

```kotlin
@Test fun `a splitter pane takes a size`() = runInEdtAndWait {
    val first = JPanel(); val second = JPanel()
    val s = Splitter(false, 0.5f).apply { firstComponent = first; secondComponent = second; setSize(1000, 400); doLayout() }
    val r = UiSplitters.setSize(UiSplitters.paneOf(first)!!, 300)
    s.doLayout(); assertEquals(300, first.width, 2); assertTrue(r.proportionAfter in 0.29..0.31)
}
@Test fun `the second pane sized moves the proportion the other way`() = runInEdtAndWait { /* setSize(pane of second, 300) -> first gets 1000 - 300 - divider */ }
@Test fun `a clamp is reported`() = runInEdtAndWait {
    val s = Splitter(false, 0.5f, 0.3f, 0.7f) /* ... */; val r = UiSplitters.setProportion(s, 0.1)
    assertEquals("the splitter keeps its first pane between 0.30 and 0.70", r.heldBack)
}
@Test fun `three components splitter first and last`() = runInEdtAndWait { /* setSize(pane of last, 200) -> lastSize 200 */ }
@Test fun `split pane`() = runInEdtAndWait { /* JSplitPane divider moves */ }
@Test fun `fit gives a tree its rows`() = runInEdtAndWait {
    /* vertical Splitter, first = JBScrollPane(Tree with 6 rows), 400 high; setSize(fitSize(pane)) -> the tree shows all 6 rows */
}
@Test fun `cut axis`() = runInEdtAndWait { /* a tree in a 60 px high scroll pane with 6 rows -> HEIGHT */ }
@Test fun `describe`() = runInEdtAndWait { assertEquals("horizontal 0.50", UiSplitters.describe(Splitter(false, 0.5f))) }
```

- [ ] **Step 2: Run** — FAIL.

- [ ] **Step 3: Implement**

`Splitter`: axis is HEIGHT when `orientation` (vertical split) is true; total = size along axis minus `dividerWidth`; for the first pane `p = px / total`, for the second `1 - px / total`; `setProportion(p.toFloat())`, which clamps to `getMinimumProportion()..getMaximumProportion()` and, for a `JBSplitter` with a key, saves it; `heldBack` when the proportion after differs by more than 0.01 from the one asked. `ThreeComponentsSplitter`: `firstSize`/`lastSize` in px; the inner pane sized by changing `lastSize` (or `firstSize` when the last is empty); axis HEIGHT when `orientation` true. `JSplitPane`: `dividerLocation` = px for the left/top pane, `size - px - dividerSize` for the other. After a change, `revalidate()` and `UiSettle.barrier()` in the session.

Session `splitterStep(step)`: with `key` and no target → the showing `JBSplitter` whose `splitterProportionKey == key` gets the proportion, else `PropertiesComponent.getInstance().setValue(key, p.toFloat(), 0.5f)`. Otherwise resolve the target; the pane is `paneOf(c)` for a numeric size or a proportion, `paneOf(c, cutAxis(c) ?: nearest axis)` for fit; a target that is itself a splitter means its first pane. Record restores before the change: `UiRestore.step("splitter", "ref" to registry.refFor(pane.child), "size" to before)`, and for a `JBSplitter` with a key, `UiRestore.step("splitter", "key" to key, "proportion" to proportionBefore)`. A restore whose ref no longer shows reports "the splitter is gone; nothing to put back" and passes. Report: `moved the divider of OnePixelSplitter [ref=e40]: the pane with XDebuggerTree [ref=e41] is 240 px high, was 105 px (proportion 0.62, was 0.28)` plus `; held back: ...`.

Snapshot: `split = UiSplitters.describe(component)`; `listed` includes `split != null`; the formatter appends ` ` + split after the ref.

- [ ] **Step 4: Run tests** — PASS. Live: Project Structure SDKs tree `splitter` fit; debugger Variables fit; editor split divider `proportion 0.3`; each with `restore:true`, then a snapshot shows the splitter's old state.
- [ ] **Step 5: Commit** — `feat(ui): splitter step, splitter state in snapshots, restores`

---

### Task 6: Detection of cut content, `cut:` lines and `fit`

**Files:**
- Modify: `ij-plugin/.../ui/UiLayout.kt`, `ij-plugin/.../ui/UiSession.kt` (`screenshotStep`)
- Test: `ij-plugin/src/test/kotlin/.../ui/UiLayoutTest.kt` (create or extend)

**Interfaces:**
- Consumes: `UiSplitters.paneOf`, `UiSplitters.cutAxis`.
- Produces: `UiLayout.Problem(line, fix, toolWindow, area: Rectangle? = null)` (screen area of the cut control); `UiLayout.cutInside(window, root, refOf, project): List<Problem>` — the new checks; `problems()` includes them.

- [ ] **Step 1: Failing tests** (Swing frames built in the test, `UiModel.build` via `FallbackUiWalker`)

```kotlin
@Test fun `rows cut in height in a splitter pane name a splitter fit`() { /* vertical Splitter; first = scroll pane of a 6-row tree, 50 px high; problems() has a line "shows 2 of 6 rows" with fix {"action":"splitter","ref":"eN","size":"fit"} */ }
@Test fun `a long tree cut in height is not reported`() { /* 200 rows, 300 px view: no line */ }
@Test fun `a truncated table header is reported in a dialog`() { /* JTable in a JDialog, column 40 px, header "Default parameter": line names {"action":"window"} */ }
@Test fun `a one-line editor field cut is reported`() { /* EditorTextField in a dialog narrower than its text */ }
```

- [ ] **Step 2: Run** — FAIL.

- [ ] **Step 3: Implement**

New checks, each a `Problem` with its area:
- rows cut in height: a list, tree or table in a viewport, `rows shown < minOf(total, MIN_ROWS_SHOWN = 8)` and `total <= MAX_ROWS_TO_FIT = 40` (a long list that scrolls is normal), when `UiSplitters.paneOf(c, Axis.HEIGHT)` exists: "the pane with XDebuggerTree [ref=eN] shows 3 of 6 rows".
- rows cut at the right (the existing `rowsCut`) when `paneOf(c, Axis.WIDTH)` exists.
- a one-line editor (`EditorEx.isOneLineMode`), an `EditorTextField`, a combo box, whose content's preferred width exceeds its width by more than `SLACK`; a `JTableHeader` column whose header renderer's preferred width exceeds the column width.
- a label `TRUNCATED` inside a dialog (window not `IdeFrame`) or inside a splitter pane.
Fix preference: `splitter` fit on the control when `paneOf` finds a splitter along the cut axis; the tool window's step; `{"action":"window"}` for a dialog; the frame maximize. Group lines per fix so one splitter gets one line.

Screenshot: after painting, `cut:` lines for problems whose area meets the picture's screen area (`canvas.bounds`), `"cut: " + line`. With `fit`, before locating highlights: problems of the pictured window, apply each fix with `applyFix` (restores go to `stepUndo`), `UiSettle.settle()`, again once; then paint. `applyFix` dispatches `SPLITTER` to `splitterStep`.

- [ ] **Step 4: Run tests** — PASS. Live: the debugger `fit` picture shows all variables; Change Signature `fit` widens the dialog and the headers read whole.
- [ ] **Step 5: Commit** — `feat(ui): report content cut in panes, fields and headers; screenshot cut lines and fit`

---

### Task 7: Run and debug: reports, tool window crop, console highlights, ANSI

**Files:**
- Create: `ij-plugin/.../ui/UiRunWatch.kt`
- Modify: `UiSession.kt` (RUN branch; crop `ToolWindow`; console highlight), `freeze/IdeRuns.kt` (strip ANSI)
- Test: `ij-plugin/src/test/kotlin/.../freeze/IdeRunsTest.kt` (ANSI), `ij-plugin/src/test/kotlin/.../ui/UiRunWatchTest.kt` (report text)

**Interfaces:**
- Produces: `UiRunWatch(project)`: `start()`, `suspend fun report(actionId: String?): String?` — null when no run event and the action is not a run, debug or stop action; `IdeRuns.stripAnsi(s: String): String`.

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun `ansi escapes are dropped`() = assertEquals("tick 7", IdeRuns.stripAnsi("tick \u001B[33m7\u001B[39m"))
@Test fun `a run action that started nothing lists what runs`() = assertEquals(
    "no run or debug session started; running now: 'tabs-alpha.js' (Debug)",
    UiRunWatch.render(started = emptyList(), notStarted = emptyList(), stopped = emptyList(), running = listOf("'tabs-alpha.js' (Debug)"), runAction = true))
```

- [ ] **Step 2: Run** — FAIL.

- [ ] **Step 3: Implement**

`UiRunWatch.start()` subscribes to `ExecutionManager.EXECUTION_TOPIC` on the project's bus: `processStarted` adds `'name' (executorId)`, `processNotStarted` adds the name with the cause's first line, `processTerminated` adds to stopped. `report()` waits up to 2 s for a started or terminated event when the action is a run/debug/stop action (ids `Run`, `Debug`, `RunClass`, `DebugClass`, `Rerun`, `Stop`, `ChooseRunConfiguration`, and any `ExecutorAction`), then renders: `started 'x' (Run)`, `did not start 'x': cause`, `stopped 'x'`, and for `Stop`, `still running: …` from `ExecutionManager.getRunningProcesses()`; for a run action with no event, the line in the test. The RUN branch in `UiSession.runStep` appends it with `; `. `IdeRuns.report` and `expect console` text pass lines through `stripAnsi` (regex `\u001B\[[0-?]*[ -/]*[@-~]`). Crop `ToolWindow(id)`: `UiLayout.toolWindows(project)` by id ignoring case, the decorator's screen bounds; failing lists the showing ids. Console highlight: the run's `RunContentDescriptor` by name (`RunContentManager.allDescriptors`), its `executionConsole`: a `ConsoleViewImpl` → its `editor` and the line of the last (or `nth`) match of `contains` → `UiCodeRange.linesArea`; a terminal-based console → find the text in its `TerminalTextBuffer` through the `JBTerminalPanel` and outline that line from the panel's char height; a console of neither kind fails "the console of 'x' is a <class>; outline it with a locator".

- [ ] **Step 4: Run tests** — PASS. Live: probe scenarios 8 and 9.
- [ ] **Step 5: Commit** — `feat(ui): run and stop reports, crop to a tool window, console line highlights, no ANSI in console text`

---

### Task 8: Offscreen painting of editor-based table cells

**Files:**
- Modify: `ij-plugin/.../ui/UiCapture.kt` (and what the probe shows)

- [ ] **Step 1: Probe** in the sandbox with `steroid_execute_code` (`modal: dialog`) on Change Signature: paint the dialog with `printAll` into an image and compare the table area with `Robot.createScreenCapture` of the same area; then with `paint` of the table alone; then after `table.getCellRenderer(0,1).getTableCellRendererComponent(...).addNotify`-free layout (`setSize` to the cell rect and `validate()`). Record which paint path is right.
- [ ] **Step 2: Implement** the path the probe found in `UiCapture.paint`: for each showing `JTable` in the window whose renderers are `EditorTextField`s, paint its cells after sizing the renderer to the cell and validating it; or, if no offscreen path is right, a screen capture of that table's area pasted into the picture with the report note "the table at … was captured from the screen".
- [ ] **Step 3: Live check** — the Change Signature picture shows `string` whole and no gray bar.
- [ ] **Step 4: Commit** — `fix(ui): editor-based table cells paint whole in pictures`

---

### Task 9: Documentation and JetDesk

**Files:**
- Modify: `prompts/src/main/prompts/ide/ui-scenarios.md`, `prompts/src/main/prompts/ide/ui-driving.md`
- Modify (JetDesk): `scripts/steroid/screenshot/capture.js`, `capture.test.js`, `.claude/docs/steroid-ui-driving.md`

- [ ] **Step 1: Prompts** — sections "Code and the click point", "Tabs of run and debug", "Splitters", "Cut content and fit" in `ui-scenarios.md`, one example each, bare fences; `ui-driving.md`: the `splitter` step in the step list and a line on `cut:`.
- [ ] **Step 2: Prompt checks** — `./gradlew :prompts:test -Pmcp.prompts.ide.filter=none --tests '*ContractTest' --tests '*ResourceIndexTest' --tests '*PromptQualityTest' --tests '*MarkdownArticleContractTest*'` — PASS.
- [ ] **Step 3: capture.js tests first** (`capture.test.js`): `buildSteps({file:'a.ts', lines:'20-27'})` gives a `goto` and a `lines` highlight with `crop:"highlights"`; `--right-click` adds the click and `{"click":true,"label":"right-click"}` with `crop:"popups"`; `--inspection X` gives the settings step and the inspection highlight; `--splitter "Variables=fit"`; `--fit`; `--toolwindow Debug --tab a.js --subtab "Process Console"`. Run `tools/node.cmd scripts/run-tests.js capture` — FAIL, then implement `parseArgs`/`buildSteps`, run — PASS.
- [ ] **Step 4: JetDesk doc** — examples in "Capture a picture".
- [ ] **Step 5: Commits** — fork: `docs(ui): code, click, tabs, splitter and fit`; JetDesk: `feat(steroid): capture code, context menus, inspections, tabs and splitters`.

---

### Task 10: Live verification

- [ ] Build and install: `meta/sandbox-ide/reinstall.sh`.
- [ ] Run the spec's nine live checks in the monolith; save the pictures under `workspace/capture/explore2/`, read each, compare with the probe's.
- [ ] Split Mode: `meta/sandbox-ide/restart.sh --split`; checks 1, 2, 5 and 8 through the client.
- [ ] Each run with `restore:true`; a snapshot before and after matches (splitter states, tool window sizes, caret).
- [ ] Clean the sandbox: demo files, breakpoints, runs.
