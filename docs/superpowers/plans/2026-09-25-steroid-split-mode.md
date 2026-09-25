# MCP Steroid Plus Split Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One MCP endpoint in Split Mode. The JetBrains Client hosts it, runs UI
tools locally, and forwards project and code tools to the backend over platform
RPC. The monolith and a backend with no client behave as today.

**Architecture:** The existing code stays in the main plugin module ("core"),
which loads on every side. Core wraps every registered tool in a `RoutedTool`.
In a split frontend, `RoutedTool` sends backend-side tools through a
`SplitFrontendBridge`. That bridge is implemented in a new `frontend` content
module over a `SteroidBridgeApi` RPC contract in `shared`, which a `backend`
content module serves by running the call through core's `executeBridgedTool`.

**Tech Stack:** Kotlin 2.3.20, IntelliJ Platform Gradle Plugin 2.18.1,
platform RPC (`fleet.rpc`, Gradle plugin `rpc` 2.3.20-RC2-0.1),
kotlinx.serialization, JUnit 4 and JUnit 5 as the modules already use.

**Spec:** `docs/superpowers/specs/2026-09-25-steroid-split-mode-design.md`

## Global Constraints

- Since-build stays `261`. No new upper bound.
- Kotlin stays `2.3.20`. The `rpc` Gradle plugin is `2.3.20-RC2-0.1`, from
  `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/`.
- Plugin ID `io.github.crazycoder.mcp-steroid` is unchanged.
- Content module names: `mcp-steroid.shared` (`loading="required"`),
  `mcp-steroid.frontend`, `mcp-steroid.backend`. Each descriptor sits directly
  in its module's `src/main/resources/` as `<module name>.xml`.
- Module descriptors use `<dependencies>`, not `<depends>`.
- RPC is called only from coroutines, never on the EDT, and wrapped in
  `durable {}`.
- Role values on the wire are the lowercase strings `monolith`, `frontend`,
  `backend`.
- Tool routing: backend = `steroid_list_projects`, `steroid_open_project`,
  `steroid_execute_code` (unless `side=frontend`), `steroid_execute_feedback`.
  Frontend = `steroid_list_windows`, `steroid_take_screenshot`, `steroid_input`,
  `steroid_fetch_resource`.
- Never run a bare `./gradlew :prompts:test`. It downloads 20+ GB of IDEs.
- Commit to `main` with conventional commits and no AI attribution. Do not push
  until the user asks.

## Review Focus

1. **A tool added later is not in the routing table.** It must fail loudly with
   "no routing for tool", not run silently on the wrong side. Tested in Task 1.
2. **The backend drops out in the middle of `execute_code`.** The call must
   return an error naming the backend connection within the tool's timeout, not
   hang. Tested in Task 2 with a bridge that throws.
3. **`side=frontend` on a backend with no client.** It must return an error that
   says no frontend is attached, not run on the backend. Tested in Task 1.
4. **A frontend project the backend does not know.** For example, a Light Edit
   project that exists only on the client: `project_name` falls back to the
   local key, it does not crash, and `list_windows` still lists the window.
   Tested in Task 5.
5. **Progress from a forwarded `execute_code` reaches the MCP client in order.**
   Each backend progress line comes out as one client notification, and the
   final result comes last. Tested in Task 3.

---

### Task 1: Split role and tool routing (pure logic)

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/split/SplitRouting.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/split/SplitRoutingTest.kt`

**Interfaces:**
- Produces:
  - `enum class SplitRole(val wire: String) { MONOLITH("monolith"), FRONTEND("frontend"), BACKEND("backend") }`
  - `fun classifySplitRole(mode: ProductMode): SplitRole`
  - `fun currentSplitRole(): SplitRole`
  - `enum class ToolSide { LOCAL, BACKEND }`
  - `fun routeTool(role: SplitRole, toolName: String, arguments: JsonObject): ToolSide`
  - `const val SIDE_ARGUMENT = "side"`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.platform.runtime.product.ProductMode
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitRoutingTest {
    private val none = buildJsonObject { }
    private fun side(value: String) = buildJsonObject { put(SIDE_ARGUMENT, value) }

    @Test
    fun `product modes map to roles`() {
        assertEquals(SplitRole.MONOLITH, classifySplitRole(ProductMode.MONOLITH))
        assertEquals(SplitRole.FRONTEND, classifySplitRole(ProductMode.FRONTEND))
        assertEquals(SplitRole.BACKEND, classifySplitRole(ProductMode.BACKEND))
    }

    @Test
    fun `monolith and backend run every tool locally`() {
        for (role in listOf(SplitRole.MONOLITH, SplitRole.BACKEND)) {
            for (tool in ROUTED_TOOLS.keys) {
                assertEquals("$role $tool", ToolSide.LOCAL, routeTool(role, tool, none))
            }
        }
    }

    @Test
    fun `split frontend forwards backend tools and keeps UI tools`() {
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_list_projects", none))
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_open_project", none))
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_execute_code", none))
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_execute_feedback", none))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_list_windows", none))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_take_screenshot", none))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_input", none))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_fetch_resource", none))
    }

    @Test
    fun `execute_code side argument overrides the default`() {
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.FRONTEND, "steroid_execute_code", side("frontend")))
        assertEquals(ToolSide.BACKEND, routeTool(SplitRole.FRONTEND, "steroid_execute_code", side("backend")))
        assertEquals(ToolSide.LOCAL, routeTool(SplitRole.MONOLITH, "steroid_execute_code", side("frontend")))
    }

    @Test
    fun `side frontend on a backend with no client is an error`() {
        val e = assertThrows(ToolCallErrorException::class.java) {
            routeTool(SplitRole.BACKEND, "steroid_execute_code", side("frontend"))
        }
        assertTrue(e.message, e.message.contains("no frontend is attached"))
    }

    @Test
    fun `an unknown side value is an error`() {
        assertThrows(ToolCallErrorException::class.java) {
            routeTool(SplitRole.FRONTEND, "steroid_execute_code", side("both"))
        }
    }

    @Test
    fun `a tool missing from the routing table fails loudly`() {
        val e = assertThrows(IllegalStateException::class.java) {
            routeTool(SplitRole.FRONTEND, "steroid_new_tool", none)
        }
        assertTrue(e.message!!, e.message!!.contains("no routing for tool steroid_new_tool"))
    }
}
```

- [ ] **Step 2: Run the test and check that it fails**

Run: `./gradlew :ij-plugin:test --tests '*SplitRoutingTest'`
Expected: compilation FAIL, because `SplitRole`, `routeTool` and the rest are
unresolved.

- [ ] **Step 3: Implement**

