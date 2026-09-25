Split Mode: what runs on the client and what runs on the backend

Which tools and APIs run in the JetBrains Client and which on the Remote Development backend, the side parameter, and how backend UI reaches the user.

# Split Mode (Remote Development)

In Split Mode the IDE is two processes: the **JetBrains Client** (the frontend), which shows the windows the
user works in, and the **Remote Development backend**, which holds the project. The backend can run on
another machine. MCP Steroid runs in both. Agents connect to the client's endpoint, and it forwards
project work to the backend.

## Tell which setup you are connected to

`steroid_list_windows` lists every process it spoke to in `backends[]`, each with a `role`:

- a regular IDE: one entry, `monolith`;
- Split Mode through the client's endpoint: two entries, `frontend` and `backend`;
- a Remote Development backend's own endpoint: one entry, `backend`.

`steroid_list_projects` is always answered by the process that holds the projects, so through the client's
endpoint it shows only the `backend` entry. Use `steroid_list_windows` to tell the setups apart.

Inside a script, ask the platform:

```kotlin
import com.intellij.platform.ide.productMode.IdeProductMode
import com.intellij.platform.runtime.product.ProductMode

// ProductMode is compared by identity: it is not an enum on every supported build.
val side = when (IdeProductMode.getInstance().currentMode) {
    ProductMode.FRONTEND -> "JetBrains Client (frontend)"
    ProductMode.BACKEND -> "Remote Development backend"
    else -> "regular IDE (monolith)"
}
println("This script runs in the $side")
```

## Where each tool runs

Through the client's endpoint:

| Tool | Runs on |
|---|---|
| `steroid_list_projects`, `steroid_open_project`, `steroid_execute_feedback` | backend |
| `steroid_execute_code` | backend, or the client with `side=frontend` |
| `steroid_list_windows`, `steroid_take_screenshot`, `steroid_input` | client |
| `steroid_fetch_resource` | client |

- `project_name` keys come from the backend, and every tool accepts them, including the UI tools that run
  on the client.
- `steroid_open_project` opens the project on the backend, and the client opens a window for it. Closing
  the project on the backend closes that window.
- A forwarded call reports the backend's `backend_name`, because that process ran it.

## Pick the side for `steroid_execute_code`

Scripts run on the backend unless you pass `side=frontend`. In a regular IDE both values run in the same
process, so a script that passes `side=frontend` runs there too.

| Work | Side |
|---|---|
| PSI, indexes, VFS, documents, modules, project services, builds, tests, debugger | backend (default) |
| Opening files and editing text: the client's editor shows the change and the caret | backend (default) |
| Tool window visibility and layout, focus, the windows and popups the client owns | `frontend` |
| Controls inside a dialog or Settings page that the backend owns (see below) | backend (default) |

What the client does **not** have: its `project` has no modules and no content roots, its `basePath` is a
folder under the client's configuration directory, and its index finds no project files. The files open
in its editors are `ThinClientVirtualFile` copies. Never read or search the project from a `side=frontend`
script.

What the backend changes but the user does not see: the backend keeps its own copies of the frames and
tool windows. `ToolWindowManager` on the backend reports and changes only those copies, so show or hide a
tool window with `side=frontend`.

Many action IDs exist on both sides. In the client, those that do project work (`ReformatCode`,
`FindUsages`, `Vcs.Push`) are `BackendDelegatingAction` stubs, and the implementation is on the backend.

## How backend UI reaches the user

- **Editors.** A file the backend opens in `FileEditorManager` opens in the client. Document edits and caret
  moves on the backend show up there, but not at once: text typed with `steroid_input` right after a
  backend caret move can land at the old caret. Read the caret in a `side=frontend` script before typing.
- **Tool windows whose content the backend draws**, such as the Commit tool window in 2026.1, hold a
  `LuxFrontendPanel` in the client, and the client's focus owner is that panel. Read the focused control and
  the other controls from a backend script. Keys pressed in the client reach them.
- **Dialogs** that a backend script or action opens appear in the client as a modal dialog that holds only
  a `LuxFrontendPanel`: a picture of the backend's dialog. The buttons, fields and checkboxes exist only on
  the backend. Find them and send them events from a backend script. Their screen coordinates match the
  client's picture, so a screenshot and `steroid_input` on the client reach them too.
- **Settings** opens in the client. Its tree lists the pages of both sides, and a page that exists on both
  is tagged `Client` or `Host`. A host page is drawn by Lux: its controls live on the backend, in a
  `LuxHostPanelDialog` window whose bounds cover the page. Rows scrolled out of view there have screen
  coordinates below the page and need scrolling first.
- **List popups** from the backend become native client popups. Their rows are models, not strings, and
  the UI model's `visible_text` is empty for their list: read the rows with `JListTextFixture` from the
  ui-driving recipe. Choosing a row in the client runs the backend's step.

## Modal dialogs and the `modal` option

The default `modal=smart_non_modal` checks only the side the script runs on. A backend call does not see a
dialog open in the client, such as Settings, and leaves it open. A `side=frontend` call in the default mode
closes it with Cancel, as in a regular IDE. Drive client dialogs with `side=frontend` and `modal=unleashed`.

## Driving UI with the ui-driving recipe

The recipe's UI model and `dispatchEvent` input work on both sides. Run the script on the side that owns
the components: `side=frontend` for client windows and popups, the backend for its dialogs and host
Settings pages. A `steroid_input` click at screen coordinates goes to the window on top at that point, as a
real click does, so the snapshot's coordinates reach a popup over the window you name. A click at
screenshot coordinates stays in the named window.

## The backend's own endpoint

A Remote Development backend has its own MCP endpoint. With no client attached, it is the endpoint to use.
With a client attached, use it only to troubleshoot, when the client cannot reach the backend and every
forwarded tool reports that the backend is not connected. There, `steroid_execute_code` rejects
`side=frontend`, and `steroid_list_windows` and `steroid_take_screenshot` show the backend's own copies of
the frames, not what the user sees.

# See also

- [Find and drive UI controls with XPath](mcp-steroid://ide/ui-driving)
- [Execute code tool description](mcp-steroid://skill/execute-code-tool-description)
- [Managing backends](mcp-steroid://open-project/managing-backends)
