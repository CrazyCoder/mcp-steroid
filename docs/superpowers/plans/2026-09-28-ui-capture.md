# Captures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One `steroid_ui` call, or one JetDesk command, saves a framed picture of a Settings page, menu or dialog with numbered highlights, and leaves the IDE as it found it.

**Architecture:** The `screenshot` step gains `out`, `highlight`, `crop` and `margin`; a new `UiCapture` unit paints the window with its popups, crops, draws the highlights and compares with the file it replaces. Framing comes from `window` (with a size restore that survives the dialog closing), `scroll` with `align`, `menu` with `show`, and `set`/`get` of `theme` in a new `UiThemes` unit. A call parameter `restore` closes what the call opened and applies the journal. JetDesk's `capture.js` builds the steps.

**Tech Stack:** Kotlin (IntelliJ Platform 261+, Swing/AWT), kotlinx.serialization, JUnit 5; Node.js for JetDesk.

**Spec:** `docs/superpowers/specs/2026-09-28-ui-capture-design.md`

## Global Constraints

- The plugin builds against `sinceBuild = "261"`: no API newer than 2026.1 without a reflective fallback.
- Every new step field is added to `UiSteps.FIELDS`, `scenario-1.schema.json` and `format-1.scenario.json`; `UiScenarioSchemaTest` enforces it.
- A new tool parameter updates the golden schema of `DevrigToolSpecsGoldenSchemaTest`.
- Never run a bare `:prompts:test`; filter with `--tests "*MarkdownArticleContractTest*"`.
- Conventional commits, no AI attribution, commit per task, never push.
- Docs follow the prose standard: short sentences, no em dash, one term per concept, no archaeology.
- Pictures are painted from the IDE's windows, never read from the screen.
- Live tests run in the sandboxes (`meta/sandbox-ide/`, `SANDBOX=263` for 2026.3 Split Mode); the main IDE is not restarted.

## Review Focus

1. A highlight on a control that is not showing, such as one on an unselected tab: the step fails naming it, never outlines a spot at 0,0. Test in Task 3.
2. A screen scale of 1.5 or 2: outlines and crops land on the painted pixels. Test in Task 2 with a scaled canvas.
3. `out` in a folder that does not exist, or a path that cannot be written: folders are created; an I/O error fails the step with the path. Test in Task 3.
4. A theme set while Settings is open, and a restore when the IDE synced its theme with the OS: the switch applies and waits, the restore turns syncing back on. Live test in Task 7.
5. `restore: true` after a step failed with a menu or popup left open: the menu closes and the journal still runs. Live test in Task 8.

---

### Task 1: Step fields and validation

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiSteps.kt`
- Modify: `mcp-steroid-server/src/main/resources/ui-scenarios/scenario-1.schema.json`
- Modify: `mcp-steroid-server/src/test/resources/ui-scenarios/format-1.scenario.json`
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/UiStepsTest.kt`, `UiScenarioSchemaTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** One highlight of a screenshot: a control by its locator, optionally a row of it, or the Settings breadcrumb. */
  data class UiHighlight(val target: UiTarget?, val breadcrumb: Boolean = false, val row: String? = null, val index: Int? = null, val label: String? = null)
  sealed interface UiCrop {
      object Page : UiCrop
      object Highlights : UiCrop
      data class Control(val target: UiTarget) : UiCrop
  }
  // new UiStep fields
  val out: String? = null
  val highlight: List<UiHighlight>? = null
  val crop: UiCrop? = null
  val margin: Int? = null          // null means UiSteps.DEFAULT_MARGIN
  val align: String? = null        // one of UiSteps.ALIGNS
  val show: Boolean = false
  val theme: String? = null
  val themes: Boolean = false
  val dimension: String? = null
  // UiSteps
  const val DEFAULT_MARGIN = 16
  val MARGINS = 0..200
  val ALIGNS = setOf("top", "center")
  const val BREADCRUMB = "breadcrumb"
  ```

- [ ] **Step 1: Write the failing tests** in `UiStepsTest`:

```kotlin
@Test fun `a screenshot takes out, highlights, a crop and a margin`() {
    val s = UiSteps.parse("""[{"action":"screenshot","out":"C:/pics/a.png","highlight":["breadcrumb",{"name":"Show line numbers","label":"Turn on"}],"crop":"page","margin":8}]""").single()
    assertEquals("C:/pics/a.png", s.out)
    assertEquals(listOf(UiHighlight(null, breadcrumb = true), UiHighlight(UiTarget(name = "Show line numbers"), label = "Turn on")), s.highlight)
    assertEquals(UiCrop.Page, s.crop)
    assertEquals(8, s.margin)
}
@Test fun `a crop names a control by its locator`() {
    val s = UiSteps.parse("""[{"action":"screenshot","save":"a","crop":{"name":"Settings categories"}}]""").single()
    assertEquals(UiCrop.Control(UiTarget(name = "Settings categories")), s.crop)
}
@Test fun `capture fields are rejected where they do not belong`() {
    fun err(json: String) = assertThrows<IllegalArgumentException> { UiSteps.parse("[$json]") }.message!!
    assertContains(err("""{"action":"screenshot"}"""), "save or out")
    assertContains(err("""{"action":"screenshot","save":"a","out":"C:/a.png"}"""), "not both")
    assertContains(err("""{"action":"screenshot","out":"a.gif"}"""), ".png")
    assertContains(err("""{"action":"screenshot","save":"a","crop":"highlights"}"""), "highlight")
    assertContains(err("""{"action":"screenshot","save":"a","crop":"left"}"""), "page")
    assertContains(err("""{"action":"screenshot","save":"a","highlight":[{"label":"x"}]}"""), "locator")
    assertContains(err("""{"action":"screenshot","save":"a","highlight":[{"name":"a","shadow":1}]}"""), "shadow")
    assertContains(err("""{"action":"screenshot","save":"a","margin":500}"""), "margin")
    assertContains(err("""{"action":"click","name":"a","highlight":["breadcrumb"]}"""), "screenshot")
    assertContains(err("""{"action":"scroll","name":"a","align":"bottom"}"""), "top")
    assertContains(err("""{"action":"scroll","name":"a","align":"top","pages":1}"""), "align")
    assertContains(err("""{"action":"menu","show":true}"""), "path")
    assertContains(err("""{"action":"set","theme":"Light","value":"x"}"""), "value")
    assertContains(err("""{"action":"get","themes":true,"registry":"a"}"""), "exactly one")
    assertContains(err("""{"action":"window","dimension":"SettingsEditor"}"""), "width")
}
@Test fun `theme steps parse`() {
    assertEquals("Light", UiSteps.parse("""[{"action":"set","theme":"Light"}]""").single().theme)
    assertTrue(UiSteps.parse("""[{"action":"get","themes":true}]""").single().themes)
}
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :mcp-steroid-server:test --tests "*UiStepsTest*"`
Expected: compile errors on `UiHighlight`, `UiCrop`, `out`.

- [ ] **Step 3: Implement.** Add the types and fields above. In `FIELDS` add `out`, `highlight`, `crop`, `margin`, `align`, `show`, `theme`, `themes`, `dimension`. Parsing:

```kotlin
private val HIGHLIGHT_FIELDS = TARGET_FIELDS + setOf("row", "index", "label")

private fun parseHighlight(e: JsonElement): UiHighlight = when {
    e is JsonPrimitive && e.isString && e.content == BREADCRUMB -> UiHighlight(null, breadcrumb = true)
    e is JsonObject -> {
        val unknown = e.keys - HIGHLIGHT_FIELDS
        require(unknown.isEmpty()) { "a highlight has unknown field(s) ${unknown.joinToString()}; it takes ${HIGHLIGHT_FIELDS.sorted().joinToString()}" }
        val target = UiTarget(e.string("ref"), e.string("name"), e.string("text"), e.string("class"), e.string("xpath"), e.int("nth"))
            .takeIf { it.ref != null || it.name != null || it.text != null || it.cls != null || it.xpath != null }
            ?: throw IllegalArgumentException("a highlight needs a locator: ref, name, text, class or xpath, or is \"$BREADCRUMB\"")
        UiHighlight(target, row = e.string("row"), index = e.int("index"), label = e.string("label"))
    }
    else -> throw IllegalArgumentException("a highlight is \"$BREADCRUMB\" or an object with a locator")
}

private fun parseCrop(e: JsonElement): UiCrop = when {
    e is JsonPrimitive && e.isString && e.content == "page" -> UiCrop.Page
    e is JsonPrimitive && e.isString && e.content == "highlights" -> UiCrop.Highlights
    e is JsonObject -> {
        val unknown = e.keys - TARGET_FIELDS
        require(unknown.isEmpty()) { "crop takes a locator: ${TARGET_FIELDS.sorted().joinToString()}" }
        UiCrop.Control(UiTarget(e.string("ref"), e.string("name"), e.string("text"), e.string("class"), e.string("xpath"), e.int("nth")))
    }
    else -> throw IllegalArgumentException("crop is \"page\", \"highlights\" or a locator object")
}
```

`highlight` must be a JSON array (`"highlight is a JSON array of highlights"`); an empty array is rejected. The private `JsonObject.string/int` helpers are reused for highlight objects. Validation in `validate`:

```kotlin
val captureFields = listOfNotNull(step.out?.let { "out" }, step.highlight?.let { "highlight" }, step.crop?.let { "crop" }, step.margin?.let { "margin" })
if (step.action != UiAction.SCREENSHOT) require(captureFields.isEmpty()) { "${captureFields.joinToString()} go(es) with screenshot, not $action" }
step.out?.let { require(it.isNotBlank() && it.endsWith(".png", ignoreCase = true)) { "out is the path of a .png file" } }
step.margin?.let { require(it in MARGINS) { "margin is from ${MARGINS.first} to ${MARGINS.last} pixels, was $it" } }
if (step.crop == UiCrop.Highlights) require(!step.highlight.isNullOrEmpty()) { "crop \"highlights\" needs highlight" }
step.highlight?.forEach { h -> if (h.row != null && h.index != null) throw IllegalArgumentException("a highlight takes row or index, not both") }
step.align?.let {
    require(step.action == UiAction.SCROLL) { "align goes with scroll, not $action" }
    require(it in ALIGNS) { "align is top or center, was $it" }
    require(step.pages == null) { "align places the target; drop pages" }
}
if (step.show) {
    require(step.action == UiAction.MENU) { "show goes with menu, not $action" }
    require(!step.path.isNullOrBlank()) { "menu with show needs a path, such as \"View > Appearance\"" }
}
if (step.theme != null) require(step.action == UiAction.SET) { "theme goes with set, not $action" }
if (step.themes) require(step.action == UiAction.GET) { "themes goes with get, not $action" }
step.dimension?.let {
    require(step.action == UiAction.WINDOW) { "dimension goes with window, not $action" }
    require(step.width?.toIntOrNull() != null && step.height?.toIntOrNull() != null) { "window with dimension needs width and height in pixels" }
}
```

In the screenshot branch: `require(step.save != null || step.out != null) { "screenshot needs save, the picture's name, or out, its path" }` and `require(step.save == null || step.out == null) { "pass save or out, not both" }`. In the GET/SET kinds list add `step.theme` and `"themes".takeIf { step.themes }`, extend both messages, and make `set` with a theme take no value: `if (step.theme != null) require(step.value == null) { "set of a theme takes the theme's name in theme, not a value" } else require(step.value != null) { "set needs a value" }`. `validate` must accept `show` without a target for menu. Schema: add the fields (`highlight` as an array of `anyOf` the const `"breadcrumb"` or an object with the locator properties plus row, index, label and `additionalProperties:false`; `crop` as `anyOf` enum page/highlights or a locator object; `align` enum; `margin` integer 0..200; `out` string pattern `\.png$`), and change the screenshot `then` to `{"anyOf":[{"required":["save"]},{"required":["out"]}]}`. Add to the fixture a screenshot with `out`, `highlight`, `crop`, `margin`; a `scroll` with `align`; a `menu` with `show`; `set` `theme`; `get` `themes`; `window` with `dimension`. In `UiScenarioSchemaTest` assert `UiSteps.ALIGNS` equals the `align` enum, and add the shape case `{"action":"screenshot"}` to `anyOf`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :mcp-steroid-server:test --tests "*UiStepsTest*" --tests "*UiScenarioSchemaTest*" --tests "*UiScenarioTest*"`
Expected: PASS.

- [ ] **Step 5: Commit** `feat(ui): step fields for captures: out, highlight, crop, align, show, theme`

### Task 2: `UiCapture`: paint with popups, crop, highlights, compare

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiCapture.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiCaptureTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object UiCapture {
      /** A picture and where its top left corner is on screen, in logical pixels; [scale] is image pixels per logical pixel. */
      class Canvas(val image: BufferedImage, val origin: Point, val scale: Double)
      /** A highlight in screen coordinates. */
      data class Mark(val number: Int, val bounds: Rectangle, val label: String?)
      fun paint(window: Window): Canvas                                         // EDT: the window plus its showing owned popups
      fun popupsOf(window: Window): List<Window>                                // EDT
      fun union(rects: List<Rectangle>): Rectangle
      fun cropArea(area: Rectangle, margin: Int, within: Rectangle): Rectangle
      fun crop(canvas: Canvas, screenArea: Rectangle): Canvas
      fun badgeBounds(mark: Rectangle, size: Int, within: Rectangle, placed: List<Rectangle>): Rectangle
      fun highlight(canvas: Canvas, marks: List<Mark>): Canvas
      fun markArea(canvas: Canvas, marks: List<Mark>): Rectangle                // screen area of the marks with their badges and labels
      fun differingPixels(a: BufferedImage, b: BufferedImage): Int              // Int.MAX_VALUE when sizes differ
  }
  ```

- [ ] **Step 1: Write the failing tests** (plain `BufferedImage`, no IDE):