```kotlin
/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.platform.ide.productMode.IdeProductMode
import com.intellij.platform.runtime.product.ProductMode
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Which part of a Split Mode IDE this process is. A monolith is both parts in one process. */
enum class SplitRole(val wire: String) { MONOLITH("monolith"), FRONTEND("frontend"), BACKEND("backend") }

/**
 * LIGHT without an RD connection is a standalone process, so it counts as a monolith.
 * LIGHT_WITH_RD_CONNECTION talks to a backend, so it counts as a frontend.
 */
fun classifySplitRole(mode: ProductMode): SplitRole = when (mode) {
    ProductMode.FRONTEND, ProductMode.LIGHT_WITH_RD_CONNECTION -> SplitRole.FRONTEND
    ProductMode.BACKEND -> SplitRole.BACKEND
    else -> SplitRole.MONOLITH
}

fun currentSplitRole(): SplitRole =
    runCatching { classifySplitRole(IdeProductMode.getInstance().currentMode) }.getOrDefault(SplitRole.MONOLITH)

enum class ToolSide { LOCAL, BACKEND }

const val SIDE_ARGUMENT = "side"

private enum class Home { BACKEND, FRONTEND }

/** Where each tool runs in Split Mode. Every registered tool must be listed. */
internal val ROUTED_TOOLS: Map<String, Home> = mapOf(
    "steroid_list_projects" to Home.BACKEND,
    "steroid_open_project" to Home.BACKEND,
    "steroid_execute_code" to Home.BACKEND,
    "steroid_execute_feedback" to Home.BACKEND,
    "steroid_list_windows" to Home.FRONTEND,
    "steroid_take_screenshot" to Home.FRONTEND,
    "steroid_input" to Home.FRONTEND,
    "steroid_fetch_resource" to Home.FRONTEND,
)

fun routeTool(role: SplitRole, toolName: String, arguments: JsonObject): ToolSide {
    val home = ROUTED_TOOLS[toolName] ?: error("no routing for tool $toolName")
    val requested = arguments[SIDE_ARGUMENT]?.jsonPrimitive?.contentOrNull
    val wanted = when (requested) {
        null -> home
        "backend" -> Home.BACKEND
        "frontend" -> Home.FRONTEND
        else -> throw ToolCallErrorException("Unsupported side '$requested'. Use 'frontend' or 'backend'.")
    }
    return when (role) {
        SplitRole.MONOLITH -> ToolSide.LOCAL
        SplitRole.BACKEND -> {
            if (requested == "frontend") throw ToolCallErrorException(
                "side=frontend was requested, but no frontend is attached to this IDE. It runs as a Remote Development backend."
            )
            ToolSide.LOCAL
        }
        SplitRole.FRONTEND -> if (wanted == Home.BACKEND) ToolSide.BACKEND else ToolSide.LOCAL
    }
}
```

`Home` must be `internal`, not `private`, so that `ROUTED_TOOLS` can be
`internal`. Declare it as `internal enum class Home`.

- [ ] **Step 4: Run the test and check that it passes**

Run: `./gradlew :ij-plugin:test --tests '*SplitRoutingTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/split/SplitRouting.kt ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/split/SplitRoutingTest.kt
git commit -m "feat(split): classify the split role and route tools by side"
```

### Task 2: RoutedTool, the bridge interface, and registration

**Files:**
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/split/SplitFrontendBridge.kt`
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/split/RoutedTool.kt`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/SteroidsMcpServer.kt:104-114` (tool registration)
- Modify: `ij-plugin/src/main/resources/META-INF/plugin.xml` (extension point)
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/split/RoutedToolTest.kt`

**Interfaces:**
- Consumes: `routeTool`, `SplitRole`, `ToolSide` (Task 1).
- Produces:
  - `interface SplitFrontendBridge { suspend fun forward(params: ToolCallParams, progress: McpProgressReporter): ToolCallResult; suspend fun refreshProjectKeys(); fun backendKeyFor(project: Project): String?; suspend fun backendSelf(): BackendRef? }`
  - `fun activeSplitFrontendBridge(): SplitFrontendBridge?`: the single registered extension, or `null`.
  - `class RoutedTool(delegate: McpTool, role: () -> SplitRole, bridge: () -> SplitFrontendBridge?) : McpTool`
  - `SteroidsMcpServer.ensureToolsRegistered()` (public), used by the backend bridge in Task 3.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.mcp.*
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class RoutedToolTest {
    private class LocalTool(override val name: String) : McpTool {
        var calls = 0
        override val description = "local"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override suspend fun call(context: ToolCallContext): ToolCallResult { calls++; return ToolCallResult.successTextResult("local") }
    }

    private class FakeBridge(private val fail: Boolean = false) : SplitFrontendBridge {
        val forwarded = mutableListOf<String>()
        var refreshes = 0
        override suspend fun forward(params: ToolCallParams, progress: McpProgressReporter): ToolCallResult {
            if (fail) throw IllegalStateException("connection lost")
            forwarded += params.name; progress.report("from backend"); return ToolCallResult.successTextResult("remote")
        }
        override suspend fun refreshProjectKeys() { refreshes++ }
        override fun backendKeyFor(project: Project): String? = null
        override suspend fun backendSelf(): BackendRef? = null
    }

    private fun context(name: String, reporter: McpProgressReporter = NoOpProgressReporter) =
        ToolCallContext(ToolCallParams(name = name), McpSession(), reporter)

    private fun text(r: ToolCallResult) = (r.content.single() as ContentItem.Text).text

    @Test
    fun `split frontend forwards a backend tool and relays progress`() = runBlocking {
        val local = LocalTool("steroid_execute_code"); val bridge = FakeBridge()
        val seen = mutableListOf<String>()
        val reporter = object : McpProgressReporter { override fun report(message: String) { seen += message } }
        val result = RoutedTool(local, { SplitRole.FRONTEND }, { bridge }).call(context("steroid_execute_code", reporter))
        assertEquals("remote", text(result)); assertEquals(0, local.calls)
        assertEquals(listOf("steroid_execute_code"), bridge.forwarded); assertEquals(listOf("from backend"), seen)
    }

    @Test
    fun `split frontend runs a UI tool locally after refreshing project keys`() = runBlocking {
        val local = LocalTool("steroid_input"); val bridge = FakeBridge()
        assertEquals("local", text(RoutedTool(local, { SplitRole.FRONTEND }, { bridge }).call(context("steroid_input"))))
        assertEquals(1, local.calls); assertEquals(1, bridge.refreshes); assertTrue(bridge.forwarded.isEmpty())
    }

    @Test
    fun `monolith never touches the bridge`() = runBlocking {
        val local = LocalTool("steroid_execute_code"); val bridge = FakeBridge()
        RoutedTool(local, { SplitRole.MONOLITH }, { bridge }).call(context("steroid_execute_code"))
        assertEquals(1, local.calls); assertTrue(bridge.forwarded.isEmpty()); assertEquals(0, bridge.refreshes)
    }

    @Test
    fun `a lost backend becomes a tool error naming the connection`() = runBlocking {
        val result = RoutedTool(LocalTool("steroid_execute_code"), { SplitRole.FRONTEND }, { FakeBridge(fail = true) })
            .call(context("steroid_execute_code"))
        assertTrue(result.isError); assertTrue(text(result), text(result).contains("backend is not connected"))
    }

    @Test
    fun `split frontend without a bridge is an error, not a local run`() = runBlocking {
        val local = LocalTool("steroid_execute_code")
        val result = RoutedTool(local, { SplitRole.FRONTEND }, { null }).call(context("steroid_execute_code"))
        assertTrue(result.isError); assertEquals(0, local.calls)
    }
}
```

- [ ] **Step 2: Run the test and check that it fails**

Run: `./gradlew :ij-plugin:test --tests '*RoutedToolTest'`
Expected: compilation FAIL, because `RoutedTool` and `SplitFrontendBridge` are
unresolved.

- [ ] **Step 3: Implement the bridge interface and the extension point**

`SplitFrontendBridge.kt`:

```kotlin
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter

/**
 * Reaches the backend from a Split Mode frontend. Core cannot see content-module classes, so the
 * `mcp-steroid.frontend` module implements this over platform RPC and registers it as an extension.
 */
interface SplitFrontendBridge {
    suspend fun forward(params: ToolCallParams, progress: McpProgressReporter): ToolCallResult

    /** Fetches the backend's `project_name` keys; [backendKeyFor] reads the result. */
    suspend fun refreshProjectKeys()

    /** The backend's `project_name` for this frontend [project], or null when the backend does not know it. */
    fun backendKeyFor(project: Project): String?

    /** The backend's `backends[]` entry, for frontend responses that list both sides. */
    suspend fun backendSelf(): BackendRef?
}

val SPLIT_FRONTEND_BRIDGE_EP: ExtensionPointName<SplitFrontendBridge> =
    ExtensionPointName("com.jonnyzzz.mcpSteroid.splitFrontendBridge")

fun activeSplitFrontendBridge(): SplitFrontendBridge? = SPLIT_FRONTEND_BRIDGE_EP.extensionList.firstOrNull()
```

In `plugin.xml`, inside `<extensionPoints>`:

```xml
<extensionPoint name="splitFrontendBridge"
                interface="com.jonnyzzz.mcpSteroid.server.split.SplitFrontendBridge"
                dynamic="true"/>
```

Check that the extension point's qualified name matches:
`rg -n '<id>' ij-plugin/src/main/resources/META-INF/plugin.xml` prints
`io.github.crazycoder.mcp-steroid`. Extension points take that plugin ID as
their prefix unless `qualifiedName` is set. Set
`qualifiedName="com.jonnyzzz.mcpSteroid.splitFrontendBridge"` on the element so
the name above holds.

- [ ] **Step 4: Implement RoutedTool**

```kotlin
package com.jonnyzzz.mcpSteroid.server.split

import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import kotlinx.coroutines.CancellationException

/** Runs [delegate] locally or forwards the call to the backend, per [routeTool]. */
class RoutedTool(
    private val delegate: McpTool,
    private val role: () -> SplitRole,
    private val bridge: () -> SplitFrontendBridge?,
) : McpTool by delegate {
    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val currentRole = role()
        val side = routeTool(currentRole, delegate.name, context.params.arguments)
        if (currentRole != SplitRole.FRONTEND) return delegate.call(context)
        val bridge = bridge() ?: return ToolCallResult.errorResult(
            "This JetBrains Client has no MCP Steroid frontend module loaded, so it cannot reach the backend."
        )
        if (side == ToolSide.LOCAL) {
            bridge.refreshProjectKeys()
            return delegate.call(context)
        }
        return try {
            bridge.forward(context.params, context.mcpProgressReporter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolCallResult.errorResult("The backend is not connected or the call to it failed: ${e.message}")
        }
    }
}
```

`routeTool` throws `ToolCallErrorException` for a bad `side`. `McpToolRegistry`
already turns that into an error result.

- [ ] **Step 5: Wrap every tool at registration**

In `SteroidsMcpServer.kt`, move the registration block out of
`startServerIfNeeded` into a public method and wrap each tool:

```kotlin
fun ensureToolsRegistered() {
    if (!toolsRegistered.compareAndSet(false, true)) return
    val tools = service<McpSteroidToolsIJ>()
    val specs = tools.commonToolSpecs() +
        OpenProjectToolSpec(includeBackendName = false) { tools.handler<OpenProjectToolHandler>() }
    specs.forEach { spec ->
        mcpServer.toolRegistry.registerTool(RoutedTool(spec, ::currentSplitRole, ::activeSplitFrontendBridge))
    }
}
```

`startServerIfNeeded` then calls `ensureToolsRegistered()` where the inline block
was. The existing comment above the block still applies and moves with it.

- [ ] **Step 6: Run the tests and check that they pass**

Run: `./gradlew :ij-plugin:test --tests '*RoutedToolTest' --tests '*SplitRoutingTest'`
Expected: PASS.

Run: `./gradlew :ij-plugin:test --tests '*McpServer*' --tests '*ToolRegistr*'`
Expected: PASS. Registration still exposes every tool name.

- [ ] **Step 7: Commit**

```bash
git add ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/split/ ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/SteroidsMcpServer.kt ij-plugin/src/main/resources/META-INF/plugin.xml ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/split/RoutedToolTest.kt
git commit -m "feat(split): route every tool through RoutedTool and a frontend bridge"
```

### Task 3: Run a bridged tool call on the backend (core)

**Files:**
- Modify: `mcp-core/src/main/kotlin/com/jonnyzzz/mcpSteroid/mcp/McpToolRegistry.kt` (a `callTool` overload that takes a reporter)
- Create: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/split/BridgedToolExecutor.kt`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/split/BridgedToolExecutorTest.kt`

**Interfaces:**
- Produces:
  - `suspend fun McpToolRegistry.callTool(params: ToolCallParams, session: McpSession, progress: McpProgressReporter): ToolCallResult`
  - `sealed interface BridgedOutcome { data class Progress(val message: String); data class Result(val result: ToolCallResult) }`
  - `fun executeBridgedTool(core: McpServerCore, params: ToolCallParams): Flow<BridgedOutcome>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jonnyzzz.mcpSteroid.server.split

