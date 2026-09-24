IDE: Find and drive UI controls with XPath

Snapshot the IDE's Swing UI, find controls by name or painted text with XPath, then click, type and close dialogs, all in one steroid_execute_code call.

# When to use this recipe

Use it when a task needs the IDE's own UI: a dialog with no API, a tool window row, a settings page, a
popup. One script can open the window, find controls by what they show, act on them and verify the
result, instead of a screenshot and a `steroid_input` call per step.

Prefer an API when one exists. An action ID (`ActionManager.getInstance().getAction(id)`), a service or a
`DialogWrapper` method is shorter and never breaks on a layout change. See
[action discovery](mcp-steroid://ide/action-discovery) for finding action IDs.

The recipes use the UI model of the **Performance Testing** plugin (`com.jetbrains.performancePlugin`),
which JetBrains IDEs bundle for their own UI tests. It is internal API. If that plugin is disabled, the
`com.jetbrains.performancePlugin.*` imports do not resolve; walk the components with
`UIUtil.findComponentsOfType(root, JButton::class.java)` and `accessibleContext.accessibleName` instead.

## Snapshot and find

`XpathDataModelCreator` turns the showing component tree into a DOM, one `<div>` per component. Each
element has these attributes, and the live `Component` as user data under `"component"`:

| Attribute | Content |
|---|---|
| `class` | Simple class name: `ActionButton`, `JButton`, `SeTextField` |
| `javaclass` | Fully qualified class name |
| `classhierarchy` | Class and superclasses, space-separated: match with `contains(@classhierarchy,'javax.swing.JTree')` |
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

// RemoteDriverDataModelExtension registers every component with the JMX test driver. A normal IDE has no
// driver, and building the model fails with "Invoker is not registered" unless the extension is dropped.
fun uiModel(root: Component?): Document =
    XpathDataModelCreator().apply { elementProcessors.removeIf { it is RemoteDriverDataModelExtension } }.create(root)

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
window by its content: titles and window classes differ between versions. In 2026.2, Settings is a
non-modal frame titled `Settings – <project>`, not a `JDialog`.

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
    val model = XpathDataModelCreator().apply { elementProcessors.removeIf { it is RemoteDriverDataModelExtension } }
        .create(root)
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

The fixtures in the same plugin read rows through their cell renderers, so they return the text a row
shows. Give them a read-only AssertJ robot: their click methods drive `java.awt.Robot`, which moves the
user's real mouse.

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

`JListTextFixture(robot, list).contents()` and `JTableTextFixture(robot, table)` work the same way. To click
a row, take its bounds from `tree.getRowBounds(row)` or `list.getCellBounds(i, i)` and dispatch the click to
the tree or list at that point.

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
  `allowModalDialog()` first. Run dialog scripts with `modal=unleashed`.

# See also

- [Discover IDE actions at caret](mcp-steroid://ide/action-discovery)
- [Open Project (With Dialog Handling)](mcp-steroid://open-project/open-with-dialogs)
- [Execute code tool description](mcp-steroid://skill/execute-code-tool-description)
