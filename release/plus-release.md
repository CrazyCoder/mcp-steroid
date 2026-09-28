# Releasing MCP Steroid Plus

MCP Steroid Plus releases from `CrazyCoder/mcp-steroid` by a tag. Pushing a tag `v<version>` runs
`.github/workflows/release.yml`, which builds the plugin in Docker and creates the GitHub release with four
assets: `mcp-steroid-plus-<version>.zip`, its `.sha256`, `release.json` and `updatePlugins.xml`. The rest of
this folder describes the upstream `jonnyzzz/mcp-steroid` process, which does not apply here.

## Steps

1. Write `release/notes/<version>.md`: a `## <version>` heading, a summary paragraph, then sections. The
   workflow uses the file as the release body, read from the tagged commit.
2. Set `VERSION` to the version, two components such as `0.119`: `printf '0.119' > VERSION`.
3. Run `./gradlew :ij-plugin:verifyPlugin`. It checks every supported IDE build and takes a few minutes. Every
   line must say `Compatible`.
4. Commit `VERSION` and the notes, and any pictures, as `release: <version>`.
5. Push `main`.
6. Tag and push the tag:

   ```
   git tag -a v0.119 -m "Release 0.119"
   git push origin v0.119
   ```

7. Watch the workflow, then check the release:

   ```
   gh run list --repo CrazyCoder/mcp-steroid --workflow release.yml --limit 1
   gh run watch <run id> --repo CrazyCoder/mcp-steroid --exit-status
   gh release view v0.119 --repo CrazyCoder/mcp-steroid --json url,assets --jq '.url, (.assets[] | .name)'
   ```

## Gotchas

- **The tag must equal `v` + `VERSION`.** The workflow's first step fails on a mismatch, and on a notes file
  that is missing from the tagged commit the release step fails after the build.
- **Make the tag annotated.** `git tag v0.119` alone stops with "no tag message" in this setup; pass `-a` and
  `-m`, as the earlier release tags are.
- **Pass `--repo CrazyCoder/mcp-steroid` to every `gh` command.** Without it `gh` resolves the upstream
  repository, `jonnyzzz/mcp-steroid`, and reports that `release.yml` does not exist.
- **The tag push is a push of its own.** A gate that confirms one push at a time needs a second confirmation
  for the tag after `main`.
- **Pictures in the notes need an absolute URL.** A relative path does not render in a release body. Commit the
  picture under `release/notes/img/` and link its raw URL on the tag:
  `https://raw.githubusercontent.com/CrazyCoder/mcp-steroid/v<version>/release/notes/img/<file>`. The URL
  answers only after the tag is pushed; check that it returns 200 then.
- **Do not plan on editing a published release.** A fix ships as the next version, with its own notes.
- **Verify the build you release, not a sandbox snapshot.** A local snapshot zip is named after `HEAD` but built
  from the working tree, so it can hold changes that are not committed.
