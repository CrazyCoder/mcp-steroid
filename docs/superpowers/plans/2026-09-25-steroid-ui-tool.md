# steroid_ui Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `steroid_ui` MCP tool that snapshots IDE UI with refs, acts on it
by ref or locator, waits inside the IDE and reports each action's effect,
with no Kotlin compilation; plus `modal=dialog`, `ui.*` script helpers,
snapshot-based screenshot metadata and opt-in traces.

**Architecture:** New package `com.jonnyzzz.mcpSteroid.ui` in `ij-plugin`
builds a `UiNode` tree from the Performance Testing plugin's
`XpathDataModelCreator` (loaded through that plugin's class loaders, with a
Swing walker as fallback), formats it with refs from an app-level weak
registry, and runs steps through shared input code that posts events and
awaits a barrier. The tool spec lives in `mcp-steroid-server` with the other
specs; devrig forwards the call.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.1+ (sinceBuild 261), kotlinx
coroutines and serialization, JUnit 4 (`ij-plugin`), JUnit 5
(`mcp-steroid-server`), Docker integration tests (`test-integration`).

**Spec:** `docs/superpowers/specs/2026-09-25-steroid-ui-tool-design.md`

## Global Constraints

- Plugin `sinceBuild` 261. Every API used must exist in IU-261.22158.277 (the
  `runIde` sandbox IDE, `.intellijPlatform/ides/IU-2026.1`).
- No dependency on `intellij.performanceTesting.remoteDriver`: it has
  `visibility="internal"`. Load its classes through the plugin's class
  loaders by name. In 2026.1 the classes are in the main
  `performanceTesting.jar`; in 2026.2+ in the content module.
- Repo bans (root `CLAUDE.md`): no `internal` modifier, no
  `runCatching{}.onFailure{}`, no empty `catch`, no `@Suppress("DEPRECATION")`,
  no `@ParameterizedTest`, no hardcoded `mcp-steroid://` literals in
  production Kotlin, no Java latches in coroutine code.
- Never run `./gradlew test` at the root. Never run two `:ij-plugin:test`
  tasks at once. Never run a bare `:prompts:test` (downloads 20+ GB); use the
  filtered form from `meta/user.md`.
- Commit to `main` in the fork, conventional commits, no AI attribution.
- Live tests only in the `runIde` sandbox (2026.1) or other sandboxes. Never
  in the user's own IDEs (2026.2.x daily IDE, the 2026.3 EAP monorepo IDE).
- `ModalityState.any()` only for pure UI reads and input. Model or PSI work
  runs under the target window's modality (`ModalityState.stateForComponent`).

## Review Focus

1. A remote-driver extension that throws while painting text for one
   component must not fail the snapshot: fall back to the walker and say
   why. Test in Task 1.
2. A huge `visible_text` (an editor with a large file) must not blow up the
   response: per-node text is cut and `max_nodes` caps the tree. Test in
   Task 2.
3. A heavyweight popup or modal dialog of the project must be part of the
   default snapshot, topmost first. Test in Task 3 (window ordering helper).
4. A ref whose window closed must fail as stale, not act on a hidden
   component. Test in Task 2.
5. A click that opens a modal dialog must return. Test in Task 7
   (integration) and in the live checks.

---

## Phase 1: the thin slice (snapshot only), then a live check

Proves: the remote-driver model loads from Steroid plugin code on 2026.1,
the snapshot format is useful, the tool is wired through the in-IDE server,
Split Mode routing and devrig. Stop after Task 4 and adjust the rest of the
plan to what the live check found.

### Task 1: UiNode and the model builders

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiNode.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiComponentFacts.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/RemoteDriverModel.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/FallbackUiWalker.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiModel.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/FallbackUiWalkerTest.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiModelTest.kt`

**Interfaces:**
- Produces:
  - `data class UiNode(val component: Component, val className: String, val name: String?, val text: List<String>, val tooltip: String?, val value: String?, val states: Set<UiState>, val interactive: Boolean, val children: List<UiNode>)`
  - `enum class UiState(val label: String)`: `DISABLED, CHECKED, SELECTED, FOCUSED, EXPANDED, EDITABLE, DEFAULT`
  - `object UiComponentFacts { fun states(c: Component): Set<UiState>; fun value(c: Component): String?; fun interactive(c: Component): Boolean; fun ownText(c: Component): String?; fun simpleClassName(c: Component): String }`
  - `class FallbackUiWalker(private val onlyShowing: Boolean = true) { fun build(root: Component): UiNode }`
  - `object RemoteDriverModel { fun unavailableReason(): String?; fun build(root: Component): UiNode }` (EDT only; throws on failure)
  - `data class UiModelResult(val root: UiNode, val source: String, val note: String?)`
  - `object UiModel { fun build(root: Component, onlyShowing: Boolean = true): UiModelResult }` (EDT only)

- [ ] **Step 1: Write the failing walker test**

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class FallbackUiWalkerTest {
    private fun sample(): JPanel = JPanel().apply {
        add(JLabel("Font size:"))
        add(JTextField("13").apply { accessibleContext.accessibleName = "Font size" })
        add(JCheckBox("Show tool window bars", true))
        add(JButton("Apply").apply { isEnabled = false })
    }

    @Test
    fun `the walker reads names, text, values and states`() {
        val root = FallbackUiWalker(onlyShowing = false).build(sample())
        val kids = root.children
        assertEquals(listOf("JLabel", "JTextField", "JCheckBox", "JButton"), kids.map { it.className })
        assertEquals(listOf("Font size:"), kids[0].text)
        assertEquals("Font size", kids[1].name)
        assertEquals("13", kids[1].value)
        assertTrue(UiState.EDITABLE in kids[1].states)
        assertTrue(UiState.CHECKED in kids[2].states)
        assertTrue(UiState.DISABLED in kids[3].states)
        assertTrue(kids[3].interactive)
        assertEquals(false, kids[0].interactive)
    }

    @Test
    fun `hidden children are skipped when only showing components are wanted`() {
        val panel = sample()
        panel.getComponent(0).isVisible = false
        val root = FallbackUiWalker(onlyShowing = false).build(panel)
        assertEquals(4, root.children.size)
        val visibleOnly = FallbackUiWalker(onlyShowing = false, skipInvisible = true).build(panel)
        assertEquals(3, visibleOnly.children.size)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.FallbackUiWalkerTest'`
Expected: compilation failure, `FallbackUiWalker` unresolved.

- [ ] **Step 3: Implement UiNode, UiComponentFacts and FallbackUiWalker**

`UiNode.kt`:

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Component

/** One component of a UI snapshot, reduced to what an agent reads and what a step needs to find it. */
data class UiNode(
    val component: Component,
    val className: String,
    val name: String?,
    val text: List<String>,
    val tooltip: String?,
    val value: String?,
    val states: Set<UiState>,
    val interactive: Boolean,
    val children: List<UiNode>,
) {
    /** Listed in a snapshot: it shows something, or an agent can act on it. */
    val listed: Boolean get() = interactive || !name.isNullOrBlank() || text.isNotEmpty() || !tooltip.isNullOrBlank()

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.walk()) }
    }
}