import com.jonnyzzz.mcpSteroid.mcp.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class BridgedToolExecutorTest {
    private fun core(tool: McpTool) = McpServerCore(
        serverInfo = ServerInfo(name = "t", version = "0"),
        instructions = "",
        capabilities = ServerCapabilities(),
    ).also { it.toolRegistry.registerTool(tool) }

    private fun tool(body: suspend (ToolCallContext) -> ToolCallResult) = object : McpTool {
        override val name = "t"; override val description = "t"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override suspend fun call(context: ToolCallContext) = body(context)
    }

    @Test
    fun `progress lines arrive in order and the result comes last`() = runBlocking {
        val events = executeBridgedTool(core(tool { c ->
            c.mcpProgressReporter.report("one"); c.mcpProgressReporter.report("two")
            ToolCallResult.successTextResult("done")
        }), ToolCallParams(name = "t")).toList()
        assertEquals(listOf("one", "two"), events.filterIsInstance<BridgedOutcome.Progress>().map { it.message })
        assertEquals("done", ((events.last() as BridgedOutcome.Result).result.content.single() as ContentItem.Text).text)
    }

    @Test
    fun `a handler exception comes back as an error result`() = runBlocking {
        val last = executeBridgedTool(core(tool { error("boom") }), ToolCallParams(name = "t")).toList().last()
        val result = (last as BridgedOutcome.Result).result
        assertTrue(result.isError)
        assertTrue((result.content.first() as ContentItem.Text).text.contains("boom"))
    }

    @Test
    fun `the bridge session is removed afterwards`() = runBlocking {
        val c = core(tool { ToolCallResult.successTextResult("x") })
        val before = c.sessionManager.getAllSessions().size
        executeBridgedTool(c, ToolCallParams(name = "t")).toList()
        assertEquals(before, c.sessionManager.getAllSessions().size)
    }
}
```

Check the `McpServerCore` constructor with
`rg -n "class McpServerCore\(" -A12 mcp-core/src/main/kotlin` and pass any other
required arguments it lists.

- [ ] **Step 2: Run the test and check that it fails**

Run: `./gradlew :ij-plugin:test --tests '*BridgedToolExecutorTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Add the registry overload**

In `McpToolRegistry`, rename the body of `callTool(params, session)` from
`val toolCallContext = ...` onwards into the new overload, and make the old
method build its token reporter and delegate:

```kotlin
suspend fun callTool(params: ToolCallParams, session: McpSession): ToolCallResult {
    // (existing tool lookup, logging and token-based reporter stay here)
    return callTool(params, session, progress)
}

suspend fun callTool(params: ToolCallParams, session: McpSession, progress: McpProgressReporter): ToolCallResult {
    val tool = tools[params.name]
        ?: return ToolCallResult(content = listOf(ContentItem.Text(text = "Tool not found: ${params.name}")), isError = true)
    val toolCallContext = ToolCallContext(params, session, progress)
    return try {
        tool.call(toolCallContext)
    } catch (e: ToolCallErrorException) {
        e.toolCallResult
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolCallResult.builder()
            .addTextContent("Tool execution error: ${e.message}")
            .addTextContent("Stacktrace: " + e.stackTraceToString())
            .markAsError()
            .build()
    }
}
```

Keep the existing cancellation comment on the moved `catch`.

- [ ] **Step 4: Implement executeBridgedTool**

```kotlin
package com.jonnyzzz.mcpSteroid.server.split

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

sealed interface BridgedOutcome {
    data class Progress(val message: String) : BridgedOutcome
    data class Result(val result: ToolCallResult) : BridgedOutcome
}

/** Runs one tool call for a Split Mode frontend: its progress lines, then its result. */
fun executeBridgedTool(core: McpServerCore, params: ToolCallParams): Flow<BridgedOutcome> = channelFlow {
    val session = core.sessionManager.createSession()
    try {
        val progress = object : McpProgressReporter {
            override fun report(message: String) { trySend(BridgedOutcome.Progress(message)) }
        }
        send(BridgedOutcome.Result(core.toolRegistry.callTool(params, session, progress)))
    } finally {
        core.sessionManager.removeSession(session.id)
    }
}.buffer(Channel.UNLIMITED)
```

The flow is buffered without limit so that a progress burst never drops a
`trySend`. Progress lines are short, and a call makes a bounded number of them.

- [ ] **Step 5: Run the tests and check that they pass**

Run: `./gradlew :ij-plugin:test --tests '*BridgedToolExecutorTest'` then
`./gradlew :mcp-core:test`
Expected: PASS. `McpToolRegistryTest` still passes, which shows the token
reporter path is unchanged.

- [ ] **Step 6: Commit**

```bash
git add mcp-core/src/main/kotlin/com/jonnyzzz/mcpSteroid/mcp/McpToolRegistry.kt ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/split/BridgedToolExecutor.kt ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/split/BridgedToolExecutorTest.kt
git commit -m "feat(split): run a bridged tool call and stream its progress"
```

### Task 4: The `side` parameter on steroid_execute_code

**Files:**
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ExecuteCodeTool.kt:134-172` (a new param after `modal`)
- Modify: the golden schema files that `DevrigToolSpecsGoldenSchemaTest` compares against. Find them with `rg -ln "steroid_execute_code" mcp-steroid-server/src/test`.
- Test: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/VisionInputToolSpecSchemaTest.kt` is the pattern. Add `ExecuteCodeSideParamTest.kt` next to it.

