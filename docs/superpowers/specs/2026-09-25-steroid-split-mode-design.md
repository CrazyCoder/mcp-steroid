# MCP Steroid Plus in Split Mode: one endpoint, split plugin

Sub-project 1 of the split-mode effort. Sub-projects 2-4 (JetDesk installer,
split-mode input fixes, agent guidance) get their own specs after this one lands.

## Goal

An agent reproducing a Remote Development ticket talks to one MCP endpoint. It
works with the project on the backend and acts as the user on the frontend
without choosing a side per call. A monolithic IDE behaves exactly as today. A
backend with no client attached (devrig) keeps working.

## Background

- The plugin is one module. Its only mode check, `IdeRunMode`
  (`REMOTE_DEV_BACKEND`), is logged and written to the marker, and nothing reads
  it. Installed on both sides, each process runs its own server. No tool says
  which side it runs on.
- A field test in Split Mode (both sides installed): backend work matched a
  monolith. The frontend gave screenshots and window state, but input failed,
  and the agent had to pick one of two servers for every call.
- Platform facts:
  - A split plugin has shared, frontend and backend content modules. A module
    loads only where its `intellij.platform.frontend` or
    `intellij.platform.backend` dependency is satisfied. A monolith satisfies
    both.
  - Frontend and backend talk through platform RPC with `@Serializable`
    payloads. In a monolith the call runs in-process.
  - Plugin sync between the sides works only for JetBrains Marketplace plugins.
    Steroid Plus ships through its own `updatePlugins.xml`, so the same zip has
    to be installed on both sides. That is sub-project 2.

## Decisions

1. The plugin becomes a split plugin, and the agent sees one endpoint.
2. In Split Mode the frontend (JetBrains Client) hosts that endpoint. It runs on
   the same machine as the agent and the user, even when the backend is remote.
3. The existing code stays in the main plugin module. It loads on every side and
   acts as "core". Three thin content modules add generic tool forwarding. Tool
   handlers do not change.

## Modules and loading

| Module | Depends on | Contents |
| -- | -- | -- |
| main (`ij-plugin`, "core") | `com.intellij.modules.platform` | Everything that exists today, plus `ToolRouter` |
| `shared` (`loading="required"`) | platform | `SteroidBridgeApi` RPC contract and payload types |
| `backend` | `intellij.platform.backend`, `intellij.platform.kernel.backend`, `intellij.platform.rpc.backend`, `shared` | `SteroidBridgeApi` implementation and its `remoteApiProvider` registration |
| `frontend` | `intellij.platform.frontend`, `shared` | The split-mode routing policy for `ToolRouter` |

What each process loads and does:

| Process | Modules | MCP server | Routing |
| -- | -- | -- | -- |
| Monolith | main, shared, backend, frontend | yes | all tools run locally |
| Split frontend (JetBrains Client) | main, shared, frontend | yes, the one agents use | backend-side tools go over RPC |
| Split backend | main, shared, backend | yes, for devrig and no-client use | all tools run locally |

The routing policy looks at `IdeProductMode`: it forwards only when the process
is a split frontend.

### Routing table

| Tool | Side |
| -- | -- |
| `steroid_list_projects` | backend |
| `steroid_open_project` | backend |
| `steroid_execute_code` | backend, or the frontend with `side=frontend` |
| `steroid_execute_feedback` | backend |
| `steroid_list_windows` | frontend |
| `steroid_take_screenshot` | frontend |
| `steroid_input` | frontend |
| resource and prompt reads | frontend (local) |

`steroid_execute_code` gains an optional `side` parameter, `frontend` or
`backend`, defaulting to `backend`. On a monolith both values run locally. On a
backend endpoint, `backend` runs locally and `frontend` is an error that says
the backend cannot run code in the client, whether or not one is attached.

## RPC contract (`shared`)

```kotlin
@Rpc
interface SteroidBridgeApi : RemoteApi<Unit> {
    suspend fun callTool(tool: String, argsJson: String, meta: BridgeCallMeta): Flow<BridgeEvent>
    suspend fun projectKeys(): List<ProjectKeyEntry>
}

@Serializable data class BridgeCallMeta(val clientRequestId: String)
@Serializable sealed interface BridgeEvent {
    @Serializable data class Progress(val json: String) : BridgeEvent
    @Serializable data class Result(val toolCallResultJson: String) : BridgeEvent
}
@Serializable data class ProjectKeyEntry(val projectName: String, val projectId: ProjectId)
```

- The backend implementation resolves the tool's handler in core, runs it, and
  emits `Progress` for each MCP progress notification and one `Result` at the
  end. A handler exception becomes a `Result` carrying an MCP error.
- The frontend calls it only from coroutines, never on the EDT, and wraps it in
  `durable {}` so a call survives a reconnect.
- `ProjectId` is the platform's shared, `@Serializable` project identity
  (`Project.projectId()`, `ProjectId.findProject()`; public, experimental).

## Project identity and output

- **Project keys.** `project_name` keys stay computed on the backend, which owns
  the project model. The frontend calls `projectKeys()` to map a key to its own
  `Project` for screenshot and input, and to label windows in `list_windows`
  with backend keys.
- **Roles.** Each `backends[]` entry gets `role`: `monolith`, `frontend` or
  `backend`. In Split Mode, `list_projects` is answered by the backend, which
  owns the projects. `list_windows` lists both sides, because its windows belong
  to the frontend and their project keys come from the backend.
  Forwarded responses keep the backend's `backend_name`, so `execution_id` and
  execution storage point at the process that ran the code.