enum class UiState(val label: String) {
    DISABLED("disabled"), CHECKED("checked"), SELECTED("selected"), FOCUSED("focused"),
    EXPANDED("expanded"), EDITABLE("editable"), DEFAULT("default"),
}
```

`UiComponentFacts.kt`:

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JToggleButton
import javax.swing.JTree
import javax.swing.text.JTextComponent

/** Facts read from a live component. Call on the EDT. */
object UiComponentFacts {
    private const val MAX_VALUE = 80

    /** Classes whose name marks them as clickable although they are not Swing buttons. */
    private val CLICKABLE_CLASS_NAMES = listOf("ActionButton", "TabLabel", "LinkLabel", "ActionLink", "HyperlinkLabel")

    fun simpleClassName(c: Component): String {
        val type = if (c.javaClass.isAnonymousClass) c.javaClass.superclass else c.javaClass
        return type.name.substringAfterLast('.').substringAfterLast('$')
    }

    fun interactive(c: Component): Boolean = when (c) {
        is AbstractButton, is JTextComponent, is JList<*>, is JTree, is JTable, is JComboBox<*>, is JSlider, is JSpinner -> true
        else -> generateSequence<Class<*>>(c.javaClass) { it.superclass }
            .any { type -> CLICKABLE_CLASS_NAMES.any { type.simpleName == it } }
    }

    fun ownText(c: Component): String? {
        val raw = when (c) {
            is JLabel -> c.text
            is AbstractButton -> c.text
            else -> null
        } ?: return null
        return clean(raw).takeIf { it.isNotEmpty() }
    }

    fun value(c: Component): String? = when (c) {
        is JTextComponent -> clean(c.text).take(MAX_VALUE)
        is JComboBox<*> -> c.selectedItem?.toString()?.let { clean(it).take(MAX_VALUE) }
        else -> null
    }

    fun states(c: Component): Set<UiState> = buildSet {
        if (!c.isEnabled) add(UiState.DISABLED)
        if (c is JToggleButton && c.isSelected) add(UiState.CHECKED)
        if (c is JTextComponent && c.isEditable) add(UiState.EDITABLE)
        if (c is JButton && c.isDefaultButton) add(UiState.DEFAULT)
        if (c === KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner) add(UiState.FOCUSED)
    }

    fun name(c: Component): String? = c.accessibleContext?.accessibleName?.let(::clean)?.takeIf { it.isNotEmpty() }

    fun tooltip(c: Component): String? = (c as? JComponent)?.toolTipText?.let(::clean)?.takeIf { it.isNotEmpty() }

    /** HTML tags removed, whitespace collapsed. */
    fun clean(raw: String): String = raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}
```

`FallbackUiWalker.kt`:

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Component
import java.awt.Container

/**
 * Builds the tree from Swing alone, for when the Performance Testing plugin's model is not available.
 * It reads accessible names, the text of labels and buttons, and tooltips, but not painted text such as
 * tree and list rows. Call on the EDT.
 */
class FallbackUiWalker(
    private val onlyShowing: Boolean = true,
    private val skipInvisible: Boolean = onlyShowing,
) {
    fun build(root: Component): UiNode = node(root, depth = 0)

    private fun node(c: Component, depth: Int): UiNode {
        val kids = if (c is Container && depth < MAX_DEPTH) {
            c.components.filter { include(it) }.map { node(it, depth + 1) }
        } else emptyList()
        return UiNode(
            component = c,
            className = UiComponentFacts.simpleClassName(c),
            name = UiComponentFacts.name(c),
            text = listOfNotNull(UiComponentFacts.ownText(c)),
            tooltip = UiComponentFacts.tooltip(c),
            value = UiComponentFacts.value(c),
            states = UiComponentFacts.states(c),
            interactive = UiComponentFacts.interactive(c),
            children = kids,
        )
    }

    private fun include(c: Component): Boolean = when {
        onlyShowing -> c.isShowing
        skipInvisible -> c.isVisible
        else -> true
    }

    companion object {
        private const val MAX_DEPTH = 64
    }
}
```

- [ ] **Step 4: Run the walker test to see it pass**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.FallbackUiWalkerTest'`
Expected: 2 tests PASS.

- [ ] **Step 5: Write the failing UiModel test**

The model falls back when the remote-driver build throws (Review Focus 1).
`UiModel` takes its remote builder as a parameter so the test can inject a
failing one.

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton
import javax.swing.JPanel

class UiModelTest {
    private val panel = JPanel().apply { add(JButton("OK")) }

    @Test
    fun `a failing remote-driver build falls back to the walker and says why`() {
        val result = UiModel.build(panel, onlyShowing = false, remote = { error("paint failed") }, remoteUnavailable = { null })
        assertEquals("swing", result.source)
        assertTrue(result.note!!.contains("paint failed"))
        assertEquals(listOf("JButton"), result.root.children.map { it.className })
    }

    @Test
    fun `an unavailable remote driver is named in the note`() {
        val result = UiModel.build(panel, onlyShowing = false, remote = { error("unused") }, remoteUnavailable = { "plugin disabled" })
        assertEquals("swing", result.source)
        assertEquals("remote driver unavailable: plugin disabled", result.note)
    }

    @Test
    fun `a working remote-driver build is used`() {
        val fake = FallbackUiWalker(onlyShowing = false).build(panel)
        val result = UiModel.build(panel, onlyShowing = false, remote = { fake }, remoteUnavailable = { null })
        assertEquals("remote-driver", result.source)
        assertEquals(null, result.note)
    }
}
```

- [ ] **Step 6: Run it to see it fail**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.UiModelTest'`
Expected: compilation failure, `UiModel` unresolved.

- [ ] **Step 7: Implement RemoteDriverModel and UiModel**

`RemoteDriverModel.kt`:

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.contentModules
import com.intellij.openapi.extensions.PluginId
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.awt.Component

/**
 * The Performance Testing plugin's UI model (`XpathDataModelCreator`), which adds the text a component
 * paints (tree and list rows, tabs, editor text) to what Swing reports.
 *
 * Its module has `visibility="internal"`, so Steroid cannot declare a dependency on it. The class is
 * loaded by name from the plugin's own class loaders: the main one (2026.1, where it is in
 * `performanceTesting.jar`) or a content module's (2026.2 and later). Three reflective calls: the
 * constructor, `getElementProcessors()` and `create(Component, Boolean, Component?)`.
 */
object RemoteDriverModel {
    private const val PLUGIN_ID = "com.jetbrains.performancePlugin"
    private const val CREATOR = "com.jetbrains.performancePlugin.remotedriver.xpath.XpathDataModelCreator"
    private const val DRIVER_EXTENSION = "com.jetbrains.performancePlugin.remotedriver.RemoteDriverDataModelExtension"
    private const val SEPARATOR = " || "

    /** Why the model cannot be used, or null when it can. */
    fun unavailableReason(): String? = try {
        creatorClass()
        null
    } catch (e: ClassNotFoundException) {
        e.message
    }

    private fun creatorClass(): Class<*> {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
            ?: throw ClassNotFoundException("the Performance Testing plugin is not installed")
        if (!PluginManagerCore.isLoaded(plugin.pluginId)) throw ClassNotFoundException("the Performance Testing plugin is disabled")
        val loaders = sequenceOf(plugin.pluginClassLoader) + plugin.contentModules.asSequence().map { it.pluginClassLoader }
        for (loader in loaders.filterNotNull()) {
            try {
                return Class.forName(CREATOR, false, loader)
            } catch (e: ClassNotFoundException) {
                continue
            }
        }
        throw ClassNotFoundException("$CREATOR is not in the Performance Testing plugin")
    }

    /** Builds the model of [root] and its showing descendants. Call on the EDT. */
    fun build(root: Component): UiNode {
        val type = creatorClass()
        val creator = type.getConstructor().newInstance()
        // RemoteDriverDataModelExtension and the Remote Development extensions call the JMX test driver,
        // which a normal IDE does not run: with them the build fails with "Invoker is not registered".
        @Suppress("UNCHECKED_CAST")
        val processors = type.getMethod("getElementProcessors").invoke(creator) as MutableList<Any>
        processors.removeIf { p ->
            p.javaClass.name == DRIVER_EXTENSION ||
                (p.javaClass.getMethod("isRemDevExtension").invoke(p) as Boolean)
        }
        val document = type.getMethod("create", Component::class.java, Boolean::class.javaPrimitiveType, Component::class.java)
            .invoke(creator, root, true, null) as Document
        val top = document.documentElement.childElements().firstOrNull()
            ?: error("the UI model of ${root.javaClass.name} is empty")
        return toNode(top)
    }

    private fun toNode(e: Element): UiNode {
        val component = e.getUserData("component") as? Component
            ?: error("a UI model element carries no component")
        return UiNode(
            component = component,
            className = e.getAttribute("class").ifEmpty { UiComponentFacts.simpleClassName(component) },
            name = e.getAttribute("accessiblename").let(UiComponentFacts::clean).takeIf { it.isNotEmpty() },
            text = e.getAttribute("visible_text").split(SEPARATOR).map(UiComponentFacts::clean).filter { it.isNotEmpty() },
            tooltip = e.getAttribute("tooltiptext").let(UiComponentFacts::clean).takeIf { it.isNotEmpty() },
            value = UiComponentFacts.value(component),
            states = UiComponentFacts.states(component),
            interactive = UiComponentFacts.interactive(component),
            children = e.childElements().filter { it.tagName == "div" }.map(::toNode).toList(),
        )
    }