**Interfaces:**
- Consumes: `SIDE_ARGUMENT = "side"` (Task 1). The literal stays `"side"` because `mcp-steroid-server` cannot see `ij-plugin`.
- Produces: an optional string enum `side`, values `frontend` and `backend`,
  with no default in the schema. When it is absent, the routing default applies.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExecuteCodeSideParamTest {
    @Test
    fun `execute_code advertises an optional side enum`() {
        val spec = ExecuteCodeToolSpec { error("not called") }
        val side = spec.inputSchema["properties"]!!.jsonObject["side"]!!.jsonObject
        assertEquals(listOf("frontend", "backend"), side["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        val required = spec.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertFalse("side" in required)
    }
}
```

Check the `ExecuteCodeToolSpec` constructor before running
(`rg -n "class ExecuteCodeToolSpec" mcp-steroid-server/src/main/kotlin`) and
match it.

- [ ] **Step 2: Run the test and check that it fails**

Run: `./gradlew :mcp-steroid-server:test --tests '*ExecuteCodeSideParamTest'`
Expected: FAIL, `properties.side` is null.

- [ ] **Step 3: Add the parameter**

After the `modal` parameter, following the style of the neighboring
declarations:

```kotlin
val side = InputSchemaElement.param("side")
    .description(
        "Split Mode only: where the script runs. 'backend' (default) holds the project model; " +
            "'frontend' is the JetBrains Client process, for client-only UI state. " +
            "In a regular IDE both run in the same process."
    )
    .cliSynopsis("split mode side: frontend or backend")
    .enumString(mapOf("frontend" to "frontend", "backend" to "backend"))
    .registerToSchema()
```

If `enumString` requires an enum type, declare
`enum class ExecutionSide(val wire: String) { FRONTEND("frontend"), BACKEND("backend") }`
in the same file and use `ExecutionSide.entries.associateBy { it.wire }`, as
`modal` does. The handler does not read the value, because routing uses the raw
arguments.

- [ ] **Step 4: Update the golden schemas and run the tests**

Run: `./gradlew :mcp-steroid-server:test :npx-kt:test`
The golden test fails and prints the new schema. Update the golden file with
exactly the printed `side` block, then run the same command again.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mcp-steroid-server/ npx-kt/
git commit -m "feat(execute-code): add a side parameter for Split Mode"
```

### Task 5: Roles in markers and backends[], and backend project keys on the frontend

**Files:**
- Modify: `devrig-common/src/main/kotlin/com/jonnyzzz/mcpSteroid/PidMarker.kt:33-34`
- Modify: `devrig-common/src/test/kotlin/com/jonnyzzz/mcpSteroid/PidMarkerTest.kt:165-185`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ServerUrlWriter.kt:39,85` and its caller in `SteroidsMcpServer.kt`
- Modify: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/ServerUrlWriterTest.kt`
- Modify: `mcp-steroid-server/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/BackendRef.kt:36-39`
- Modify: `mcp-steroid-server/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/BackendRefSerializationTest.kt`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ListProjectsToolHandler.kt:17-18,40-43`
- Modify: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ListWindowsToolHandler.kt:23-35`
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/split/SplitProjectKeysTest.kt`

**Interfaces:**
- Consumes: `SplitRole`, `currentSplitRole()`, `activeSplitFrontendBridge()` (Tasks 1-2).
- Produces:
  - `PidMarker.role: String? = null`, replacing `remoteDevelopmentBackend`.
  - `BackendRef.role: String? = null`. The in-IDE handlers set it, and devrig
    leaves it null.
  - `fun projectNameFor(project: Project): String` returns the backend key when
    a bridge knows the project, and the local key otherwise.
  - `fun localProjectNameFor(project: Project): String`: the local formula
    alone. Task 6 uses it on the backend.

- [ ] **Step 1: Write the failing tests**

`SplitProjectKeysTest` (platform test, because it needs a real `Project`):

```kotlin
package com.jonnyzzz.mcpSteroid.server.split

import com.intellij.openapi.project.Project
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.localProjectNameFor
import com.jonnyzzz.mcpSteroid.server.projectNameFor

class SplitProjectKeysTest : BasePlatformTestCase() {
    private fun bridge(key: String?) = object : SplitFrontendBridge {
        override suspend fun forward(params: ToolCallParams, progress: McpProgressReporter) = ToolCallResult.successTextResult("")
        override suspend fun refreshProjectKeys() = Unit
        override fun backendKeyFor(project: Project): String? = key
        override suspend fun backendSelf(): BackendRef? = null
    }

    fun testBackendKeyWinsWhenTheBridgeKnowsTheProject() {
        ExtensionTestUtil.maskExtensions(SPLIT_FRONTEND_BRIDGE_EP, listOf(bridge("backend-key")), testRootDisposable)
        assertEquals("backend-key", projectNameFor(project))
    }

    fun testUnknownProjectFallsBackToTheLocalKey() {
        ExtensionTestUtil.maskExtensions(SPLIT_FRONTEND_BRIDGE_EP, listOf(bridge(null)), testRootDisposable)
        assertEquals(localProjectNameFor(project), projectNameFor(project))
    }

    fun testNoBridgeUsesTheLocalKey() {
        assertEquals(localProjectNameFor(project), projectNameFor(project))
    }
}
```

In `PidMarkerTest`, replace the `remoteDevelopmentBackend` case with:

```kotlin
val backendMarker = samplePidMarker().copy(role = "backend")
val text = PidMarkerJson.encode(backendMarker)
assertTrue(text.contains("\"role\": \"backend\""), "role field missing: $text")
assertEquals("backend", PidMarkerJson.decode(text).role)
val olderMarker = PidMarkerJson.decode(text.replace(",\n  \"role\": \"backend\"", ""))
assertNull(olderMarker.role)
```

Keep the existing assertion style, messages and helpers in that file. Match the
exact whitespace of the encoded JSON by printing `text` once if the `replace`
does not match.

In `BackendRefSerializationTest`, add a case: a `BackendRef` with
`role = "frontend"` encodes a `"role":"frontend"` field, and one with
`role = null` encodes no `role` key. Follow the file's existing drift-gate
pattern.

In `ServerUrlWriterTest`, construct `ServerUrlWriter(role = SplitRole.BACKEND)`
and assert `marker.role == "backend"`.

- [ ] **Step 2: Run the tests and check that they fail**

Run: `./gradlew :devrig-common:test :mcp-steroid-server:test --tests '*BackendRef*' :ij-plugin:test --tests '*SplitProjectKeysTest' --tests '*ServerUrlWriterTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement**

`PidMarker.kt`: replace the `remoteDevelopmentBackend` line and its KDoc with:

```kotlin
/** `monolith`, `frontend` or `backend`: which part of a Split Mode IDE wrote this marker. Null from older plugins. */
val role: String? = null,
```

`ServerUrlWriter`: the constructor parameter becomes `private val role: SplitRole`,
and the marker gets `role = role.wire`. Its caller in `SteroidsMcpServer` passes
`currentSplitRole()` instead of `isRemoteDevBackend()`.

`BackendRef`: add `val role: String? = null` after `intellij`. Update its KDoc
to name the consumer: an agent in Split Mode needs to know which side each entry
is.

`ListProjectsToolHandler.kt`:

```kotlin
fun localProjectNameFor(project: Project): String =
    "${project.name}-${base36FixedWidth("project", project.basePath, project.name)}"

/** The backend's key when a Split Mode bridge knows [project], else [localProjectNameFor]. */
fun projectNameFor(project: Project): String =
    activeSplitFrontendBridge()?.backendKeyFor(project) ?: localProjectNameFor(project)
```

Keep the existing KDoc on `localProjectNameFor`. In `selfBackendRef()`, set
`role = currentSplitRole().wire`.

`ListWindowsToolHandler`: after it builds `backends`, add the backend's entry
when a bridge is active:

```kotlin
val bridgeRef = activeSplitFrontendBridge()?.backendSelf()
backends = backendsTable(listOfNotNull(self.selfBackendRef(), bridgeRef)),
```

Update any other `BackendRef(...)` or `ServerUrlWriter(...)` construction that
stops compiling:
`rg -n "BackendRef\(|ServerUrlWriter\(|remoteDevelopmentBackend" -g '*.kt'`.
Devrig's aggregating handlers keep `role = null`.

- [ ] **Step 4: Run the tests and check that they pass**

Run the Step 2 command, then `./gradlew :npx-kt:test`.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add devrig-common/ mcp-steroid-server/ ij-plugin/
git commit -m "feat(split): report the split role in markers and backends[]"
```

### Task 6: shared, backend and frontend content modules

**Files:**
- Modify: `settings.gradle.kts` (the RPC plugin repository and version; includes)
- Create: `ij-plugin/shared/build.gradle.kts`,
  `ij-plugin/shared/src/main/resources/mcp-steroid.shared.xml`,
  `ij-plugin/shared/src/main/kotlin/com/jonnyzzz/mcpSteroid/split/SteroidBridgeApi.kt`
- Create: `ij-plugin/backend/build.gradle.kts`,
  `ij-plugin/backend/src/main/resources/mcp-steroid.backend.xml`,
  `ij-plugin/backend/src/main/kotlin/com/jonnyzzz/mcpSteroid/split/backend/SteroidBridgeApiImpl.kt`,
  `ij-plugin/backend/src/main/kotlin/com/jonnyzzz/mcpSteroid/split/backend/SteroidBridgeApiProvider.kt`
- Create: `ij-plugin/frontend/build.gradle.kts`,
  `ij-plugin/frontend/src/main/resources/mcp-steroid.frontend.xml`,
  `ij-plugin/frontend/src/main/kotlin/com/jonnyzzz/mcpSteroid/split/frontend/RpcSplitFrontendBridge.kt`
- Modify: `ij-plugin/build.gradle.kts` (`pluginModule` dependencies and the split-mode switch)
- Modify: `ij-plugin/src/main/resources/META-INF/plugin.xml` (`<content>`)
- Test: `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/split/ContentModulesConsistencyTest.kt`

**Interfaces:**
- Consumes:
  - `executeBridgedTool`, `BridgedOutcome` (Task 3);
  - `SteroidsMcpServer.ensureToolsRegistered()` and `getServer()` (Task 2);
  - `SplitFrontendBridge` (Task 2);
  - `localProjectNameFor`, `describeSelfBackend().selfBackendRef()` (Task 5).
- Produces:
  - `@Rpc interface SteroidBridgeApi : RemoteApi<Unit>` with
    `suspend fun callTool(request: BridgeToolRequest): Flow<BridgeEvent>`,
    `suspend fun projectKeys(): List<ProjectKeyEntry>` and
    `suspend fun backendSelfJson(): String`.
  - `@Serializable data class BridgeToolRequest(val name: String, val argumentsJson: String, val trustedArgumentsJson: String)`.
    This refines the spec's `callTool(tool, argsJson, meta)` into one request
    object that carries the transient trusted arguments too.
  - `@Serializable sealed interface BridgeEvent` with `Progress(message: String)`
    and `Result(toolCallResultJson: String)`.
  - `@Serializable data class ProjectKeyEntry(val projectName: String, val projectId: ProjectId)`.

- [ ] **Step 1: Write the failing consistency test**

```kotlin
package com.jonnyzzz.mcpSteroid.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContentModulesConsistencyTest {
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()
    private val declared = Regex("""<module\s+name="([^"]+)"""").findAll(
        pluginXml.substringAfter("<content>").substringBefore("</content>")
    ).map { it.groupValues[1] }.toSet()
    private val descriptors = listOf("shared", "backend", "frontend").associateWith {
        File("$it/src/main/resources").listFiles { f -> f.name.startsWith("mcp-steroid.") && f.name.endsWith(".xml") }
            ?.map { f -> f.name.removeSuffix(".xml") }.orEmpty()
    }

    @Test
    fun `every content module has a descriptor and every descriptor is declared`() {
        assertEquals(setOf("mcp-steroid.shared", "mcp-steroid.backend", "mcp-steroid.frontend"), declared)
        assertEquals(declared, descriptors.values.flatten().toSet())
    }

    @Test
    fun `module descriptors use dependencies, never depends`() {
        for ((dir, names) in descriptors) for (name in names) {
            val xml = File("$dir/src/main/resources/$name.xml").readText()
            assertTrue("$name must not use <depends>", !xml.contains("<depends"))
        }
    }
}
```

- [ ] **Step 2: Run the test and check that it fails**

Run: `./gradlew :ij-plugin:test --tests '*ContentModulesConsistencyTest'`
Expected: FAIL, `declared` is empty.

- [ ] **Step 3: Gradle wiring**

`settings.gradle.kts`, in `pluginManagement`:

```kotlin
repositories {
    // (existing entries stay)
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
}
plugins {
    id("rpc") version "2.3.20-RC2-0.1"
}
```

and next to `include(":ij-plugin")`:

```kotlin
include(":ij-plugin:shared")
include(":ij-plugin:backend")
include(":ij-plugin:frontend")
```

`ij-plugin/shared/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.intellij.platform.module")
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("rpc")
}
dependencies {
    intellijPlatform {
        intellijIdea(rootProject.extra["mcp.platform.version"] as String)
    }
}
```

Use the platform version and repository setup that `ij-plugin/build.gradle.kts`
already uses (`targetIdeVersion`, around line 38). If `intellijIdea(...)` is not
how `ij-plugin` declares its target, copy its exact `intellijPlatform {}` target
declaration, and put the shared value in `buildSrc`'s `McpSteroidIdeTargets` so
the four projects cannot drift.

`ij-plugin/backend/build.gradle.kts`: the same plugins without `rpc`
(the template applies it to every module; keep `rpc` if compilation of the
provider needs it), plus:

```kotlin
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
    }
    implementation(project(":ij-plugin:shared"))
    compileOnly(project(":ij-plugin"))
}
```

`ij-plugin/frontend/build.gradle.kts`: the same, with
`bundledModule("intellij.platform.frontend")` and
`implementation(project(":ij-plugin:shared"))`, `compileOnly(project(":ij-plugin"))`.

`compileOnly(project(":ij-plugin"))` gives the content modules core's classes at
compile time. At runtime the main module's classes are visible to its content
modules. If Gradle reports a cycle (`ij-plugin` depends on the modules through
`pluginModule`), move the compile-time dependency to core's main source set
output: `compileOnly(files(project(":ij-plugin").sourceSets["main"].output))`,
together with core's own `compileOnly` dependencies (`mcp-core`,
`mcp-steroid-server`).

`ij-plugin/build.gradle.kts`, in the `dependencies { intellijPlatform { ... } }`
block:

```kotlin
pluginModule(implementation(project(":ij-plugin:shared")))
pluginModule(implementation(project(":ij-plugin:backend")))
pluginModule(implementation(project(":ij-plugin:frontend")))
```

and in `intellijPlatform { }`:

```kotlin
// -Pmcp.splitMode=true runs runIde as a frontend + backend pair (Run IDE (Split Mode)).
splitMode = providers.gradleProperty("mcp.splitMode").map(String::toBoolean).orElse(false)
pluginInstallationTarget = org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware.PluginInstallationTarget.BOTH
```

- [ ] **Step 4: Descriptors and plugin.xml**

`plugin.xml`, after `<depends>`:

```xml
<content>
    <module name="mcp-steroid.shared" loading="required"/>
    <module name="mcp-steroid.backend"/>
    <module name="mcp-steroid.frontend"/>
