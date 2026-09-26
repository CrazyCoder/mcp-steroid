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
fields and combo boxes, `text=...` for the text it paints (tabs, editor text), and `tip="..."`. A list,
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
each step for a reproduction, and pass `marks=true` to `steroid_take_screenshot` to see the refs on the
image.

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
Swing names and labels when it is disabled. The next sections use that model directly.

## Snapshot and find

`XpathDataModelCreator` turns the showing component tree into a DOM, one `<div>` per component. Each
element has these attributes, and the live `Component` as user data under `"component"`:

| Attribute | Content |
|---|---|
| `class` | Simple class name: `ActionButton`, `JButton`, `SeTextField` |
| `javaclass` | Fully qualified class name |
| `classhierarchy` | Superclasses up to `JComponent`, joined with ` -> `, without the class itself: `contains(@classhierarchy,'javax.swing.JTree') or @javaclass='javax.swing.JTree'` |
| `accessiblename` | Accessible name: button labels, tool window names, tree descriptions |
| `tooltiptext` | Tooltip, often a full file path or the action name |
| `visible_text` | Text the component paints: tree and list rows, tabs, editor text. Separate strings are joined with a double-pipe separator, which `uiSnapshot` below splits on |

Build the model and read the components on the EDT with `ModalityState.any()`, so it also works while a
dialog is open:

```kotlin
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.wm.WindowManager
import com.jetbrains.performancePlugin.remotedriver.RemoteDriverDataModelExtension
import com.jetbrains.performancePlugin.remotedriver.xpath.XpathDataModelCreator
import java.awt.Component
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

// RemoteDriverDataModelExtension, and in a Split Mode client the Remote Development extensions, call the JMX
// test driver. A normal IDE has no driver, and building the model fails with "Invoker is not registered"
// unless those extensions are dropped.
fun uiModel(root: Component?): Document =
    XpathDataModelCreator().apply {
        elementProcessors.removeIf { it is RemoteDriverDataModelExtension || it.isRemDevExtension }
    }.create(root)

fun uiElements(root: Component?, xpath: String): List<Element> {
    val nodes = XPathFactory.newInstance().newXPath().compile(xpath)
        .evaluate(uiModel(root), XPathConstants.NODESET) as NodeList
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

fun uiFind(root: Component?, xpath: String): List<Component> =
    uiElements(root, xpath).mapNotNull { it.getUserData("component") as? Component }

// One line per named component, with the centre in screen coordinates: `steroid_input` accepts
// them as `click:left@screen:<x>,<y>`. Unnamed wrappers with a single child are skipped.
fun uiSnapshot(root: Component?): String = buildString {
    fun walk(e: Element, depth: Int) {
        val kids = (0 until e.childNodes.length).map { e.childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.tagName == "div" }
        val name = e.getAttribute("accessiblename").trim()
        val tip = e.getAttribute("tooltiptext").trim()
        val texts = e.getAttribute("visible_text").split(" || ").map { it.trim() }.filter { it.isNotEmpty() }
        val named = name.isNotEmpty() || tip.isNotEmpty() || texts.isNotEmpty()
        if (!named && kids.size <= 1) {
            kids.forEach { walk(it, depth) }
            return
        }
        append("  ".repeat(depth)).append("- ").append(e.getAttribute("class"))
        if (name.isNotEmpty()) append(" '").append(name.take(60)).append("'")
        if (tip.isNotEmpty() && tip != name) append(" tip='").append(tip.take(60)).append("'")
        if (texts.isNotEmpty() && texts.joinToString(" ") != name) {
            append(" text=").append(texts.take(8).joinToString("|") { it.take(40) })
            if (texts.size > 8) append("|+").append(texts.size - 8)
        }
        val component = e.getUserData("component") as? Component
        if (named && component != null && component.isShowing) {
            val p = component.locationOnScreen
            append(" @").append(p.x + component.width / 2).append(",").append(p.y + component.height / 2)
        }
        appendLine()
        kids.forEach { walk(it, depth + 1) }
    }
    val top = uiModel(root).documentElement
    (0 until top.childNodes.length).map { top.childNodes.item(it) }
        .filterIsInstance<Element>().filter { it.tagName == "div" }.forEach { walk(it, 0) }
}

val frame = WindowManager.getInstance().getFrame(project) ?: error("No frame for ${project.name}")
withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    println(uiSnapshot(frame))
    val search = uiFind(frame, "//div[@class='ActionButton' and @accessiblename='Search Everywhere']")
    println("Search Everywhere button: ${search.map { it.javaClass.name }}")
}
```

Pass the smallest root that holds the target: a window, a tool window or a panel. The model paints every
component to read its text, so a whole Settings window takes seconds and a small dialog takes milliseconds.

## Drive a dialog in one call

Open the window with `invokeLater` and return from the lambda, then wait for it in the script. Find the new
window by its content, not by title or window class. Settings, for one, opens either as a modal
`JDialog` or as a non-modal frame titled `Settings – <project>`, depending on a user preference and the
current modality.

Send input with `Component.dispatchEvent` on the EDT. It reaches the component directly, so it works while
the IDE is in the background and never moves the user's mouse. Events posted to the event queue instead
are dropped for keys when no component owns the focus.