```kotlin
class UiCaptureTest {
    private fun canvas(w: Int, h: Int, scale: Double = 1.0) =
        UiCapture.Canvas(BufferedImage((w * scale).toInt(), (h * scale).toInt(), BufferedImage.TYPE_INT_RGB), Point(100, 50), scale)

    @Test fun `a crop keeps its margin inside the picture`() {
        assertEquals(Rectangle(90, 40, 70, 40), UiCapture.cropArea(Rectangle(100, 50, 50, 20), 10, Rectangle(0, 0, 1000, 1000)))
        assertEquals(Rectangle(0, 0, 60, 30), UiCapture.cropArea(Rectangle(5, 5, 50, 20), 10, Rectangle(0, 0, 1000, 1000)))
    }
    @Test fun `a crop of a scaled canvas cuts image pixels at the scale`() {
        val c = UiCapture.crop(canvas(400, 300, scale = 2.0), Rectangle(150, 100, 100, 50))
        assertEquals(200, c.image.width); assertEquals(100, c.image.height); assertEquals(Point(150, 100), c.origin)
    }
    @Test fun `a badge sits at the top left outside the mark, and moves past placed badges`() {
        val first = UiCapture.badgeBounds(Rectangle(100, 100, 80, 20), 18, Rectangle(0, 0, 500, 500), emptyList())
        assertEquals(Rectangle(82, 82, 18, 18), first)
        val second = UiCapture.badgeBounds(Rectangle(100, 100, 80, 20), 18, Rectangle(0, 0, 500, 500), listOf(first))
        assertFalse(second.intersects(first))
        val atEdge = UiCapture.badgeBounds(Rectangle(2, 2, 80, 20), 18, Rectangle(0, 0, 500, 500), emptyList())
        assertTrue(Rectangle(0, 0, 500, 500).contains(atEdge))
    }
    @Test fun `highlighting draws the outline where the mark is, at the scale`() {
        val c = UiCapture.highlight(canvas(400, 300, scale = 2.0), listOf(UiCapture.Mark(1, Rectangle(200, 100, 100, 40), null)))
        // the mark's left edge is 100 logical px right of the origin: 200 image px
        assertNotEquals(0, c.image.getRGB(200, 150) and 0xFFFFFF)
        assertEquals(0, c.image.getRGB(260, 150) and 0xFFFFFF)
    }
    @Test fun `the mark area holds the badge and the label`() {
        val c = canvas(400, 300)
        val area = UiCapture.markArea(c, listOf(UiCapture.Mark(1, Rectangle(200, 100, 100, 40), "Turn this on")))
        assertTrue(area.contains(Rectangle(200, 100, 100, 40)))
        assertTrue(area.x < 200 && area.y < 100)
    }
    @Test fun `identical pictures differ in no pixel, and a changed pixel counts`() {
        val a = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB); val b = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        assertEquals(0, UiCapture.differingPixels(a, b))
        b.setRGB(3, 3, 0xFF0000)
        assertEquals(1, UiCapture.differingPixels(a, b))
        assertEquals(Int.MAX_VALUE, UiCapture.differingPixels(a, BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB)))
    }
}
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :ij-plugin:test --tests "*UiCaptureTest*"`
Expected: compile error, `UiCapture` unresolved.

- [ ] **Step 3: Implement.** `paint`: collect `window` plus `popupsOf(window)` (recursively owned windows that are showing and are not `Dialog`s, which a dialog on top would own as a separate window), take the union of their screen bounds, create the image with `ImageUtil.createImage(window.graphicsConfiguration, w, h, TYPE_INT_ARGB)` so its scale follows the screen's, paint each with `g.translate(offset)` then `printAll(g)`, and read the scale as `JBUIScale.sysScale(window)`. Save through `ImageUtil.toBufferedImage(image)` so the PNG has the image's real pixels. `crop`: convert the screen area to image pixels (`(x - origin.x) * scale`), intersect with the image, `getSubimage` into a copy. `highlight`: draw on a copy with `g.scale(scale, scale)` and `g.translate(-origin)`, antialiasing on; per mark a `RoundRectangle2D` outline 2.5 px wide in `OUTLINE = Color(0xE5, 0x2B, 0x50)`, a 1 px white edge outside it, a filled circle badge of `BADGE = 18` px with the white number centred, and the label in a filled rounded box right of the badge. Badges are placed with `badgeBounds`, which tries top left outside, then top right, then inside top left, clamps into `within`, and shifts right past `placed`. `markArea` unions each mark with its badge and label boxes. `differingPixels` compares `getRGB` of equal-sized images.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :ij-plugin:test --tests "*UiCaptureTest*"`
Expected: PASS.

- [ ] **Step 5: Commit** `feat(ui): paint a window with its popups, crop it and draw numbered highlights`

### Task 3: The screenshot step captures

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSession.kt` (`screenshotStep`, constructor gains `scenarioDir: Path?`)
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiPictureFacts.kt` (crop in the JSON)
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSettingsParts.kt`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiToolHandler.kt` (pass the scenario file's folder)
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiCapturePathTest.kt`

**Interfaces:**
- Consumes: `UiCapture` (Task 2), `UiHighlight`, `UiCrop`, `UiStep.out/highlight/crop/margin` (Task 1).
- Produces:
  ```kotlin
  object UiSettingsParts {
      fun breadcrumbs(window: Window): JComponent?     // EDT: the showing Breadcrumbs of the Settings page, with crumbs
      fun crumbsBounds(c: JComponent): Rectangle       // EDT: screen bounds of the painted crumbs, not the whole bar
      fun page(window: Window): Rectangle?             // EDT: screen bounds of the breadcrumbs plus the page editor
  }
  object UiCapturePaths { fun resolve(out: String, scenarioDir: Path?): Path }   // relative needs scenarioDir
  ```

- [ ] **Step 1: Write the failing test** for the path rule:

```kotlin
class UiCapturePathTest {
    @Test fun `a relative out resolves against the scenario folder`() {
        assertEquals(Path.of("C:/docs/scenarios/img/a.png"), UiCapturePaths.resolve("img/a.png", Path.of("C:/docs/scenarios")))
    }
    @Test fun `an absolute out is kept`() {
        assertEquals(Path.of("C:/pics/a.png"), UiCapturePaths.resolve("C:/pics/a.png", null))
    }
    @Test fun `a relative out outside a scenario is refused`() {
        assertThrows<UiStepFailure> { UiCapturePaths.resolve("a.png", null) }.also { assertContains(it.message!!, "absolute") }
    }
}
```