</content>
```

`mcp-steroid.shared.xml`:

```xml
<idea-plugin>
</idea-plugin>
```

`mcp-steroid.backend.xml`:

```xml
<idea-plugin>
    <dependencies>
        <module name="intellij.platform.backend"/>
        <module name="intellij.platform.kernel.backend"/>
        <module name="mcp-steroid.shared"/>
    </dependencies>
    <extensions defaultExtensionNs="com.intellij">
        <platform.rpc.backend.remoteApiProvider
            implementation="com.jonnyzzz.mcpSteroid.split.backend.SteroidBridgeApiProvider"/>
    </extensions>
</idea-plugin>
```

`mcp-steroid.frontend.xml`:

```xml
<idea-plugin>
    <dependencies>
        <module name="intellij.platform.frontend"/>
        <module name="mcp-steroid.shared"/>
    </dependencies>
    <extensions defaultExtensionNs="com.jonnyzzz.mcpSteroid">
        <splitFrontendBridge implementation="com.jonnyzzz.mcpSteroid.split.frontend.RpcSplitFrontendBridge"/>
    </extensions>
</idea-plugin>
```

The `defaultExtensionNs` must match the extension point's qualified name from
Task 2 (`com.jonnyzzz.mcpSteroid.splitFrontendBridge`).

- [ ] **Step 5: The RPC contract (shared)**

```kotlin
@file:Suppress("UnstableApiUsage")