    private fun Element.childElements(): Sequence<Element> =
        (0 until childNodes.length).asSequence().map { childNodes.item(it) }.filterIsInstance<Element>()
}
```

Note: `@Suppress("UNCHECKED_CAST")` is allowed; only the DEPRECATION
suppression is banned.

`UiModel.kt`:

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.diagnostic.logger
import java.awt.Component

data class UiModelResult(val root: UiNode, val source: String, val note: String?)

/** Builds the snapshot tree of a root component. Call on the EDT. */
object UiModel {
    private val log = logger<UiModel>()

    fun build(
        root: Component,
        onlyShowing: Boolean = true,
        remote: (Component) -> UiNode = RemoteDriverModel::build,
        remoteUnavailable: () -> String? = RemoteDriverModel::unavailableReason,
    ): UiModelResult {
        val unavailable = remoteUnavailable()
        if (unavailable != null) {
            return UiModelResult(FallbackUiWalker(onlyShowing).build(root), SOURCE_SWING, "remote driver unavailable: $unavailable")
        }
        return try {
            UiModelResult(remote(root), SOURCE_REMOTE_DRIVER, null)
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log.warn("The remote-driver UI model failed; using the Swing walker", cause)
            UiModelResult(FallbackUiWalker(onlyShowing).build(root), SOURCE_SWING, "remote driver failed: ${cause.message}")
        }
    }

    const val SOURCE_REMOTE_DRIVER = "remote-driver"
    const val SOURCE_SWING = "swing"
}
```

- [ ] **Step 8: Run both tests to see them pass**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.*'`
Expected: 5 tests PASS.

- [ ] **Step 9: Commit**

```bash
git add ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui
git commit -m "feat(ui): build a UI tree from the remote-driver model or Swing"
```

### Task 2: refs and the snapshot text

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiRefRegistry.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSnapshotFormatter.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiRefRegistryTest.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSnapshotFormatterTest.kt`

**Interfaces:**
- Consumes: `UiNode`, `UiState`.
- Produces:
  - `class UiRefRegistry(private val capacity: Int = 5000) { fun refFor(c: Component): String; fun resolve(ref: String): UiRefResolution }`, plus `@Service(APP) class UiRefs { val registry: UiRefRegistry }`
  - `sealed interface UiRefResolution { data class Live(val component: Component); data class Stale(val ref: String); data class Unknown(val ref: String) }`
  - `data class UiWindowHeader(val windowId: String, val title: String?, val kind: String, val modal: Boolean, val source: String, val note: String?)`
  - `object UiSnapshotFormatter { fun format(header: UiWindowHeader, root: UiNode, refOf: (UiNode) -> String, maxNodes: Int, withBounds: Boolean): UiSnapshotText }`
  - `data class UiSnapshotText(val text: String, val listedCount: Int, val cut: Int)`

- [ ] **Step 1: Write the failing registry test** (Review Focus 4)

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton

class UiRefRegistryTest {
    @Test
    fun `one component keeps one ref, another gets a new one`() {
        val registry = UiRefRegistry(showing = { true })
        val a = JButton("A")
        val b = JButton("B")
        assertEquals(registry.refFor(a), registry.refFor(a))
        assertNotEquals(registry.refFor(a), registry.refFor(b))
    }

    @Test
    fun `a ref resolves while its component shows and is stale after`() {
        var showing = true
        val registry = UiRefRegistry(showing = { showing })
        val a = JButton("A")
        val ref = registry.refFor(a)
        assertEquals(UiRefResolution.Live(a), registry.resolve(ref))
        showing = false
        assertEquals(UiRefResolution.Stale(ref), registry.resolve(ref))
    }

    @Test
    fun `an unknown ref is reported as unknown`() {
        assertEquals(UiRefResolution.Unknown("e999"), UiRefRegistry(showing = { true }).resolve("e999"))
    }

    @Test
    fun `the oldest refs are evicted past the capacity`() {
        val registry = UiRefRegistry(capacity = 2, showing = { true })
        val first = registry.refFor(JButton("1"))
        registry.refFor(JButton("2"))
        registry.refFor(JButton("3"))
        assertTrue(registry.resolve(first) is UiRefResolution.Unknown)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.UiRefRegistryTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the registry**

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import com.intellij.openapi.components.Service
import java.awt.Component
import java.lang.ref.WeakReference
import java.util.WeakHashMap

sealed interface UiRefResolution {
    data class Live(val component: Component) : UiRefResolution
    data class Stale(val ref: String) : UiRefResolution
    data class Unknown(val ref: String) : UiRefResolution
}

/**
 * Short refs (`e12`) for components listed in snapshots. Components are held weakly, so a ref never keeps
 * a closed window alive. A ref whose component is gone or no longer showing is stale. Thread-safe.
 */
class UiRefRegistry(
    private val capacity: Int = 5000,
    private val showing: (Component) -> Boolean = Component::isShowing,
) {
    private var next = 1
    private val byComponent = WeakHashMap<Component, String>()
    private val byRef = object : LinkedHashMap<String, WeakReference<Component>>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WeakReference<Component>>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun refFor(c: Component): String {
        byComponent[c]?.let { ref -> if (byRef.containsKey(ref)) return ref }
        val ref = "e${next++}"
        byComponent[c] = ref
        byRef[ref] = WeakReference(c)
        return ref
    }

    @Synchronized
    fun resolve(ref: String): UiRefResolution {
        val holder = byRef[ref] ?: return UiRefResolution.Unknown(ref)
        val c = holder.get() ?: return UiRefResolution.Stale(ref)
        return if (showing(c)) UiRefResolution.Live(c) else UiRefResolution.Stale(ref)
    }
}

@Service(Service.Level.APP)
class UiRefs {
    val registry = UiRefRegistry()
}
```

- [ ] **Step 4: Run the registry test to see it pass**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.UiRefRegistryTest'`
Expected: 4 tests PASS.

- [ ] **Step 5: Write the failing formatter test** (Review Focus 2)

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JButton
import javax.swing.JPanel

class UiSnapshotFormatterTest {
    private val header = UiWindowHeader("w-1", "Settings", "dialog", modal = true, source = "remote-driver", note = null)
    private val dummy = JPanel()

    private fun node(cls: String, name: String? = null, text: List<String> = emptyList(), value: String? = null,
                     states: Set<UiState> = emptySet(), interactive: Boolean = false, kids: List<UiNode> = emptyList()) =
        UiNode(dummy, cls, name, text, null, value, states, interactive, kids)

    @Test
    fun `listed components get a line with ref and states, wrappers with one child are skipped`() {
        val tree = node("JRootPane", kids = listOf(
            node("JPanel", kids = listOf(
                node("JBCheckBox", name = "Show tool window bars", states = setOf(UiState.CHECKED), interactive = true),
                node("JButton", name = "OK", states = setOf(UiState.DEFAULT), interactive = true),
            )),
        ))
        var n = 0
        val out = UiSnapshotFormatter.format(header, tree, { "e${++n}" }, maxNodes = 400, withBounds = false)
        assertEquals(
            """
            window w-1 "Settings" (dialog, modal) source=remote-driver
            - JPanel
              - JBCheckBox "Show tool window bars" [ref=e1] [checked]
              - JButton "OK" [ref=e2] [default]
            """.trimIndent(),
            out.text,
        )
        assertEquals(2, out.listedCount)
    }

    @Test
    fun `long text is cut and the node cap summarises the rest`() {
        val many = (1..10).map { node("JButton", name = "B$it", interactive = true) }
        val tree = node("JPanel", kids = listOf(node("JTextArea", value = "x".repeat(500), interactive = true), node("JPanel", kids = many)))
        val out = UiSnapshotFormatter.format(header, tree, { "e0" }, maxNodes = 5, withBounds = false)
        assertTrue(out.text, out.text.contains("value=\"" + "x".repeat(80) + "…\""))
        assertTrue(out.text, out.text.contains("… 6 more"))
        assertEquals(6, out.cut)
    }

    @Test
    fun `painted text beyond eight entries is counted, not listed`() {
        val tree = node("JPanel", kids = listOf(node("Tree", name = "Settings categories", text = (1..12).map { "row$it" }, interactive = true)))
        val out = UiSnapshotFormatter.format(header, tree, { "e7" }, maxNodes = 400, withBounds = false)
        assertTrue(out.text, out.text.contains("text=row1|row2|row3|row4|row5|row6|row7|row8|+4"))
    }
}
```

- [ ] **Step 6: Run it to see it fail**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.UiSnapshotFormatterTest'`
Expected: compilation failure.