- [ ] **Step 2: Run it to fail**, `./gradlew :ij-plugin:test --tests "*UiCapturePathTest*"`.

- [ ] **Step 3: Implement.**
  - `UiCapturePaths.resolve`: absolute stays; relative with `scenarioDir` resolves and normalizes; relative without it throws `UiStepFailure("out is a relative path; in a call with steps give an absolute path")`.
  - `UiSettingsParts`: `breadcrumbs` walks the window for `com.intellij.ui.components.breadcrumbs.Breadcrumbs` instances that are showing and have crumbs (`crumbs.iterator().hasNext()`); `crumbsBounds` takes `min(width, preferredSize.width)` from its screen location; `page` unions the breadcrumbs' bounds with the showing component whose class simple name is `ConfigurableEditor` (verify the class name live in both 262 and 263 and adjust).
  - `screenshotStep`:
    1. Resolve the pictured window as today (target, else topmost).
    2. For each highlight: breadcrumb → `UiSettingsParts.breadcrumbs(window)` or fail `"no Settings page is showing"`; else `resolve(target)`, a row via `pickRow`, fail when the component is not showing (`"<describe> is not showing; select its tab or page first"`); scroll the control, or the row via `UiRows.scrollTo`, to the middle of its viewport (shared helper with Task 4, `scrollToAlign(c, rect, "center")`) when it is outside the viewport's view rect.
    3. `UiSettle.settle()`, then on EDT: screen bounds of each highlight (row bounds via `UiRows.bounds` translated to screen, breadcrumb via `crumbsBounds`), `UiCapture.paint(window)`, `highlight`, then crop: none → keep; `Page` → `UiSettingsParts.page(window)` or fail `"crop \"page\" needs a Settings page"`; `Highlights` → `markArea`; `Control` → resolved control's screen bounds; each through `cropArea(area, margin ?: DEFAULT_MARGIN, canvas bounds on screen)`.
    4. Target file: `out` → `UiCapturePaths.resolve(out, scenarioDir)`, else `<artifacts>/screenshots/<save>.png`. Create folders; if the file exists read it with `ImageIO.read` and compute `differingPixels`; write PNG; an `IOException` fails the step with the path and the message.
    5. Facts JSON beside it (`<name>.json`), with the crop added (`UiPictureFacts.json(crop = "page" | "highlights" | "control" | "window")`).
    6. Report: `saved 1234x876 picture of <window> to <path> (<facts>); highlights: 1 breadcrumb, 2 "Show line numbers"; crop page` plus `; unchanged` or `; changed: N pixels differ` when a file was replaced.
  - `UiToolHandler`: `loadScenario` returns the file too; pass `file.parent` as `scenarioDir` to `UiSession`.

- [ ] **Step 4: Unit tests pass**, then live test on the 262 sandbox (`meta/sandbox-ide/ui.sh`): Settings `editor.preferences.appearance`, screenshot with `highlight:["breadcrumb",{"name":"Show line numbers"}]` for each crop value, `out` under `workspace/capture/`; read each PNG and check the outlines and numbers sit on the controls. Repeat on `SANDBOX=263` in Split Mode for a client page (Appearance & Behavior > Appearance). A highlight on a control on an unselected tab fails with its message (Review Focus 1). An `out` in a new folder works; an `out` on a read-only path fails with the path (Review Focus 3).

- [ ] **Step 5: Commit** `feat(ui): screenshot highlights, crops and a caller-named path`

### Task 4: `scroll` with `align`

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSession.kt` (`scrollStep`, new `scrollToAlign`)
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiScrollAlignTest.kt`

**Interfaces:**
- Produces: `internal fun alignedViewY(rectY: Int, rectHeight: Int, extent: Int, viewHeight: Int, align: String): Int` in `UiCapture`'s companion file or a small `UiScrollAlign` object; `scrollToAlign(c: Component, rect: Rectangle, align: String)` in `UiSession` (EDT).

- [ ] **Step 1: Failing test**

```kotlin
class UiScrollAlignTest {
    @Test fun `top puts the rect at the top, center in the middle, clamped to the content`() {
        assertEquals(500, UiScrollAlign.viewY(500, 20, 300, 2000, "top"))
        assertEquals(360, UiScrollAlign.viewY(500, 20, 300, 2000, "center"))
        assertEquals(1700, UiScrollAlign.viewY(1990, 10, 300, 2000, "top"))
        assertEquals(0, UiScrollAlign.viewY(10, 10, 300, 2000, "center"))
    }
}
```