package com.jonnyzzz.mcpSteroid.split

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Split Mode frontend -> backend bridge. The frontend hosts the agent's MCP endpoint. */
@Rpc
interface SteroidBridgeApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): SteroidBridgeApi =
            RemoteApiProviderService.resolve(remoteApiDescriptor<SteroidBridgeApi>())
    }

    /** Runs one MCP tool call on the backend: zero or more [BridgeEvent.Progress], then one [BridgeEvent.Result]. */
    suspend fun callTool(request: BridgeToolRequest): Flow<BridgeEvent>

    /** The backend's `project_name` key for each open project, with the platform's shared [ProjectId]. */
    suspend fun projectKeys(): List<ProjectKeyEntry>

    /** The backend's `backends[]` entry as JSON (`BackendRef`, which lives in core). */
    suspend fun backendSelfJson(): String
}

@Serializable
data class BridgeToolRequest(val name: String, val argumentsJson: String, val trustedArgumentsJson: String)

@Serializable
sealed interface BridgeEvent {
    @Serializable data class Progress(val message: String) : BridgeEvent
    @Serializable data class Result(val toolCallResultJson: String) : BridgeEvent
}

@Serializable
data class ProjectKeyEntry(val projectName: String, val projectId: ProjectId)
```

- [ ] **Step 6: The backend provider**

```kotlin
@file:Suppress("UnstableApiUsage")

package com.jonnyzzz.mcpSteroid.split.backend

import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectIdOrNull
import com.intellij.platform.rpc.backend.RemoteApiProvider
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.server.describeSelfBackend
import com.jonnyzzz.mcpSteroid.server.localProjectNameFor
import com.jonnyzzz.mcpSteroid.server.split.BridgedOutcome
import com.jonnyzzz.mcpSteroid.server.split.executeBridgedTool
import com.jonnyzzz.mcpSteroid.split.*
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class SteroidBridgeApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<SteroidBridgeApi>()) { SteroidBridgeApiImpl() }
    }
}

internal class SteroidBridgeApiImpl : SteroidBridgeApi {
    override suspend fun callTool(request: BridgeToolRequest): Flow<BridgeEvent> {
        val server = SteroidsMcpServer.getInstance().also { it.ensureToolsRegistered() }
        val arguments = McpJson.decodeFromString(JsonObject.serializer(), request.argumentsJson)
        val params = ToolCallParams(
            name = request.name,
            arguments = arguments,
            rawArguments = buildJsonObject { put("name", request.name); put("arguments", arguments) },
            trustedArguments = McpJson.decodeFromString(JsonObject.serializer(), request.trustedArgumentsJson),
        )
        return executeBridgedTool(server.getServer(), params).map { outcome ->
            when (outcome) {
                is BridgedOutcome.Progress -> BridgeEvent.Progress(outcome.message)
                is BridgedOutcome.Result -> BridgeEvent.Result(
                    McpJson.encodeToString(ToolCallResult.serializer(), outcome.result)
                )
            }
        }
    }

    override suspend fun projectKeys(): List<ProjectKeyEntry> =
        ProjectManager.getInstance().openProjects.mapNotNull { project ->
            project.projectIdOrNull()?.let { ProjectKeyEntry(localProjectNameFor(project), it) }
        }

    override suspend fun backendSelfJson(): String =
        McpJson.encodeToString(BackendRef.serializer(), describeSelfBackend().selfBackendRef())
}
```

The backend has no bridge extension, so `projectNameFor` would give the same
key. `localProjectNameFor` makes that explicit.

- [ ] **Step 7: The frontend bridge**

```kotlin
@file:Suppress("UnstableApiUsage")

package com.jonnyzzz.mcpSteroid.split.frontend

import com.intellij.openapi.project.Project
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.projectIdOrNull
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.split.SplitFrontendBridge
import com.jonnyzzz.mcpSteroid.split.BridgeEvent
import com.jonnyzzz.mcpSteroid.split.BridgeToolRequest
import com.jonnyzzz.mcpSteroid.split.SteroidBridgeApi
import fleet.rpc.client.durable
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

internal class RpcSplitFrontendBridge : SplitFrontendBridge {
    private val keys = ConcurrentHashMap<ProjectId, String>()

    override suspend fun forward(params: ToolCallParams, progress: McpProgressReporter): ToolCallResult {
        val request = BridgeToolRequest(
            name = params.name,
            argumentsJson = McpJson.encodeToString(JsonObject.serializer(), params.arguments),
            trustedArgumentsJson = McpJson.encodeToString(JsonObject.serializer(), params.trustedArguments),
        )
        var result: ToolCallResult? = null
        durable {
            SteroidBridgeApi.getInstance().callTool(request).collect { event ->
                when (event) {
                    is BridgeEvent.Progress -> progress.report(event.message)
                    is BridgeEvent.Result -> result = McpJson.decodeFromString(ToolCallResult.serializer(), event.toolCallResultJson)
                }
            }
        }
        return result ?: error("the backend closed the call without a result")
    }

    override suspend fun refreshProjectKeys() {
        val entries = durable { SteroidBridgeApi.getInstance().projectKeys() }
        keys.clear()
        entries.forEach { keys[it.projectId] = it.projectName }
    }

    override fun backendKeyFor(project: Project): String? = project.projectIdOrNull()?.let { keys[it] }