- [ ] **Step 7: Implement the formatter**

```kotlin
package com.jonnyzzz.mcpSteroid.ui

data class UiWindowHeader(
    val windowId: String,
    val title: String?,
    val kind: String,
    val modal: Boolean,
    val source: String,
    val note: String?,
)

data class UiSnapshotText(val text: String, val listedCount: Int, val cut: Int)

/** The snapshot text: one line per listed component, indented by depth. */
object UiSnapshotFormatter {
    private const val MAX_TEXT = 80
    private const val MAX_TEXT_ENTRIES = 8
    private const val MAX_ENTRY = 40

    fun format(header: UiWindowHeader, root: UiNode, refOf: (UiNode) -> String, maxNodes: Int, withBounds: Boolean): UiSnapshotText {
        val out = StringBuilder()
        out.append("window ").append(header.windowId)
        header.title?.takeIf { it.isNotBlank() }?.let { out.append(" \"").append(it).append('"') }
        out.append(" (").append(header.kind).append(if (header.modal) ", modal" else "").append(')')
        out.append(" source=").append(header.source)
        header.note?.let { out.append("\nnote: ").append(it) }
        var listed = 0
        var cut = 0

        fun walk(node: UiNode, depth: Int) {
            if (!node.listed && node.children.size <= 1) {
                node.children.forEach { walk(it, depth) }
                return
            }
            if (listed >= maxNodes) {
                cut += node.walk().count { it.listed || it.children.size > 1 }
                return
            }
            listed++
            out.append('\n').append("  ".repeat(depth)).append("- ").append(line(node, refOf, withBounds))
            node.children.forEach { walk(it, depth + 1) }
        }

        root.children.forEach { walk(it, 0) }
        if (cut > 0) out.append("\n… ").append(cut).append(" more (raise max_nodes or pass a narrower root)")
        return UiSnapshotText(out.toString(), listed, cut)
    }

    private fun line(node: UiNode, refOf: (UiNode) -> String, withBounds: Boolean): String = buildString {
        append(node.className)
        node.name?.let { append(" \"").append(cut(it, MAX_TEXT)).append('"') }
        if (node.interactive || node.listed && (node.name != null || node.text.isNotEmpty())) {
            append(" [ref=").append(refOf(node)).append(']')
        }
        node.states.sortedBy { it.ordinal }.forEach { append(" [").append(it.label).append(']') }
        node.value?.let { append(" value=\"").append(cut(it, MAX_TEXT)).append('"') }
        val texts = node.text.filter { it != node.name }
        if (texts.isNotEmpty()) {
            append(" text=").append(texts.take(MAX_TEXT_ENTRIES).joinToString("|") { cut(it, MAX_ENTRY) })
            if (texts.size > MAX_TEXT_ENTRIES) append("|+").append(texts.size - MAX_TEXT_ENTRIES)
        }
        node.tooltip?.takeIf { it != node.name }?.let { append(" tip=\"").append(cut(it, MAX_TEXT)).append('"') }
        if (withBounds && node.component.isShowing) {
            val p = node.component.locationOnScreen
            append(" @").append(p.x).append(',').append(p.y).append(' ')
                .append(node.component.width).append('x').append(node.component.height)
        }
    }

    private fun cut(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "…"
}
```

Note the `JPanel` line in the expected output of the first test: an unnamed
node with two children stays as a structural line without a ref. The
`JRootPane` wrapper with one child is skipped.

- [ ] **Step 8: Run the formatter test to see it pass**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.*'`
Expected: all `ui` tests PASS (12).

- [ ] **Step 9: Commit**

```bash
git commit -m "feat(ui): weak refs and the snapshot text format" -- ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui
```

### Task 3: the steroid_ui tool, snapshot only

**Files:**
- Create: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiTool.kt`
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/UiToolSpecSchemaTest.kt`
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/McpSteroidTools.kt` (add `UiToolSpec { handler<UiToolHandler>() }` after `VisionInputToolSpec`)
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiWindows.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiToolHandler.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiWindowsTest.kt`
- Modify: `ij-plugin/src/main/resources/META-INF/plugin.xml` (service binding next to `VisionInputToolHandler`)
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/vision/VisionService.kt` (make `findComponentByWindowId` a public top-level function in `WindowIdUtil.kt`, used by both)
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/split/SplitRouting.kt` (`"steroid_ui" to Home.FRONTEND`)
- Modify: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/split/SplitRoutingTest.kt` (a `steroid_ui` routes-to-frontend case)
- Create: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/DevrigUiToolHandler.kt`
- Modify: `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig/server/StubMcpSteroidTools.kt`
- Modify: the tool lists in `DevrigToolSpecsTest.kt`, `DevrigToolSpecsGoldenSchemaTest.kt` (count 9 plus a golden for `steroid_ui`), `ToolSpecCliMetadataTest.kt`, `ExpectedSteroidTools.kt`, and `DevrigServerInstructions.kt` if it lists tools.

**Interfaces:**
- Consumes: `UiModel.build`, `UiSnapshotFormatter.format`, `UiRefs`, `UiWindowHeader`.
- Produces:
  - `class UiToolSpec(val handler: () -> UiToolHandler) : McpToolBase()`, name `steroid_ui`
  - `@Serializable data class UiParams(val taskId: String, val reason: String, val windowId: String? = null, val steps: String? = null, val snapshot: String? = null, val maxNodes: Int = 400, val trace: Boolean = false, @Transient val executionBackend: ExecutionBackendProvenance? = null)`
  - `interface UiToolHandler { suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult }`
  - `object UiWindows { fun projectWindows(frame: Window, all: List<Window>): List<Window> }` (topmost first, frame last)
  - `fun findComponentByWindowId(windowId: String): Component?` in `WindowIdUtil.kt`

Ruling (plan): `steps` is a string holding a JSON array, not a JSON-schema
array, so the devrig CLI takes it as `--steps='[…]'` like `--sequence`. In
Phase 1 the handler rejects a non-empty `steps` with "steps arrive in the
next build"; the parameter is declared now so the schema golden is written
once.

- [ ] **Step 1: Write the failing schema test**

```kotlin
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class UiToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = UiToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_ui")
        assertRequiredExactly(schema, "project_name", "task_id", "reason")
        assertStringProperty(schema, "project_name")
        assertStringProperty(schema, "window_id")
        assertStringProperty(schema, "steps")
        assertStringProperty(schema, "snapshot")
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :mcp-steroid-server:test --tests '*UiToolSpecSchemaTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement the spec**

```kotlin
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.boolean
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.int
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.mcp.withDefaultValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

class UiToolSpec(val handler: () -> UiToolHandler) : McpToolBase() {
    override val name = "steroid_ui"

    override val description = """
        Read and drive the IDE's UI by what it shows: dialogs, popups, tool windows, Settings pages.

        With no steps it returns a snapshot of the project's showing windows, topmost first: one line per
        control with its class, accessible name, state, value, painted text (tree and list rows, tabs) and a
        ref such as [ref=e12]. Refs stay valid while their control is showing.

        Pass window_id (from steroid_list_windows) to snapshot one window.
        Prefer this tool over screenshots to find controls. It compiles no code, so it answers fast.
    """.trimIndent()
    override val cliSynopsis = "snapshot and drive IDE UI by what it shows"

    val projectName = CommonToolParams.projectName().registerToSchema()
    val taskId = CommonToolParams.taskId().registerToSchema()
    val reason = CommonToolParams.reason().registerToSchema()
    val windowId = CommonToolParams.windowId().registerToSchema()

    val steps = InputSchemaElement.param("steps")
        .description("JSON array of steps to run in order. Omit for a snapshot only.")
        .cliSynopsis("JSON array of steps; omit for a snapshot")
        .string()
        .registerToSchema()

    val snapshot = InputSchemaElement.param("snapshot")
        .description("Snapshot in the response: 'full', 'diff' or 'none'. Default: 'full' with no steps, 'diff' with steps.")
        .cliSynopsis("full | diff | none")
        .string()
        .registerToSchema()

    val maxNodes = InputSchemaElement.param("max_nodes")
        .description("Most controls to list, default 400. The rest is counted.")
        .cliSynopsis("most controls to list (default 400)")
        .int()
        .withDefaultValue(400)
        .registerToSchema()