- [ ] **Step 2: Run to fail.**
- [ ] **Step 3: Implement** `viewY = (if (align == "top") rectY else rectY - (extent - rectHeight) / 2).coerceIn(0, max(0, viewHeight - extent))`. `scrollToAlign` converts the target's (or row's) rect to the viewport view's coordinates and sets `viewPosition`. `scrollStep` uses it when `step.align != null`; the report says `aligned ... to the top` or `to the middle`. The screenshot step calls it with `center` for highlights outside the view.
- [ ] **Step 4: Tests pass; live**: a long page (Editor > General) `{"action":"scroll","name":"Show line numbers","align":"top"}`, then a screenshot shows it at the top.
- [ ] **Step 5: Commit** `feat(ui): scroll a control to the top or middle of its view`

### Task 5: Window size restore for dialogs

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSession.kt` (`windowStep`)
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiResize.kt`
- Test: live only (the dimension store needs a running IDE)

**Interfaces:**
- Produces: `UiResize.dimensionKey(window: Window): String?` (EDT), `UiResize.restoreDimension(key: String, width: Int, height: Int, project: Project)`; the restore step `{"action":"window","dimension":"<key>","width":W,"height":H}`.

- [ ] **Step 1: Discover** where Settings saves its size: in the 262 sandbox, open Settings, resize it, close it, then with a `code` step read `DimensionService.getInstance().getSize("<key>", project)` for the key from `DialogWrapper.findInstance(...)`'s `getDimensionKey()` (protected, reached by reflection), and for the 263 floating Settings frame check `WindowStateService.getInstance().getSize("<key>")` with the key the non-modal wrapper uses (read `NonModalWindowWrapper` through the monorepo MCP). Record both keys and stores in the task notes of the commit body.
- [ ] **Step 2: Implement.** `dimensionKey`: the DialogWrapper's key, else the non-modal Settings wrapper's key, else null. In `windowStep`, for a window that is not the IDE frame and has a key, record `UiRestore.step("window", "dimension" to key, "width" to w, "height" to h)` with the size before. A `window` step with `dimension`: if a showing window has that key, size it with `UiResize.window`; then write the size to the store the key belongs to (`DimensionService.setSize` and, for a window-state key, `WindowStateService.setSize`) so the next opening uses it. Report `the saved size of <key> is back to WxH`.
- [ ] **Step 3: Live test** on 262 and 263: size Settings to 1400x900 in a call with `restore: true` (after Task 8, or with a scenario before), close it, reopen: it opens at the size it had before.
- [ ] **Step 4: Commit** `feat(ui): a resized dialog gets its saved size back after it closes`

### Task 6: `menu` with `show`

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiMenu.kt` (new `show`)
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSession.kt` (dispatch)

**Interfaces:**
- Produces: `suspend fun UiMenu.show(path: String, frame: JFrame, input: UiInput, timeoutMs: Long): String`