    override suspend fun backendSelf(): BackendRef? = runCatching {
        McpJson.decodeFromString(BackendRef.serializer(), durable { SteroidBridgeApi.getInstance().backendSelfJson() })
    }.getOrNull()
}
```

`durable {}` retries a call across a reconnect. A retried `callTool` would run
the tool on the backend twice, which is wrong for `execute_code`. Before relying
on `durable` for `forward`, read its KDoc (`search_symbol durable` in the
IntelliJ monorepo, `fleet/rpc`) to confirm when it retries. If it retries a flow
that has already emitted, drop `durable` from `forward` only. Then a lost
connection surfaces as the Task 2 error ("backend is not connected"), which the
Review Focus accepts. Keep `durable` for the idempotent `projectKeys` and
`backendSelfJson`. Record the finding in a one-line comment on `forward`.

- [ ] **Step 8: Build and run the tests**

Run: `./gradlew :ij-plugin:test --tests '*ContentModulesConsistencyTest'`
Expected: PASS.

Break the gate once: rename `mcp-steroid.frontend.xml` to
`mcp-steroid.frontend2.xml` and run it again.
Expected: FAIL, because the sets differ. Rename it back and run again.
Expected: PASS.

Run: `./gradlew :ij-plugin:buildPlugin -x test`
Expected: BUILD SUCCESSFUL. Then check the layout:
`unzip -l ij-plugin/build/distributions/mcp-steroid-plus-*.zip | rg "modules|shared|frontend|backend"`
Expected: the three module jars (under `lib/modules/` or `lib/`), each with its
`mcp-steroid.*.xml` at the jar root. Check one with
`unzip -l <jar> | rg "mcp-steroid\..*xml"`.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts ij-plugin/build.gradle.kts ij-plugin/shared ij-plugin/backend ij-plugin/frontend ij-plugin/src/main/resources/META-INF/plugin.xml ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/split/ContentModulesConsistencyTest.kt
git commit -m "feat(split): add shared, backend and frontend content modules with the bridge RPC"
```

### Task 7: Split-mode rules in AGENTS.md

**Files:**
- Modify: `CLAUDE.md`. `AGENTS.md` is a symlink to it, so edit `CLAUDE.md`.

- [ ] **Step 1: Add a "Split mode" section**

Put it after the section that describes the plugin module layout (find it with
`rg -n "^## " CLAUDE.md`). Content:

```markdown
## Split mode

MCP Steroid Plus is a split plugin. The main module (`ij-plugin`) holds all the
existing code and loads on every side. Three content modules sit on top:

| Module | Loads on | Holds |
| -- | -- | -- |
| `mcp-steroid.shared` | every side | the `SteroidBridgeApi` RPC contract and its payload types, nothing else |
| `mcp-steroid.backend` | backend and monolith | the `SteroidBridgeApi` provider |
| `mcp-steroid.frontend` | frontend and monolith | `RpcSplitFrontendBridge` |

In Split Mode the JetBrains Client hosts the agent's MCP endpoint. `RoutedTool`
forwards backend-side tools through the bridge. `ROUTED_TOOLS` in
`SplitRouting.kt` is the routing table.

Files that change together:

- `<content>` in `plugin.xml` and the module descriptors
  (`ContentModulesConsistencyTest` enforces it)
- a new MCP tool and its `ROUTED_TOOLS` entry (an unrouted tool fails at call
  time)
- `SteroidBridgeApi` and `SteroidBridgeApiImpl`

RPC rules:

- `@Rpc` interfaces with `suspend` methods only. Payloads are `@Serializable`.
  `shared` stays free of frontend-only and backend-only APIs.
- Never call RPC on the EDT. Use `durable {}` only for idempotent calls.
- Batch; do not make chatty calls.

Checks when adding or moving split code:

- Run the inspection "Plugin DevKit | Code | Frontend and Backend API Usage".
- Run `./gradlew :ij-plugin:runIde -Pmcp.splitMode=true` to start a frontend
  and backend pair. To feel latency, enable internal mode
  (`-Didea.is.internal=true`) and raise Direct Ping in the Split Mode widget.
```

Apply the repo's 80-column wrapping only if `CLAUDE.md` already hard-wraps its
prose. Otherwise match its style.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(agents): describe the split plugin layout and its rules"
```

### Task 8: Verification

**Files:** none (verification only). Fix any finding in the task that owns the
code and commit there.

- [ ] **Step 1: Full unit tests and the verifier**

Run: `./gradlew :ij-plugin:test :mcp-core:test :mcp-steroid-server:test :devrig-common:test :npx-kt:test`
Expected: PASS.

Run: `./gradlew :ij-plugin:verifyPlugin`
Expected: no compatibility problems on the configured 261, 262 and 263 targets.
Experimental API usage (`ProjectId`, RPC) is reported as a warning, not an
error. List those warnings in the final report.

- [ ] **Step 2: The frontend/backend API inspection**

Run the "Frontend and Backend API Usage" inspection on `ij-plugin/shared`,
`ij-plugin/backend` and `ij-plugin/frontend`. Use the IDE's inspection run
through MCP Steroid on the IDE that has this repository open, or `get_file_problems`
per file. Expected: no findings. Fix any finding in Task 6's files.

- [ ] **Step 3: Monolith live check**

Build (`./gradlew :ij-plugin:buildPlugin -x test`), then install into IntelliJ
IDEA 2026.2.3 through JetDesk:
`tools/node.cmd scripts/steroid/plugin/cli.js install --ide 8e3a2d52 --zip <abs zip> --restart`.
Through `mcp-steroid`, check:

- `steroid_list_projects`: projects listed, `backends[0].role == "monolith"`.
- `steroid_execute_code` prints the plugin version.
- `steroid_take_screenshot` returns an image.
- `press:ALT+1` reports `ActivateProjectToolWindow`.
- `click:CTRL+Left` on a reference reports `GotoDeclaration`.

Expected: the same behavior as 0.108.

- [ ] **Step 4: Split Mode live check**

Start IntelliJ IDEA 2026.3 in Split Mode on this machine: Toolbox "Run in Split
mode", or `./gradlew :ij-plugin:runIde -Pmcp.splitMode=true`. Install the zip
into both sides by hand. For the Toolbox case, find each side's plugins
directory from its marker's `pluginPath` in `~/.mcp-steroid/markers/`.
Connect to the endpoint whose marker has `"role": "frontend"`. Check:

- `steroid_list_projects` returns the backend's keys, and `backends[0].role == "backend"`.
- `steroid_list_windows` returns client windows labeled with those same keys,
  and `backends[]` has both a `frontend` and a `backend` entry.
- `steroid_execute_code` with `println(com.intellij.platform.ide.productMode.IdeProductMode.isBackend)`
  prints `true`. With `side=frontend` it prints `false`.
- A long `execute_code` (a loop that prints 5 lines one second apart) streams
  its progress and then returns.
- `steroid_take_screenshot` shows the client window.
- `press:ALT+1` toggles the Project tool window in the client.
- `click:CTRL+Left` navigates.

Then enable internal mode, set Direct Ping to 300 ms, and repeat the long
`execute_code`. Expected: it still streams and finishes.

- [ ] **Step 5: Backend with no client**

If Docker is available, run the devrig Remote Development E2E:
`./gradlew :test-integration:test --tests '*DevrigRemoteDevelopment*'`.
Expected: PASS. If Docker is not available, record that it was skipped and why.

- [ ] **Step 6: Report**

Summarize what was verified live, what was skipped and why, and the verifier's
experimental-API warnings.