    val trace = InputSchemaElement.param("trace")
        .description("Record a trace (pictures before and after each step, snapshots, events) in the execution folder.")
        .cliSynopsis("record a trace in the execution folder")
        .boolean()
        .withDefaultValue(false)
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult =
        handler().handleUi(
            context[projectName],
            UiParams(
                taskId = context[taskId],
                reason = context[reason],
                windowId = context[windowId],
                steps = context[steps],
                snapshot = context[snapshot],
                maxNodes = context[maxNodes],
                trace = context[trace],
                executionBackend = context.executionBackendProvenance(),
            ),
        )
}

@Serializable
data class UiParams(
    val taskId: String,
    val reason: String,
    val windowId: String? = null,
    val steps: String? = null,
    val snapshot: String? = null,
    val maxNodes: Int = 400,
    val trace: Boolean = false,
    @Transient val executionBackend: ExecutionBackendProvenance? = null,
)

interface UiToolHandler {
    suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult
}
```

Check `boolean()` returns `Boolean?` so `withDefaultValue(false)` applies; if
the parser shape differs, follow the `int()` pattern in `McpSchema.kt`.

- [ ] **Step 4: Register and run the server tests**

Add `UiToolSpec { handler<UiToolHandler>() },` after
`VisionInputToolSpec { … }` in `commonToolSpecs()`. Add `"steroid_ui"` to the
tool lists in `DevrigToolSpecsTest`, `ToolSpecCliMetadataTest` (both maps get
`"steroid_ui" to emptyMap()` / `emptyList()` unless the test demands CLI
examples; follow what it asserts for `steroid_input`), and raise the count in
`DevrigToolSpecsGoldenSchemaTest` to 9 with a `GOLDEN_UI` constant captured
from `spec.asMcpJson()` as its KDoc describes.

Run: `./gradlew :mcp-steroid-server:test`
Expected: PASS.

- [ ] **Step 5: Write the failing window-order test** (Review Focus 3)

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiWindowsTest {
    private class W(val id: String, val owner: W?)

    @Test
    fun `owned windows come first, newest first, and the frame last`() {
        val frame = W("frame", null)
        val other = W("other-frame", null)
        val dialog = W("dialog", frame)
        val popup = W("popup", dialog)
        val stray = W("stray", other)
        val ordered = UiWindows.order(frame, listOf(frame, other, dialog, stray, popup), ownerOf = { it.owner })
        assertEquals(listOf("popup", "dialog", "frame"), ordered.map { it.id })
    }
}
```

- [ ] **Step 6: Run it to see it fail, then implement UiWindows**

Run: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.UiWindowsTest'`
Expected: compilation failure.

```kotlin
package com.jonnyzzz.mcpSteroid.ui

import java.awt.Window

/** Which windows belong to a project frame, in the order a snapshot lists them. */
object UiWindows {
    /** [all] in creation order (as `Window.getWindows()` returns them). Owned windows newest first, then the frame. */
    fun <W : Any> order(frame: W, all: List<W>, ownerOf: (W) -> W?): List<W> {
        val owned = all.filter { w -> w !== frame && generateSequence(ownerOf(w), ownerOf).any { it === frame } }
        return owned.reversed() + frame
    }

    /** Showing windows of [frame]: its dialogs and heavyweight popups, topmost first, then the frame. */
    fun projectWindows(frame: Window): List<Window> =
        order(frame, Window.getWindows().filter { it.isShowing }, Window::getOwner)
}
```

Run the test again. Expected: PASS.

- [ ] **Step 7: Implement the IJ handler**

Move `findComponentByWindowId` from `VisionService` to a public top-level
function in `WindowIdUtil.kt` (same body), and call it from both places.

`UiToolHandler.kt` (in `com.jonnyzzz.mcpSteroid.server`):

```kotlin
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.WindowManager
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.ui.UiModel
import com.jonnyzzz.mcpSteroid.ui.UiRefs
import com.jonnyzzz.mcpSteroid.ui.UiSnapshotFormatter
import com.jonnyzzz.mcpSteroid.ui.UiWindowHeader
import com.jonnyzzz.mcpSteroid.ui.UiWindows
import com.jonnyzzz.mcpSteroid.vision.WindowIdUtil
import com.jonnyzzz.mcpSteroid.vision.findComponentByWindowId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import javax.swing.SwingUtilities

class UiToolHandlerIJ : UiToolHandler {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult {
        val project = service<ProjectScopedToolHandler>().resolveProject(projectName)
        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_ui",
            arguments = json.encodeToJsonElement(params).jsonObject,
            taskId = params.taskId,
            executionBackend = params.executionBackend,
        )
        project.executionStorage.writeCodeExecutionData(executionId, "reason.txt", params.reason)
        val builder = ToolCallResult.builder()
        if (!params.steps.isNullOrBlank() && params.steps.trim() != "[]") {
            return builder.addTextContent("ERROR: steps are not supported by this build yet; call without steps for a snapshot.")
                .markAsError().build()
        }
        return try {
            val text = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                val windows = if (params.windowId != null) {
                    val c = findComponentByWindowId(params.windowId)
                        ?: error("No IDE window found for window_id: ${params.windowId}")
                    listOf(c as? Window ?: SwingUtilities.getWindowAncestor(c) ?: error("window_id ${params.windowId} is not in a window"))
                } else {
                    val frame = WindowManager.getInstance().getFrame(project) ?: error("No frame for project ${project.name}")
                    UiWindows.projectWindows(frame)
                }
                val registry = service<UiRefs>().registry
                windows.joinToString("\n\n") { window ->
                    val model = UiModel.build(window)
                    UiSnapshotFormatter.format(
                        header(window, model.source, model.note),
                        model.root.let { root -> if (root.component === window) root else root },
                        { registry.refFor(it.component) },
                        params.maxNodes,
                        withBounds = params.snapshot == "full",
                    ).text
                }
            }
            project.executionStorage.writeCodeExecutionData(executionId, "snapshot.txt", text)
            builder.addTextContent("execution_id: ${executionId.executionId}\n$text").build()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "steroid_ui failed: ${e.message}"
            project.executionStorage.writeCodeErrorEvent(executionId, message)
            builder.addTextContent("ERROR: $message").markAsError().build()
        }
    }

    private fun header(window: Window, source: String, note: String?) = UiWindowHeader(
        windowId = WindowIdUtil.compute(window, window),
        title = (window as? Frame)?.title ?: (window as? Dialog)?.title,
        kind = when (window) {
            is Frame -> "frame"
            is Dialog -> "dialog"
            else -> "popup"
        },
        modal = (window as? Dialog)?.isModal == true,
        source = source,
        note = note,
    )
}
```

Clean-up while implementing: drop the no-op `model.root.let { … }` and pass
`model.root`. `UiModel.build(window)` includes the root (`create(root, true,
null)`), and the formatter lists the root's children, so the window's
`JRootPane` wrapper line collapses naturally.

