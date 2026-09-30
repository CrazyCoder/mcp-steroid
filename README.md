# MCP Steroid Plus

A maintained continuation of [MCP Steroid](https://github.com/jonnyzzz/mcp-steroid)
with fixes for current IntelliJ Platform builds. Plugin ID
`io.github.crazycoder.mcp-steroid`. It installs into `plugins/mcp-steroid-plus`
and disables the upstream `com.jonnyzzz.mcp-steroid` plugin when both are
present. Each release on
[GitHub Releases](https://github.com/CrazyCoder/mcp-steroid/releases) carries
the plugin, the `devrig` CLI built from the same commit, and the devrig
installers, so the two always match. [Install](#install) shows how to get both.

## MCP Steroid

<p align="center">
  <img src="website/static/pluginIcon.svg" alt="MCP Steroid Logo" width="120" height="120">
</p>

<p align="center">
  <strong>Connect your AI coding agent to a real JetBrains IDE</strong><br>
  <em>Install <code>devrig</code>, and your agent works through the whole IntelliJ — not just the files</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <a href="https://discord.gg/e9qgQ7NeTC"><img src="https://img.shields.io/badge/Discord-Community-5865F2.svg" alt="Discord"></a>
</p>

<p align="center">
  <a href="https://github.com/CrazyCoder/mcp-steroid/releases">Releases</a> &bull;
  <a href="https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f">Demo Videos</a> &bull;
  <a href="https://jonnyzzz.com/blog/2026/04/07/mcp-steroid-open-source/">Blog Post</a> &bull;
  <a href="https://discord.gg/e9qgQ7NeTC">Discord</a>
</p>

---

## What is devrig?

**[`devrig`](#install)** is the product you install: a small command-line
tool that connects your AI coding agent (Claude Code, Codex, or Gemini) to a real JetBrains IDE.
It brings **its own runtime**, registers itself with your agent, and bridges the agent's calls to
the IDE — no manual MCP wiring.

devrig reaches the IDE through **MCP Steroid**, a JetBrains IDE plugin (this repo) that exposes the
IDE's real semantic actions — typed refactorings, inspections, the debugger, and test runs — over
the open [Model Context Protocol](https://modelcontextprotocol.io/). You install devrig; devrig
uses MCP Steroid.

Unlike file-only assistants, this gives AI agents the same capabilities developers use: semantic
code understanding, advanced refactorings, debugging, test running, visual awareness, and the
entire IntelliJ API surface — all inside the running IDE's JVM.

### One bridge, every IDE

A single `devrig` process connects your AI Agent to **all** the IntelliJ-family IDEs running on
your machine at once — each open on a different project — and can download and start more on demand.

<p align="center">
  <img src="website/static/devrig-bridge.svg" alt="One devrig bridge connects your AI Agent to all running IDEs at once — and can start more" width="720">
</p>

### What your agent gets

- **Visual IDE understanding** — screenshots + OCR + component tree
- **UI automation** — control the IDE like a human developer, from a text snapshot of its windows (`steroid_ui`)
- **Refactorings without a script** — rename, safe delete, move and quick fixes with a dry run first (`steroid_refactor`)
- **Native IntelliJ APIs** — PSI, inspections, refactorings, and more
- **Kotlin scripting** — full platform access at runtime via `steroid_execute_code`
- **Standard MCP protocol** — connects to MCP-compatible AI agents

The upstream project measured IDE-access vs plain-shell agents on real codebases. See its
[experiment findings](https://devrig.dev/docs/experiment-findings/) for the results.

### Explore the CLI

The command tree is discoverable from either direction:

```bash
devrig --help
devrig tools
devrig help execute_code
devrig list_projects --json
devrig open_project --project_path="$PWD" --task_id=demo-open --reason="open current project from CLI" --wait --json
devrig prompt mcp-steroid://prompt/skill --project_name="PROJECT_NAME_FROM_LIST_PROJECTS" --json
```

`list_projects` is canonical (`projects` and `project` are compatibility aliases). Human output is
readable and may use terminal color; commands that advertise `--json` emit one ANSI-free document for
agents and scripts. Incomplete commands print focused help with every missing value. See the
contributor [CLI contract](docs/devrig-cli-contract.md).

---

## Install

### 1. Install devrig — one command

**macOS / Linux**

```bash
curl -fsSL https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/install.sh | sh
```

**Windows**

```powershell
irm https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/install.ps1 | iex
```

The script does exactly two things: it installs the `devrig` CLI of the latest release with its own bundled runtime into `~/.mcp-steroid`, and it registers the stable `devrig` launcher on your `PATH` (if `devrig` is not found afterwards, open a new terminal or add `~/.mcp-steroid/bin` to `PATH`). It never touches your agent configs or your IDEs — it finishes by printing the explicit next-step commands (steps 2 and 3 below). Installation is idempotent; re-run it any time to update.

devrig updates itself: a running devrig checks `version.json` of the latest release and installs a newer release in the background.

**Switching from the upstream devrig.** A devrig installed from `devrig.dev` keeps updating from there, and lacks the tools this repository adds (`steroid_ui`, `steroid_refactor`). Run the command above once: it installs this repository's devrig over it, and from then on devrig updates from this repository's releases. Then restart your agent sessions: a devrig process that is still running keeps its own update check on `devrig.dev`. `devrig --version` then prints `<release>.0-r-<hash>`, such as `0.125.0-r-1a2b3c4` for release 0.125.

### 2. Register your AI agent

```bash
devrig install claude
devrig install codex
devrig install gemini
```

`devrig install <agent>` registers devrig as the `mcp-steroid` MCP server in Claude Code, Codex, or Gemini (one of `claude`, `codex`, `gemini`). The entry lands in the user-scope config, so it is visible from every project. For any other MCP client, `devrig install config` prints the manual `mcp.json` snippet to paste. `devrig --help` lists the full command set.

### 3. Install the MCP Steroid Plus plugin

MCP Steroid Plus is not on JetBrains Marketplace. An IDE installs it from the plugin repository of the latest release:

```
https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/updatePlugins.xml
```

1. In the IDE, open **Settings | Plugins**, click the gear icon, choose **Manage Plugin Repositories…** and add the URL above.
2. Run `devrig install plugin`, or search for **MCP Steroid Plus** in **Settings | Plugins** and install it.

`devrig install plugin` asks every JetBrains IDE running on your machine to install the plugin — each IDE asks for your confirmation with its own native install dialog, so nothing is installed silently. An IDE finds the plugin only after step 1.

Instead of the repository, you can download `mcp-steroid-plus-<version>.zip` from [GitHub Releases](https://github.com/CrazyCoder/mcp-steroid/releases) and choose **Install Plugin from Disk** in the same gear menu.

**Requirements**

- A JetBrains IDE — IntelliJ IDEA, PyCharm, GoLand, WebStorm, Rider, CLion, or Android Studio.
- A standard desktop IDE runs with a real display: the normal GUI on macOS/Windows, or under **Xvfb** (a virtual X display) on Linux/CI. Plain non-backend headless mode is unsupported (best-effort, see [#177](https://github.com/jonnyzzz/mcp-steroid/issues/177)); a frontendless Remote Development backend is supported. Backend product mode, not the presence of a client window or the raw AWT-headless flag alone, determines that distinction — see [Running devrig in CI](https://devrig.dev/docs/running-on-ci/).
- An MCP-compatible AI agent (Claude Code, Codex, or Gemini).

For a clean machine with no IDE running, an agent can discover the download catalog with `devrig backend download --json`, install IDEA Ultimate 2026.2, and call `steroid_open_project`. The managed IU-262 backend starts on demand as a frontendless Remote Development backend with MCP Steroid included; no separate start command or client window is required. Readiness is the project path plus Maven/Gradle import, not a screenshot. A managed backend gets the plugin bundled in devrig, so it needs no plugin repository.

**Plugin updates:** the installed plugin adds the plugin repository above to the IDE's own update check, so the IDE offers each new release like a Marketplace update, even when the repository is not in its list.

### Verify the connection

When the plugin starts, it writes the connection details to `.idea/mcp-steroid.md` in each open project. Ask your agent to list the open projects:

```bash
claude -p "List all open projects using steroid_list_projects"
codex exec "List all open projects using steroid_list_projects"
gemini "List all open projects using steroid_list_projects"
```

If you see your open IntelliJ projects, the connection works. The plugin also serves the raw server URL at `http://127.0.0.1:6315/mcp` (Streamable HTTP transport) for clients that prefer to talk to the IDE directly.

### Local development loop (deploy both halves from a checkout)

Working on MCP Steroid itself? Two Gradle tasks push your checkout into the live environment — no IDE restarts, no reinstalling.

**One-time per IDE**: install the [Plugin Hot Reload](https://github.com/jonnyzzz/intellij-plugin-hot-reload) plugin into every IDE you deploy to — download the ZIP from its Releases page, then `Settings | Plugins | ⚙ | Install Plugin from Disk…`, restart once. It exposes the local hot-reload endpoint (a `~/.<pid>.hot-reload` marker per running IDE) that `deployPlugin` talks to.

```bash
# 1. devrig (the CLI): build, stage under ~/.mcp-steroid/devrig/, and regenerate the
#    ~/.mcp-steroid/bin/devrig launcher via `devrig install devrig`.
./gradlew deployDevrig

# assert: the launcher runs YOUR build — a dev version stamped with your checkout's git hash
~/.mcp-steroid/bin/devrig version
# → <base>.19999-SNAPSHOT-<git hash of your HEAD>

# 2. the IDE plugin: build the plugin ZIP and hot-reload it into every running IDE.
./gradlew :ij-plugin:deployPlugin

# assert: the task output ends with SUCCESS per IDE —
#   Installing and loading plugin: MCP Steroid (<base>.19999-SNAPSHOT-<git hash>)
#   Plugin MCP Steroid reloaded successfully
```

Both tasks fail loudly instead of half-deploying: `deployDevrig` fails when `devrig install devrig` cannot write the launcher (e.g. a `DEVRIG_BIN_NO_AUTO_REGISTER` opt-out), and `deployPlugin` fails with `No running IDEs found` when no IDE with the hot-reload plugin is up, or on anything but `SUCCESS` from an IDE.

---

## Compatible AI Agents

`devrig install` registers MCP Steroid directly with:

- **Claude** (Claude Code)
- **Codex** CLI
- **Gemini** CLI

MCP Steroid speaks the standard Model Context Protocol, so other MCP-capable clients can also connect to the plugin's server directly — see [How it works](https://devrig.dev/docs/how-it-works/) on the upstream site.

---

## Capabilities

### Design philosophy in one breath

The MCP tool surface is **intentionally small** — power lives in
`mcp-steroid://` prompt resources that teach agents to call IntelliJ's
APIs directly inside `steroid_execute_code`. New tools and new
`McpScriptContext` methods are not the lever for "agents deliver more";
better recipes are. The full canonical statement lives in
[`docs/PHILOSOPHY.md`](docs/PHILOSOPHY.md) and is mirrored at runtime
as `mcp-steroid://skill/design-philosophy`.

### MCP Tools

| Tool | Description |
|------|-------------|
| **Execute Code** (`steroid_execute_code`) | Run Kotlin code inside the IDE's JVM with full API access |
| **UI** (`steroid_ui`) | Read the IDE's windows as text with refs, drive dialogs, popups and Settings by what they show, run an IDE action at a code location, and find the class and plugin behind a control. No compile |
| **Refactor** (`steroid_refactor`) | Rename, safe delete, move, quick fix, intention, optimize imports, reformat and usages, dry run first, without a dialog or a script |
| **Execute Feedback** (`steroid_execute_feedback`) | Provide execution ratings back to agents |
| **Fetch Resource** (`steroid_fetch_resource`) | Fetch any `mcp-steroid://` skill guide / recipe by URI |
| **Vision Screenshot** (`steroid_take_screenshot`) | Capture IDE screenshots with component metadata |
| **Vision Input** (`steroid_input`) | Send keyboard/mouse events to the IDE via a sequence-string DSL |
| **List Projects** (`steroid_list_projects`) | Discover all open IntelliJ projects |
| **List Windows** (`steroid_list_windows`) | Enumerate IDE windows and components |
| **Open Project** (`steroid_open_project`) | Open projects programmatically |

### MCP Resources

Guides and runnable examples, fetched with `steroid_fetch_resource`:

- **LSP Operations** (`lsp/`) — Go to definition, find references, hover, completion
- **IDE Power Operations** (`ide/`) — Refactorings, code generation, inspections, UI driving
- **Debugger Integration** (`debugger/`) — Breakpoints, thread control, debugging workflows
- **Test Runner** (`test/`) — Run tests, inspect results, navigate test trees
- **VCS Operations** (`vcs/`) — Git annotations, file history
- **Project Workflows** (`open-project/`) — Open projects with trust levels, manage backends
- **Skill Guides** (`skill/`, `prompt/`) — IntelliJ API, execute-code, debugger, test runner and Split Mode guides

---

## Featured Demo Videos

| Video | Description | Duration |
|-------|-------------|----------|
| [Codex Debugs in IntelliJ IDEA](https://www.youtube.com/watch?v=HtDDNyAoLak) | Full debugging session with Codex | 1:03:24 |
| [CodeDozer Demo 5](https://www.youtube.com/watch?v=6ByedA15n8Q) | Most popular demo | 1:00 |
| [CodeDozer & IntelliJ Debugger](https://www.youtube.com/watch?v=8MjogrpfXLU) | Debugger integration showcase | 8:25 |
| [Now we call tasks in IntelliJ](https://www.youtube.com/watch?v=JGcRk7Y3-Z8) | Task execution demo | 2:21 |
| [Real Work in Monorepo Part 2](https://www.youtube.com/watch?v=ibc0saTT06M) | Deep dive into real workflow | 18:37 |
| [Cursor Talks with IntelliJ](https://www.youtube.com/watch?v=QIl57FrAJtk) | Cursor integration | 0:44 |

Watch all demos: [MCP Steroid Playlist](https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f)

---

## Configuration

MCP Steroid can be configured via IntelliJ's Registry (`Help > Find Action > Registry`) or JVM system properties.

| Registry Key | Default | Description |
|--------------|---------|-------------|
| `mcp.steroid.server.port` | 6315 | MCP server port (0 for auto-assign). A port for this IDE in `~/.mcp-steroid/ports.json` overrides it |
| `mcp.steroid.server.host` | 127.0.0.1 | Bind address (use 0.0.0.0 for Docker) |
| `mcp.steroid.storage.path` | (empty) | Custom storage path (default: `~/.mcp-steroid/runs/`) |

See the full [Configuration Documentation](https://devrig.dev/docs/configuration/) on the upstream site.

---

## Architecture

- **Technology:** Kotlin on the JVM, running inside the IDE process
- **HTTP Server:** Ktor 3.3.2 (Streamable HTTP + SSE)
- **Protocol:** Model Context Protocol (MCP)
- **Default Port:** 6315
- **OCR:** Tesseract 5.5.1
- **Platform:** IntelliJ Platform Plugin SDK

The server runs **inside the IDE's JVM process** — no inter-process communication. Direct access to the project model, semantic index, PSI tree, test runner, debugger, and VCS layer.

---

## About the Project

**MCP Steroid** is an open-source project by Eugene Petrenko ([@jonnyzzz](https://linkedin.com/in/jonnyzzz)), licensed under [Apache 2.0](LICENSE).

Read more:
- [MCP Steroid Is Now Open Source](https://jonnyzzz.com/blog/2026/04/07/mcp-steroid-open-source/) — announcement and project history
- [IntelliJ as a Skill Factory](https://jonnyzzz.com/blog/2026/04/08/mcp-steroid-skill-factory/) — build custom agent skills without plugin development
- [Project Assessment: 75 Days, 1300+ Commits](https://jonnyzzz.com/blog/2026/02/23/mcp-steroid-project-assessment/) — architecture and quality deep dive

*IntelliJ IDEA, IntelliJ Platform, PyCharm, WebStorm, and JetBrains are trademarks of JetBrains s.r.o.*

---

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on
how to get started, and [CONTRIBUTORS.md](CONTRIBUTORS.md) for the list of people
who have helped make MCP Steroid better.

---

## License

MCP Steroid is open-source software licensed under the [Apache License 2.0](LICENSE).

---

## Links

- **Releases:** [github.com/CrazyCoder/mcp-steroid/releases](https://github.com/CrazyCoder/mcp-steroid/releases)
- **GitHub Issues:** [github.com/CrazyCoder/mcp-steroid/issues](https://github.com/CrazyCoder/mcp-steroid/issues)
- **Upstream website:** [devrig.dev](https://devrig.dev)
- **Discord:** [discord.gg/e9qgQ7NeTC](https://discord.gg/e9qgQ7NeTC)
- **GitHub Sponsors:** [github.com/sponsors/jonnyzzz](https://github.com/sponsors/jonnyzzz)
- **Blog:** [jonnyzzz.com](https://jonnyzzz.com)
- **YouTube:** [@jonnyzzz](https://youtube.com/@jonnyzzz)
- **LinkedIn:** [jonnyzzz](https://linkedin.com/in/jonnyzzz)
- **X/Twitter:** [@jonnyzzz](https://x.com/jonnyzzz)

---

<p align="center">
  <sub>Built with care for the AI agent developer community</sub>
</p>