- [ ] **Step 1: Implement.** Resolve the segments with `pick` as `step` does, so the error messages are the same. By mode: `Outside` fails `"the main menu is <where>; a picture shows only the IDE's windows"`; `Bar` or `Merged` with the top menu shown clicks that `JMenu` in the showing `JMenuBar` through `UiInput`; `Hamburger` or folded clicks the Main Menu button (the showing component whose class simple name is `MainMenuButton`'s button, verify live) and then the top menu's item in its popup. Each further segment hovers the `JMenuItem` with that text in the last opened `JPopupMenu`, waiting up to the timeout for the submenu popup to show. The last segment's menu stays open. Report `opened View > Appearance; it shows N items`.
- [ ] **Step 2: Live test** in the 262 sandbox in merged and hamburger modes: `menu` `show` `View > Appearance`, then a screenshot shows both menus in the picture (Task 2's popups), then `close` closes them.
- [ ] **Step 3: Commit** `feat(ui): open a main menu path and leave it open for a picture`

### Task 7: Themes

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiThemes.kt`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiConfig.kt` (get/set dispatch)
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiThemesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object UiThemes {
      data class Theme(val id: String, val name: String, val dark: Boolean, val plugin: String?, val current: Boolean)
      const val SYNC = "sync"   // the restore value when the IDE followed the OS theme
      fun list(): List<Theme>                                              // EDT
      fun pick(themes: List<Theme>, wanted: String): Theme                  // by name without case, else id; UiStepFailure lists names
      fun render(themes: List<Theme>): String
      suspend fun set(wanted: String, timeoutMs: Long): UiConfig.Outcome     // switches, waits, gives the restore
  }
  ```

- [ ] **Step 1: Failing test** for `pick` and `render`:

```kotlin
class UiThemesTest {
    private val themes = listOf(
        UiThemes.Theme("ExperimentalLight", "Light", false, null, false),
        UiThemes.Theme("ExperimentalDark", "Dark", true, null, true),
        UiThemes.Theme("com.x.dracula", "Dracula Pro", true, "Dracula Theme", false),
    )
    @Test fun `a theme is picked by name without case, or by id`() {
        assertEquals("ExperimentalLight", UiThemes.pick(themes, "light").id)
        assertEquals("com.x.dracula", UiThemes.pick(themes, "com.x.dracula").id)
    }
    @Test fun `an unknown theme lists the installed names`() {
        val e = assertThrows<UiStepFailure> { UiThemes.pick(themes, "Solarized") }
        assertContains(e.message!!, "Light, Dark, Dracula Pro")
    }
    @Test fun `the list marks the current theme and names the plugin`() {
        val text = UiThemes.render(themes)
        assertContains(text, "Dark (dark, id ExperimentalDark) [current]")
        assertContains(text, "Dracula Pro (dark, id com.x.dracula, plugin Dracula Theme)")
    }
}
```

- [ ] **Step 2: Run to fail.**
- [ ] **Step 3: Implement.**
  - `list`: `LafManager.getInstance().installedThemes` (a `Sequence<UIThemeLookAndFeelInfo>`), `current` by id against `currentUIThemeLookAndFeel`, plugin name through the theme's provider class loader when it is a `PluginAwareClassLoader` (reflective and optional; null on any failure).
  - `set`: pick; refuse `isRestartRequired` themes with `"<name> needs an IDE restart, which a step does not do"`; when the theme is current and syncing is off, return `"the theme is <name> already"` with no restore. Remember the before state: syncing with the OS (`LafManager.autodetect`) gives restore value `SYNC`, else the before theme's id. On EDT turn off `autodetect` if on, then `QuickChangeLookAndFeel.switchLafAndUpdateUI(lafManager, info, false)`.
  - Settle: poll until `currentUIThemeLookAndFeel.id == info.id`; then `UiSettle.barrier()` and wait until every showing window has repainted: install a one-shot `RepaintManager` check by calling `window.repaint()` then waiting for `UiSettle.settle(quietMs = 500, maxMs = timeoutMs)`; fail with `"the theme did not finish switching to <name> within N ms"` on timeout.
  - `set("sync")` turns autodetect back on (`lafManager.autodetect = true; lafManager.updateUI()`) and waits the same way.
  - Restore step: `UiRestore.step("set", "theme" to beforeIdOrSync)`.
  - `UiConfig.get` with `step.themes` → `render(list())`; `UiConfig.set` with `step.theme` → `UiThemes.set(theme, step.timeoutMs.coerceAtLeast(30_000))`. The step's default timeout stays 5 s for other sets, so the theme set raises its own wait to 30 s unless the step gives more.
- [ ] **Step 4: Tests pass; live** on 262 and 263: `get themes`; `set theme Dark`, then a screenshot shows dark colours (read the PNG); the restore puts Light back; with Settings open the switch still applies (Review Focus 4); with "Sync with OS" on, the restore turns it back on.
- [ ] **Step 5: Commit** `feat(ui): list the installed themes and switch to one, waiting for the repaint`

### Task 8: `restore` for a call with steps

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiTool.kt` (param, `UiParams.restore`)
- Modify: the golden schema resource of `DevrigToolSpecsGoldenSchemaTest`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiToolHandler.kt`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSession.kt` (`closeOpenedSince`)

**Interfaces:**
- Produces: `UiParams.restore: Boolean = false`; `suspend fun UiSession.closeOpenedSince(before: Set<Window>): List<String>` (report lines, newest window first, soft).

- [ ] **Step 1: Implement.** Add the `restore` boolean parameter (description: "With steps: afterwards close the windows the call opened and put back what its steps changed, as a scenario replay does. Default false."). In `handleUi`, reject `restore` with `scenario` (`"a scenario replay restores by itself; drop restore"`). Before the steps capture `UiSettle.showingWindows()`. After the steps, when `params.restore`: close menus first (`MenuSelectionManager.clearSelectedPath()`), then each window opened since, newest first, through the close logic factored out of `closeStep` into `closeWindow(window): String`; then run `result.undo` as `restore step`s exactly as the scenario branch does. Reports go to the text as `restore step` lines, and the `undo:` line is not printed.
- [ ] **Step 2: Update the golden schema** and run `./gradlew :mcp-steroid-server:test --tests "*DevrigToolSpecsGoldenSchemaTest*" --tests "*UiTool*"`; expected PASS after updating the golden file.
- [ ] **Step 3: Live test:** a call `[settings, set option, screenshot]` with `restore: true` leaves Settings closed and the option as before; a call whose second step fails with a menu open (`menu` `show` then a failing `click`) still closes the menu and restores (Review Focus 5).
- [ ] **Step 4: Commit** `feat(ui): restore, to leave the IDE as a call with steps found it`

### Task 9: Split Mode host-page highlights

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSession.kt`

**Interfaces:**
- Consumes: `forward` (existing), `scrollStep` report.
- Produces: the scroll report ends with `; screen x,y WxH` (all sides); `UiSession.backendBounds(target, row, index): Rectangle` parses it.

- [ ] **Step 1: Implement.** Append the target's (or row's) screen bounds to every `scroll` report. In `screenshotStep`, when a highlight or a control crop matches nothing locally and `forward != null`, forward `{"action":"scroll", locator, "align":"center", "side":"backend"}` and read the bounds from its report; a report without bounds fails the step with the backend's line.
- [ ] **Step 2: Live test** on 263 Split Mode: a host page such as Editor > General (Host), highlight an option; the outline sits on it in the client's picture.
- [ ] **Step 3: Commit** `feat(split): highlight controls of a host Settings page from the backend`

### Task 10: JetDesk `capture.js` and the provenance picture

**Files (support-toolkit repo):**
- Create: `scripts/steroid/screenshot/capture.js`, `scripts/steroid/screenshot/capture.test.js`
- Modify: `scripts/steroid/settings-path/verify.js` (`--screenshot`)

**Interfaces:**
- Produces: `buildSteps(opts): object[]` and `parseArgs(argv)` exported for tests; CLI as in the spec.

- [ ] **Step 1: Failing tests** in `capture.test.js` (the repo's `node:test` style):

```js
test('settings with a leaf label and breadcrumb', () => {
  const steps = buildSteps({ settings: 'editor.preferences.appearance', highlight: ['Show line numbers'], breadcrumb: true, out: 'C:/p/a.png' });
  assert.deepEqual(steps, [
    { action: 'settings', page: 'editor.preferences.appearance' },
    { action: 'screenshot', out: 'C:/p/a.png', highlight: ['breadcrumb', { name: 'Show line numbers' }] },
  ]);
});
test('framing: size, theme, scroll', () => {
  const steps = buildSteps({ settings: 'Editor > General', size: '1400x900', theme: 'Light', scrollTo: 'Show line numbers', align: 'top', out: 'C:/p/b.png' });
  assert.deepEqual(steps.map(s => s.action), ['set', 'settings', 'window', 'scroll', 'screenshot']);
  assert.deepEqual(steps[2], { action: 'window', width: '1400', height: '900' });
});
test('menu and action', () => {
  assert.deepEqual(buildSteps({ menu: 'View > Appearance', out: 'C:/p/m.png' })[0], { action: 'menu', path: 'View > Appearance', show: true });
  assert.deepEqual(buildSteps({ action: 'ShowProjectStructureSettings', out: 'C:/p/d.png' })[0], { action: 'run', id: 'ShowProjectStructureSettings' });
});
test('bad arguments', () => {
  assert.throws(() => parseArgs(['--settings', 'a', '--menu', 'b']), /one of/);
  assert.throws(() => parseArgs(['--size', '14x']), /WxH/);
});
```

- [ ] **Step 2: Run to fail:** `tools/node.cmd scripts/run-tests.js capture`.
- [ ] **Step 3: Implement.** `parseArgs` per the spec's flag table, exactly one of `--settings`, `--menu`, `--action` unless `--themes`; `--out` default `workspace/screenshots/<slug>.png` made absolute. The theme step comes first so every later step draws in it. Call `bridge.discoverIde`, then `bridge.callTool(ide, 'steroid_ui', { project_name, task_id: 'capture', reason, steps: JSON.stringify(steps), restore: true, snapshot: 'none' })`, print the text, exit 0 when the result is not an error and names the saved path, 1 on a failed step, 2 on arguments or no IDE. `--themes` sends one `get themes` step and prints it. A leaf label for `--settings` that is not a page id or path is resolved through `verify.js`'s resolver (export its resolve function) to the page, and the leaf is highlighted.
  - `verify.js --screenshot <file>`: after a match, call `capture.js`'s runner with the first breadcrumb's page, `breadcrumb: true` and the leaf highlighted.
- [ ] **Step 4: Tests pass; live** against the 262 sandbox port 6321 (`--port 6321`): a provenance picture of `Show line numbers`, a menu, a theme picture; the IDE is as before afterwards.
- [ ] **Step 5: Commit** in the toolkit: `feat(steroid): capture a framed, highlighted IDE picture in one command`

### Task 11: Documentation and the docs-scenario check

**Files:**
- Modify (fork): `prompts/src/main/prompts/ide/ui-scenarios.md`, `prompts/src/main/prompts/ide/ui-driving.md`, `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiTool.kt` (description)
- Create (fork): `docs/examples/capture/appearance.scenario.json` only if the fork has an examples folder; else the example lives in `ui-scenarios.md`
- Modify (toolkit): `.claude/docs/steroid-ui-driving.md`, `.claude/docs/provenance-settings-paths.md`

- [ ] **Step 1: Write the docs** per the spec's Documentation section: the capture recipe as one call, `restore`, highlight/crop/out, frame the picture (window, scroll align, menu show, theme with its wait), docs screenshots (a scenario with `setup` size and theme, relative `out`, replay a folder, `unchanged`/`changed` lines), Split Mode notes (the client saves the file on its machine). Update the `steroid_ui` description lines for `screenshot`, `scroll`, `menu`, `get`/`set` and the new `restore` parameter.
- [ ] **Step 2: Run** `./gradlew :prompts:test --tests "*MarkdownArticleContractTest*"` and `./gradlew :mcp-steroid-server:test --tests "*UiTool*" --tests "*DevrigToolSpecs*"`. Expected: PASS.
- [ ] **Step 3: Live docs check** on 262: write a docs scenario with two pictures into `workspace/capture/docs/`, replay it twice with `replay.sh`; the second replay reports `unchanged` for both. Replay the saved scenarios under `sandbox-ide` to confirm no regression.
- [ ] **Step 4: Commit** fork `docs(ui): capture recipe, framing, themes and docs screenshots`, toolkit `docs(steroid): capture command and provenance pictures`.