Window ids must match `steroid_list_windows`: frames use
`WindowIdUtil.compute(window, frame.component)` there. Use the same
component for frames (the frame's root component) so ids agree; check
`IdeWindowsCollector` and follow it.

Bind the service in `plugin.xml` next to `VisionInputToolHandler`:

```xml
<applicationService
    serviceInterface="com.jonnyzzz.mcpSteroid.server.UiToolHandler"
    serviceImplementation="com.jonnyzzz.mcpSteroid.server.UiToolHandlerIJ"/>
```

Add `"steroid_ui" to Home.FRONTEND` to `ROUTED_TOOLS`, and a
`SplitRoutingTest` case: `assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_ui", none))`.

- [ ] **Step 8: devrig forwarding**

`DevrigUiToolHandler.kt`, modelled on `DevrigVisionInputToolHandler`:

```kotlin
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.UiParams
import com.jonnyzzz.mcpSteroid.server.UiToolHandler
import kotlinx.serialization.json.put

class DevrigUiToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
) : UiToolHandler {
    override suspend fun handleUi(projectName: String, params: UiParams): ToolCallResult {
        val route = routing.requireProject(projectName)
        return bridge.callProjectTool(route, "steroid_ui") {
            put("task_id", params.taskId)
            put("reason", params.reason)
            params.windowId?.let { put("window_id", it) }
            params.steps?.let { put("steps", it) }
            params.snapshot?.let { put("snapshot", it) }
            put("max_nodes", params.maxNodes)
            put("trace", params.trace)
        }
    }
}
```

Wire it in `StubMcpSteroidTools` the way `visionInput` is wired, and add
`"steroid_ui"` to `ExpectedSteroidTools`.

- [ ] **Step 9: Run the affected suites**

Run, one at a time:
`./gradlew :mcp-steroid-server:test`
`./gradlew :npx-kt:test`
`./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.ui.*' --tests '*SplitRouting*' --tests '*RoutedTool*'`
Expected: PASS. Fix every list that enumerates the tools until green.

- [ ] **Step 10: Commit**

```bash
git commit -m "feat(ui): steroid_ui returns a snapshot of the project's windows" -- <every file touched in this task>
```

### Task 4: build and check live (checkpoint)

- [ ] **Step 1: Build**

Run: `./gradlew :ij-plugin:buildPlugin -x test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Start the sandbox IDE (2026.1)**

Run in the background: `./gradlew :ij-plugin:runIde`. Find its MCP URL the
way the plugin publishes it (`ServerUrlWriter`, `PortPins`: the server URL
file or `~/.mcp-steroid/ports.json` entry for the sandbox's IDE path). Open a
small project with `steroid_open_project` over HTTP (curl JSON-RPC).

- [ ] **Step 3: Check the slice**

Over HTTP, against the sandbox only:
1. `tools/list` includes `steroid_ui`.
2. `steroid_ui` with no steps: the header says `source=remote-driver`
   (proves the class loader lookup on 2026.1), the frame's tool window
   buttons, tabs and editor tab names appear with refs.
3. Open Settings in the sandbox (`steroid_execute_code` with `invokeLater`
   and `ShowSettingsUtil.getInstance().showSettingsDialog(project, "Editor")`,
   `modal=unleashed`), then `steroid_ui` with no steps: the Settings window
   is listed first, with its category tree rows and buttons.
4. Record: response time (from `idea.log` timestamps), size of the text.
5. Close Settings.

- [ ] **Step 4: Decide**

If the remote driver fails to load or the output is not useful, fix it
before Phase 2 and record the ruling in the ledger. If the times are far
above the spec's target (well under a second per call), measure where the
time goes before continuing.

### Phase 1 results (2026-09-26, IU-261.22158.277 sandbox)

- The first live call failed: on 2026.1 the model classes are in the main
  jar, and the main class loader refuses them with a `PluginException`
  ("must not be requested from main classloader"), which escaped the
  fallback. Fixed in `f8c66fd4` (content-module loaders first, any refusal
  skipped).
- `source=remote-driver` works. Project frame: 34 ms server side, 3.2 KB,
  42 refs. With the Settings dialog open: 414 ms for both windows, Settings
  listed first with its tree rows, page links and OK/Cancel/Apply states.
- Painted text repeats entries (`Search Everywhere|Search Everywhere`):
  drop consecutive duplicates in Task 5.
- The test task and `runIde` share the sandbox: stop the sandbox IDE before
  running `:ij-plugin:test` (it cannot copy locked jars).

Rulings for Phase 2, from what Phase 1 showed:

- Ruling: input events are dispatched with `IdeEventQueue.dispatchEvent`,
  each inside its own `invokeLater(…, ModalityState.any())` task, then a
  barrier task is queued. This keeps the delivery that 0.109 verified
  (window-sourced mouse events, keys to the focus owner or the target) and
  still returns when a dispatch opens a modal dialog, because the dialog's
  loop runs the barrier. Spec decisions 5 and 6 said "post"; the effect they
  asked for is the same. Cost if wrong: posting would have to replace the
  task wrapper in `UiInput` only.
- Ruling: the modality watcher moves to Phase 3 (Tasks 11–12), where
  `modal=dialog` and `ui.open` need it. Phase 2 detects opened and closed
  windows by comparing the showing window list before and after a step.
  Cost if wrong: one extra class in Phase 2.
- Ruling: while a modal dialog shows, targets are searched only in the
  topmost modal dialog and the windows it owns, which are the only windows
  a user can click. Cost if wrong: a target in a blocked window reports "no
  match" instead of "blocked by a modal dialog".

---

## Phase 2: actions

### Task 5: locators and the `root` narrowing

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiLocator.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiLocatorTest.kt`
- Create: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiSteps.kt` (step model and JSON parsing)
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/UiStepsTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class UiTarget(val ref: String? = null, val name: String? = null, val text: String? = null, @SerialName("class") val cls: String? = null, val xpath: String? = null, val nth: Int? = null)`
  - `@Serializable data class UiStep(val action: String, val target: UiTarget? = null, …fields…, @SerialName("timeout_ms") val timeoutMs: Long = 5000)` with the fields of the spec's action table: `button`, `count`, `modifiers`, `text`, `keys`, `row`, `index`, `for` (as `condition`), `title`, `offset_x`, `offset_y`. Targets are written flat in JSON (`{"action":"click","name":"OK"}`); the parser lifts `ref/name/text/class/xpath/nth` into `target`.
  - `object UiSteps { fun parse(json: String): List<UiStep> }`, throws `IllegalArgumentException` with the step index and the problem.
  - `sealed interface UiMatch { data class One(val node: UiNode); data class None(val candidates: List<UiNode>); data class Many(val matches: List<UiNode>) }`
  - `object UiLocator { fun find(root: UiNode, target: UiTarget, xpathMatches: ((String) -> Set<Component>)? = null): UiMatch }`

Test cases (JUnit 4 for the locator, JUnit 5 for the parser):

```kotlin
// UiLocatorTest
@Test fun `name matches exactly, text matches a substring, class matches a superclass`()
@Test fun `two matches fail as Many unless nth picks one`()
@Test fun `no match returns up to five candidates of the same class or a similar name`()
@Test fun `combined fields must all match`()

// UiStepsTest
@Test fun `a flat click step parses into a step with a name target`()
@Test fun `an unknown action fails with its index`()
@Test fun `a click without a target fails`()
@Test fun `press requires keys and a bad chord fails`()
@Test fun `timeout_ms defaults to 5000`()
```

`class` matching walks the component's superclasses by simple name, so
`JTextComponent` finds a `JBTextField`. `xpath` is evaluated by the caller on
the remote-driver document when available (the `UiModel` keeps the
`Document` for that); without it the locator fails with "xpath needs the
remote-driver model". Candidate ranking: same class first, then the smallest
edit distance of name or text to the wanted string.

Commit: `feat(ui): strict locators and the step model`.

### Task 6: shared input, settle and the modality watcher

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiInput.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSettle.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/ModalityWatcher.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiInputEventsTest.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/KeyChordTest.kt`
- Modify: `ij-plugin/src/main/resources/META-INF/plugin.xml` (`ModalityWatcher` app service; its subscription is made on first use)

**Interfaces:**
- Produces:
  - `data class KeyChord(val keyCode: Int, val modifiers: Int)`; `object KeyChords { fun parse(s: String): KeyChord }` (`ctrl`, `shift`, `alt`, `meta`, case-insensitive, `+` separated, key names from `KeyEvent.VK_*`)
  - `fun typedCharEvents(source: Component, ch: Char, modifiers: Int, now: Long): List<KeyEvent>` (pressed, typed, released; typed only when no key code exists)
  - `class UiInput { suspend fun click(target: Component, button: Int, count: Int, modifiers: Int, offset: Point?): ClickReport; suspend fun hover(target: Component); suspend fun press(chord: KeyChord, fallbackTarget: Component): KeyReport; suspend fun type(text: String, fallbackTarget: Component): KeyReport }`
  - `data class ClickReport(val pressedComponent: Component?, val hitTarget: Boolean, val actionPerformed: Boolean?)`
  - `data class KeyReport(val delivery: String)` (`posted` or `direct`)
  - `@Service(APP) class ModalityWatcher { val events: SharedFlow<ModalityEvent>; fun modalStack(): List<Any> }` with `data class ModalityEvent(val entering: Boolean, val entity: Any, val atNanos: Long)`
  - `object UiSettle { suspend fun barrier(); suspend fun settle(watcher: ModalityWatcher, quietMs: Long = 150, maxMs: Long = 1000) }`

Behaviour:
- `click` resolves the window and point on the EDT
  (`ModalityState.any()`), scrolls the target into view
  (`JComponent.scrollRectToVisible`), then **posts** the events built by
  the existing `clickEventSequence` to `IdeEventQueue.getInstance().postEvent`
  with the window as source. For `count == 2` it posts the two
  press/release/click triples back to back with click counts 1 and 2. An
  `AWTEventListener` (installed before posting, removed after the barrier)
  records the retargeted `MOUSE_PRESSED` recipient; an `ActionListener` on an
  `AbstractButton` target records `actionPerformed`. `hitTarget` is true when
  the recipient is the target or inside it.
- `UiSettle.barrier()` suspends until a runnable queued with
  `ApplicationManager.getApplication().invokeLater(r, ModalityState.any())`
  has run (`CompletableDeferred`), bounded by 5 s; on timeout it throws
  `UiBarrierTimeout`.
- `press` and `type` post key events when
  `KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner` is not
  null after activating the window; otherwise they dispatch directly to
  `fallbackTarget` through `IdeEventQueue.dispatchEvent`, and report
  `direct`.
- `ModalityWatcher` subscribes on first access:
  `ApplicationManager.getApplication().messageBus.connect(this).subscribe(ModalityStateListener.TOPIC, …)`,
  keeps the entered entities in a list, emits to a replay-0 `SharedFlow`.
  It is `Disposable`.

Unit tests cover `KeyChords.parse` (`ENTER`, `ctrl+shift+A`, `meta+1`, a
bad key name fails with the name), `typedCharEvents` for `a`, `A` (shift),
`€` (typed only), and the double-click event list. Dispatch, the barrier and
the watcher are covered by the integration test in Task 9.

Commit: `feat(ui): posted input with a barrier, and a modality watcher`.

### Task 7: UiSession, actions and the effect report

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSession.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSnapshotDiff.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiSnapshotDiffTest.kt`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiToolHandler.kt` (run steps through `UiSession`)
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/UiTool.kt` (description: step table and one example)

**Interfaces:**
- Consumes: `UiSteps.parse`, `UiLocator.find`, `UiInput`, `UiSettle`, `ModalityWatcher`, `UiModel`, `UiSnapshotFormatter`, `UiRefs`.
- Produces:
  - `class UiSession(project: Project, windowId: String?, maxNodes: Int, trace: UiTrace?) { suspend fun run(steps: List<UiStep>, snapshotMode: String): UiSessionResult }`
  - `data class UiStepReport(val index: Int, val action: String, val ref: String?, val lines: List<String>)`
  - `data class UiSessionResult(val reports: List<UiStepReport>, val failure: String?, val snapshot: String)`
  - `object UiSnapshotDiff { fun diff(before: String, after: String): String }` (line-based: `+ line`, `- line`; lines are compared without the `[focused]` state and with refs kept, so the same control keeps the same identity)

Actions in this task: `click`, `hover`, `type`, `fill`, `press`, `check`,
`uncheck`, `wait` (`visible`, `hidden`, `enabled`, `window`, `idle`),
`snapshot`. `select` and `close` are Task 8.

Per step:
1. Resolve the target: a `ref` through `UiRefs` (`Stale` and `Unknown` fail
   at once); otherwise build the model of the current windows and run
   `UiLocator.find`, repeating every 100 ms until `One` or the step's
   `timeout_ms`. The resolution runs on the EDT under
   `ModalityState.any()` (pure UI read).
2. Check enabled (except `wait hidden` and `snapshot`).
3. Act through `UiInput`. `fill` = focus the target
   (`IdeFocusManager.requestFocus`), select all (`JTextComponent.selectAll`),
   type. `check`/`uncheck` read `JToggleButton.isSelected` first and click
   only when it differs.
4. `UiSettle.settle` and collect: `ClickReport`, IDE actions (reuse the
   `AnActionListener` recorder that `steroid_input` uses for "IDE actions
   performed"), windows opened or closed (the watcher's events plus a
   before/after window list), the new focus owner's class and name, and
   `unexpected` for a modal no step named (a modal is named when the step's
   target is inside it, or a `wait for=window` step named its title).
5. On failure: stop, report the step index and message, attach a full
   snapshot.

Response text: `execution_id`, one block per step (`step 2 click "OK"
[ref=e31]: pressed JButton "OK" (hit), action performed, window closed
w-3f2a "Settings"`), then the snapshot per `snapshot` mode (`diff` by
default).

Unit test for the diff:

```kotlin
@Test fun `added and removed lines are marked and unchanged lines dropped`()
@Test fun `a focus change alone produces no diff`()
```

Commit: `feat(ui): run steps and report each action's effect`.

### Task 8: select, close, and error messages

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiRows.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/ui/UiRowsTest.kt`
- Modify: `UiSession.kt`, `UiSnapshotFormatter.kt` (rows for lists, trees, tables)

**Interfaces:**
- Produces: `object UiRows { fun rows(c: Component): List<String>?; fun rowBounds(c: Component, index: Int): Rectangle? }`
  reading `JList` via its renderer (`getListCellRendererComponent`), `JTree`
  via `getPathForRow` and the renderer, `JTable` via `getCellRenderer(row, 0)`;
  a renderer component's text is its accessible name, else `JLabel.text`,
  else `SimpleColoredComponent`'s `getCharSequence(false)`, else
  `toString()` of the value.

`select`: on a `JComboBox`, click it, wait for its popup list
(`ComboPopup.getList()` via `BasicComboPopup` or the IDE's list popup),
then select there. On a list, tree or table: find the row by exact text,
then by substring; scroll it visible (`scrollRectToVisible(rowBounds)`);
click its centre through `UiInput.click` with an offset. `close`: find the
`DialogWrapper` (`DialogWrapper.findInstance(component)`) and call
`doCancelAction()`, or the popup (`PopupUtil.getPopupContainerFor`) and
`cancel()`. The `unexpected` modal check and every row of the spec's error
table get their message here; the "nearest candidates" come from
`UiMatch.None`.

Unit tests: `UiRows.rows` for a `JList` with a custom renderer, a `JTree`
with two expanded rows, a `JTable`; `rowBounds` non-null for a visible row.

Commit: `feat(ui): select rows and close dialogs, with diagnostic errors`.

### Task 9: integration test and steroid_input on the shared input

**Files:**
- Create: `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/SteroidUiIntegrationTest.kt`
- Modify: `test-integration/src/main/kotlin/com/jonnyzzz/mcpSteroid/integration/infra/mcp-steroid.kt` (a `ui(...)` helper next to the `steroid_input` one)
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/vision/VisionService.kt` (`typeText` sends the pressed/typed/released triple through `typedCharEvents`; clicks keep synchronous dispatch)

Model the test on `SteroidInputDialogIntegrationTest`: it opens a test
dialog through `steroid_execute_code` and drives it. Cases, from the spec's
Testing section: snapshot lists the dialog with refs; `fill`, `check`,
`click OK` closes it and applies values; a click on a button that opens a
modal dialog returns within 2 s and describes the new dialog; `select` in a
list popup and a combo box; a miss fails with candidates; a stale ref fails
as stale.

Run only this class: `./gradlew :test-integration:test --tests '*SteroidUiIntegrationTest*'`
Expected: PASS. Also run `--tests '*SteroidInputDialogIntegrationTest*'` for
the `typeText` change.

Commit: `test(ui): drive a dialog end to end through steroid_ui`.

### Task 10: live check of actions (checkpoint)

In the `runIde` sandbox: open Settings by action, `select` a category by
name in the tree, `check` a checkbox, `click` Cancel; open the Quick Switch
Scheme popup and `select` an item. Record the times of a snapshot and of an
action step. Fix what fails before Phase 3.

---

## Phase 3: modal dialogs, helpers, screenshots, traces, docs

### Task 11: `modal=dialog`

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ExecuteCodeTool.kt` (`ModalMode.DIALOG`, wire `dialog`, description)
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ScriptExecutor.kt`
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/ExecuteCodeToolSpecSchemaTest.kt` (enum lists `dialog`)
- Test: integration case in `SteroidUiIntegrationTest`

Pre-flight for `DIALOG`: on the EDT with `ModalityState.any()`, read
`ModalityState.current()`; if it equals `ModalityState.nonModal()`, fail
with "no modal dialog is open; use smart_non_modal". Otherwise run the body
with `+ state.asContextElement()` added to the script's context, no dialog
sweep, no document sync, no VFS refresh, no monitor. Log the dialog title.
`smart_non_modal`'s surviving-dialog failure names `modal=dialog`.

Integration case: with the test dialog open, a `modal=dialog` script edits
a document in `writeAction` and returns while the dialog stays open; the
same script with `modal=non_modal` fails at the gate.

Commit: `feat(execute): modal=dialog runs a script under the open dialog's modality`.

### Task 12: `ui.*` helpers and `ui.open`

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/McpScriptContext.kt` (`val ui: UiScriptApi`)
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/McpScriptContextImpl.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiScriptApi.kt`
- Modify: `DialogKiller.kt` (monitor reacts to `ModalityWatcher` events instead of the 1 s poll)

`UiScriptApi` methods: `suspend fun snapshot(root: UiTarget? = null): String`,
`suspend fun find(target: UiTarget): Component`, `suspend fun click(target: UiTarget)`,
`type(text, target?)`, `fill(target, text)`, `press(keys)`, `select(target, row)`,
`check(target)`, `uncheck(target)`, `close(target?)`,
`suspend fun waitFor(target: UiTarget, timeoutMs: Long = 5000): Component`,
`suspend fun open(timeoutMs: Long = 10_000, block: () -> Unit): Window`.
Target builders: `ref("e12")`, `name("OK")`, `text("…")`, `cls("JTree")`,
`xpath("…")`, `infix fun UiTarget.and(other: UiTarget)`. All delegate to the
same code as `UiSession`; EDT work uses `ModalityState.stateForComponent`
of the target window.

`open`: record the watcher's current stack, `invokeLater(block, contextModality ?: nonModal)`,
await a `ModalityEvent(entering = true)` for a new entity, return its window.

Integration case: `ui.open { Messages.showYesNoDialog(...) }` returns the
dialog; a `steroid_ui` click on "Yes" closes it and the script's recorded
answer is Yes.

Commit: `feat(execute): ui helpers and ui.open for scripts`.

### Task 13: screenshots use the snapshot

**Files:**
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/vision/SwingComponentTreeProvider.kt`
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/VisionScreenshotTool.kt` (`marks` boolean, default false)
- Modify: `VisionService.kt` (draw marks on a copy, `screenshot-marked.png`)

`screenshot-tree.md` becomes the `UiSnapshotFormatter` text of the captured
window with `withBounds = true`. With `marks=true`, each listed node with a
ref gets a 1 px rectangle and its ref label drawn at its bounds (converted
to image pixels with the capture's scale), and the marked image is the one
returned.

Commit: `feat(vision): screenshot tree from the UI snapshot, and ref marks`.

### Task 14: traces

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/ui/UiTrace.kt`
- Modify: `UiSession.kt`, `UiToolHandler.kt`

With `trace=true`: before and after each step, capture the step's window
through `VisionService.capture`'s image path into `NN-before.png` /
`NN-after.png`; write `NN-snapshot.txt`; append one JSON line per step to
`trace.jsonl` (index, action, target, ref, delivery, effects, start and
duration in ms); write `trace.md` linking all files. The response gives the
folder path. Integration case: a traced run writes those files.

Commit: `feat(ui): opt-in traces with pictures and snapshots per step`.

### Task 15: prompts, philosophy and TODO

**Files:**
- Modify: `prompts/src/main/prompts/ide/ui-driving.md` (lead with `steroid_ui`; helpers replace the pasted blocks; `modal=dialog` replaces `allowModalDialog()` plus `unleashed`)
- Modify: `prompts/src/main/prompts/skill/split-mode.md` (`steroid_ui` runs in the client; `side=backend`)
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/VisionInputTool.kt` (description points to `steroid_ui`)
- Modify: `docs/PHILOSOPHY.md` (9 tools; `steroid_ui` with the ruled-out path: every `steroid_execute_code` call compiles Kotlin, 5.6 s of a 6 s UI step measured on 2026.3 EAP, and no recipe removes compilation)
- Modify: `TODO.md` (resolve "Screenshot metadata from the remote-driver UI model")

Run: `./gradlew :prompts:test -Pmcp.prompts.ide.filter=none --tests '*ContractTest' --tests '*ResourceIndexTest' --tests '*PromptQualityTest'`
and, because `ui-driving.md` keeps Kotlin blocks, `-Pmcp.prompts.ide.filter=idea:stable --tests '*KtBlock*'` limited to that article if the test supports a filter.

Commit: `docs(prompts): steroid_ui first in UI driving`.

### Task 16: final live check and review

In the `runIde` sandbox (2026.1) and, if available, a 2026.3 EAP sandbox:
the spec's live list (Settings page by name, checkbox, Cancel; Quick Switch
Scheme item; `modal=dialog` script; `ui.open`; `trace=true`). Then a
whole-change self-review, fixes with tests, full module suites:
`:mcp-steroid-server:test`, `:npx-kt:test`, `:ij-plugin:test`, and the
integration class.

---

## Results (2026-09-26)

Phase 2 live run (2026.1 sandbox) found and fixed: owned windows doubled
in their owner's tree, labels sharing a field's name made strict matches
ambiguous, editable combo boxes did not open on a centre click, combo
values printed `toString()`, and Settings opened after the settle window.
Phase 3's first integration run found that the platform's `writeAction`
(a background write action) cannot write under an open modal dialog;
`modal=dialog` uses `edtWriteAction`. The KtBlock run found that
`McpScriptContext.kt` must stay compilable on its own, so the `ui`
surface is declared there (`McpUi`, `UiQuery`).

Rulings made during execution:

- Tasks 11–14 share files (`UiSession`, the script context, the golden
  schema), so they landed in one commit (`a05000ff`) instead of four.
- The modality watcher (spec decision 14) is deferred and recorded in
  `TODO.md`; steroid_ui and `ui.open` compare window lists instead.
- `ui.open` runs its block under `ModalityState.current()`, read on the
  EDT, rather than the script's context modality (internal API).

Verification: unit suites for `ui`, `mcp-steroid-server` and `npx-kt`;
`:ij-plugin:test` 471 tests, one failure in `KotlinxBundledVersionTest`
(the 2026.3 EAP snapshot bundles kotlinx-serialization 1.11 against the
1.9 pin; unrelated to this work); `SteroidUiIntegrationTest` 7/7 in
Docker; ui-driving, split-mode and action-discovery KtBlocks on IDEA
stable; live on 2026.1: snapshot 34 ms, a five-step Settings flow 1.7 s,
`ui.open`, `modal=dialog` with a write, a traced run and a marked
screenshot at 1.5 scale. Not verified: Split Mode (`side=backend`) and
2026.3 live.

## Review rounds and live checks (2026-09-26)

Review round 1 (`5cc7fc17`): a ref into a window blocked by a modal
dialog was clicked; `ui.open` hid its block's exception behind the
timeout. Round 2 (`7410e777`): a live ref walked its whole subtree; an
orphaned `UiRows.rowBounds`.

Split Mode (`runIdeSplitMode`, IU-261.22158.277), through the JetBrains
Client's endpoint:

- Frontend (default side): snapshot 205 ms; a four-step Settings flow in
  2.0 s with every effect reported.
- `side=backend`: the backend lists its Lux-hosted Settings panels and
  editor notifications; check and fill on a backend panel in 852 ms.
- A modal dialog the backend opens shows in the client as an empty Lux
  host with no buttons. `side=backend` lists its buttons and clicks one
  (195 ms); the dialog closes on both sides.

IntelliJ IDEA 2026.3 EAP (IU-263.5885), a separate instance with its own
config, system and plugin paths:

- Snapshot 174 ms. A first-run "Meet the Islands Theme" modal blocked
  the Settings shortcut, and the failed wait named it with its controls.
- Settings is a non-modal dialog on 2026.3, so its "Cancel" also matched
  a "Cancel" link in the frame behind it. Matching now counts only the
  topmost window with a match.
- `modal=dialog` with `writeAction` hung: 2026.3's `edtWriteAction`
  takes the write-intent permit before it switches to the EDT, and the
  EDT holds write-intent for the dialog's whole event loop. Under a
  dialog the write runs on the EDT with `runWriteAction`, which works on
  2026.1 (integration 7/7) and 2026.3.
- `ui` helpers with `ui.open`, the seven-step Settings flow with a trace
  (Zoom 100% to 110% in the trace snapshots), a marked screenshot at 1.5
  scale, and `close` all work.

Not in scope, seen on the way: the JetBrains Client logs a startup
`NoClassDefFoundError` for `ProjectLevelVcsManager` from
`VcsConfirmationSilencer`.