This example opens **Go to Line**, types a line number, presses OK and checks the caret:

```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.fileEditor.FileEditorManager
import com.jetbrains.performancePlugin.remotedriver.RemoteDriverDataModelExtension
import com.jetbrains.performancePlugin.remotedriver.xpath.XpathDataModelCreator
import java.awt.Component
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.text.JTextComponent
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlinx.coroutines.delay
import org.w3c.dom.Element
import org.w3c.dom.NodeList

fun uiFind(root: Component?, xpath: String): List<Component> {
    val model = XpathDataModelCreator().apply {
        elementProcessors.removeIf { it is RemoteDriverDataModelExtension || it.isRemDevExtension }
    }.create(root)
    val nodes = XPathFactory.newInstance().newXPath().compile(xpath).evaluate(model, XPathConstants.NODESET) as NodeList
    return (0 until nodes.length).mapNotNull { (nodes.item(it) as Element).getUserData("component") as? Component }
}

val anyModality = Dispatchers.EDT + ModalityState.any().asContextElement()

suspend fun click(target: Component) = withContext(anyModality) {
    val x = target.width / 2
    val y = target.height / 2
    for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
        val mask = if (id == MouseEvent.MOUSE_PRESSED) MouseEvent.BUTTON1_DOWN_MASK else 0
        target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), mask, x, y, 1, false, MouseEvent.BUTTON1))
    }
}

suspend fun type(target: Component, text: String) = withContext(anyModality) {
    for (ch in text) {
        target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch))
    }
}

suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 10_000, probe: suspend () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        probe()?.let { return it }
        delay(100)
    }
    error("Timed out after $timeoutMs ms waiting for $what")
}

val editor = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).selectedTextEditor }
    ?: error("Open a file in the editor first")
val before = Window.getWindows().filter { it.isShowing }.toSet()

allowModalDialog() // this script opens a modal dialog on purpose; run it with modal=unleashed
ApplicationManager.getApplication().invokeLater({
    val action = ActionManager.getInstance().getAction("GotoLine")
    ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, null, true)
}, ModalityState.nonModal())

val dialog = waitFor("Go to Line dialog") {
    withContext(anyModality) {
        Window.getWindows().firstOrNull { w ->
            w.isShowing && w !in before && uiFind(w, "//div[@class='JButton' and @accessiblename='OK']").isNotEmpty()
        }
    }
}
val field = withContext(anyModality) {
    uiFind(dialog, "//div[contains(@classhierarchy,'javax.swing.text.JTextComponent')]").first() as JTextComponent
}
withContext(anyModality) { field.selectAll() }
type(field, "3")
click(withContext(anyModality) { uiFind(dialog, "//div[@class='JButton' and @accessiblename='OK']").single() })
waitFor("dialog closed") { if (dialog.isShowing) null else true }
println("Caret line: " + withContext(Dispatchers.EDT) { editor.caretModel.logicalPosition.line + 1 })
```

Direct dispatch runs the component's own key bindings. It does not run IDE shortcuts, which the event queue
handles: invoke the action by ID instead of pressing its shortcut.

## Read tree, list and table rows

A `steroid_ui` snapshot lists the rows in view, and `select` takes any row by text or index. Read rows
from a script when you need all of them. The fixtures in the same plugin read rows through their cell
renderers, so they return the text a row shows. Give them a read-only AssertJ robot: their click methods
drive `java.awt.Robot`, which moves the user's real mouse.

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
table rows the same way. To click
a row, take its bounds from `tree.getRowBounds(row)` or `list.getCellBounds(i, i)` and dispatch the click to
the tree or list at that point.

A list or tree popup (`ListPopupImpl`, `TreePopupImpl`) picks its row on hover, not on the press: it ignores a
press on any row but the selected one, and it ignores the first mouse move it sees. Before the click, dispatch
two `MOUSE_MOVED` events to the list at different points, the second at the row. `steroid_input` clicks do
this for you.

## Close what you opened

Escape does not close a popup when the event reaches the component directly: popups handle it through the
event queue. Close popups and dialogs through their API, from any component inside them:

```kotlin
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.UIUtil
import java.awt.Window
import javax.swing.JComponent
import javax.swing.RootPaneContainer

val frame = WindowManager.getInstance().getFrame(project)
withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    for (window in Window.getWindows().filter { it.isShowing && it !== frame && it is RootPaneContainer }) {
        val inside = UIUtil.findComponentsOfType((window as RootPaneContainer).rootPane, JComponent::class.java)
            .lastOrNull() ?: continue
        val dialog = DialogWrapper.findInstance(inside)
        val popup = PopupUtil.getPopupContainerFor(inside)
        when {
            dialog != null -> dialog.doCancelAction()
            popup != null -> popup.cancel()
        }
        println("${window.javaClass.simpleName}: closed=${dialog != null || popup != null}")
    }
}
```

## Pitfalls

- `IdeRobot` and its event-posting `InputEventsRobot` exist only in 2026.3 and later. Earlier builds
  have `SmoothRobot` alone, which moves the real mouse. Keep to `dispatchEvent` for input.
- Match on `accessiblename`, `visible_text` or `classhierarchy`, not on the position of a child. Layouts
  change between versions; names change less often.
- Tool windows that are hidden are not in the model. Show one with
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