- **Markers.** They gain `role` with the same values. It replaces
  `remoteDevelopmentBackend`, which nothing reads. JetDesk (sub-project 2) uses
  `role` to register the frontend endpoint as the agent's endpoint in Split
  Mode, and the backend one only on request (see
  [Direct backend endpoint](#direct-backend-endpoint)).

## Errors

- If the backend is unreachable or reconnecting, `durable {}` retries within the
  tool's existing timeout. When that runs out, the tool returns an error that
  says the backend is not connected. It never hangs.
- An unknown tool name in `ToolRouter` fails loudly. It never falls back to
  running locally.

## Direct backend endpoint

The backend keeps its own MCP endpoint in Split Mode and writes a marker with
`role` `backend`. It is a troubleshooting path for when the frontend cannot
reach the backend: a broken or reconnecting Remote Development connection makes
every forwarded tool fail with "backend not connected", and only a direct
connection can still run code there to inspect the session state, logs, threads
and open projects.

- The plugin needs no change for this. The endpoint and its marker exist on
  every backend.
- JetDesk (sub-project 2) never registers it by default. The agent keeps one
  endpoint. On request, JetDesk registers it as a second, separately named
  server (for example `mcp-steroid-backend`).
- When the backend runs on the agent's machine, JetDesk reads the URL from the
  backend marker. When it is remote, the endpoint listens on that machine's
  localhost: the agent runs there, or reaches it through an SSH tunnel. JetDesk
  documents the tunnel and does not set it up.
- The backend is headless. `execute_code`, `list_projects` and `open_project`
  work there. `take_screenshot`, `input` and `list_windows` have no frames to
  act on. The agent guidance (sub-project 4) says so.

## Build

- The plugin uses Kotlin 2.3.20 and IPGP 2.18.1. The template's `rpc` Gradle
  plugin `2.3.20-RC2-0.1` matches that Kotlin, so no Kotlin upgrade is needed.
- New Gradle subprojects `ij-plugin/shared`, `ij-plugin/frontend` and
  `ij-plugin/backend`, each with the IPGP `module` plugin, added through
  `pluginModule(implementation(project(...)))`. `shared` and `frontend` apply
  `rpc` and serialization. `backend` adds the bundled backend, kernel and RPC
  modules.
- `intellijPlatform { splitMode = true }` with installation target `BOTH`.
  `generateSplitModeRunConfigurations` provides "Run IDE (Split Mode)".
- Descriptors: `plugin.xml` gets the `<content>` block. The new modules register
  their services and extensions in their own descriptors (`<module>.xml`
  directly under `src/main/resources/`), using `<dependencies>`, not
  `<depends>`.
- Since-build stays 261. The plugin verifier covers 261, 262 and 263.

Kilo Code puts every registration in module descriptors and keeps `plugin.xml`
for wiring only. This design keeps the existing code and registrations in the
main module on purpose. The main module already loads on every side, so moving
100+ files into a content module would be churn for no behavior change.

## Project rules (`AGENTS.md`)

Add a "Split mode" section adapted from Kilo Code's JetBrains `AGENTS.md`:

- Placement: core for what exists, backend for RPC providers, frontend for
  routing and the server policy, shared for contracts only.
- Files that must change together:
  - `<content>` entries and the module descriptors;
  - the routing table and the tool list;
  - the `SteroidBridgeApi` contract and its provider.
- RPC rules:
  - `@Rpc` interfaces with `suspend` methods only;
  - `@Serializable` payloads;
  - never call RPC on the EDT;
  - `durable {}` for long-lived calls and flows;
  - batch rather than make chatty calls.
- Run "Plugin DevKit | Code | Frontend and Backend API Usage" when adding or
  moving split code.
- Test in "Run IDE (Split Mode)". Emulate latency with the Split Mode widget's
  Direct Ping field (internal mode).

Kilo's Swing and UI-style guidance does not apply: the plugin's only UI is a
settings page. Its Kotlin UI DSL material belongs to sub-project 4.

## Testing

Unit tests (`:ij-plugin:test`, no IDE downloads):

- `ToolRouter`:
  - every tool goes to the expected side in each routing mode;
  - an unknown tool fails.
- `callTool` through in-process RPC on a monolith:
  - progress events and the final result match a direct handler call;
  - a handler exception comes back as an MCP error.
- `projectKeys`:
  - a known key resolves to its `Project` and `ProjectId`;
  - an unknown key is reported as unknown.
- A descriptor consistency test: every `<content>` module has a descriptor, and
  every descriptor is listed. Break it once and watch it fail.

Build gates:

- `:ij-plugin:buildPlugin`.
- The plugin verifier on 261, 262 and 263.
- "Frontend and Backend API Usage" is clean on the new modules.

Live checks:

1. **Monolith**, IntelliJ IDEA 2026.2.3: `list_projects`, `execute_code`,
   screenshot, `press:ALT+1`, `click:CTRL+Left` (Go to Declaration) and
   `open_project` behave as in 0.108.
2. **Split Mode on this machine**, IntelliJ IDEA 2026.3 through "Run IDE (Split
   Mode)" or Toolbox "Run in Split mode", with the zip installed on both sides by
   hand. Through the frontend endpoint only:
   - `list_projects` returns backend keys;
   - `execute_code` runs on the backend;
   - `side=frontend` runs on the client;
   - the screenshot shows the client window;
   - `press:ALT+1` toggles the Project tool window in the client;
   - `click:CTRL+Left` navigates.

   Repeat `execute_code` with Direct Ping raised: it still streams and finishes.
3. **Backend with no client**: the devrig remote-development E2E stays green.

## Out of scope

- JetDesk discovery of the client's plugins directory, installing on both sides,
  and `.mcp.json` routing, including the opt-in direct backend server
  (sub-project 2).
- Input on Lux pages, popup menu items, and safe Settings opening
  (sub-project 3).
- Split-mode prompts and resources for agents, and JetDesk docs (sub-project 4).
