# Plugin Updates

MCP Steroid Plus updates through the IDE's own plugin update check.

> **devrig-side auto-update** — the devrig CLI downloads and runs the official install scripts
> itself, coordinated across concurrent devrig processes through `~/.mcp-steroid/update/` marker
> files. That flow is specified in [devrig-auto-update.md](devrig-auto-update.md). This README
> covers the IDE plugin only.

## How it works

- Each GitHub release carries `updatePlugins.xml`, a custom plugin repository with one `<plugin>`
  entry: the plugin ID, the plugin's own version (for example `0.104.0-r-<hash>`), the
  `since-build` and `until-build` of that build, and the URL of the release's versioned zip.
  `release/scripts/make-update-plugins-xml.sh` writes it from the built zip, in the release
  workflow.
- The plugin registers `PlusUpdateSettingsProvider` on the `com.intellij.updateSettingsProvider`
  extension point. It returns
  `https://github.com/CrazyCoder/mcp-steroid/releases/latest/download/updatePlugins.xml`, so the IDE
  reads the latest release's file whenever it checks plugin updates. The URL is not stored in the
  IDE settings and goes away when the plugin is removed.
- The IDE compares the version in the file with the installed build, shows its standard plugin
  update notification, and installs the zip on restart. Automatic plugin updates apply when the user
  turned them on.

## Testing

`PlusUpdateSettingsProviderTest` checks that the IDE's custom repository list includes the release
feed. To check a built zip, run:

```bash
release/scripts/make-update-plugins-xml.sh <zip> https://example.invalid/plugin.zip
```
