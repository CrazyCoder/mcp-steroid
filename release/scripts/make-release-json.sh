#!/usr/bin/env bash
# Prints release.json for a built plugin zip: make-release-json.sh <zip> <version>
set -euo pipefail
zip="$1"
version="$2"
[ -f "$zip" ] || { echo "no such zip: $zip" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
jar="$(unzip -Z1 "$zip" | grep -E '^mcp-steroid-plus/lib/ij-plugin-[^/]*\.jar$' | head -1)"
[ -n "$jar" ] || { echo "$zip has no mcp-steroid-plus/lib/ij-plugin-*.jar" >&2; exit 3; }
unzip -p "$zip" "$jar" > "$work/ij.jar"
xml="$(unzip -p "$work/ij.jar" META-INF/plugin.xml)"

plugin_id="$(printf '%s' "$xml" | sed -n 's#.*<id>\([^<]*\)</id>.*#\1#p' | head -1)"
since="$(printf '%s' "$xml" | sed -n 's#.*since-build="\([^"]*\)".*#\1#p' | head -1)"
until_build="$(printf '%s' "$xml" | sed -n 's#.*until-build="\([^"]*\)".*#\1#p' | head -1)"
sha="$(sha256sum "$zip" | cut -d' ' -f1)"
size="$(wc -c < "$zip" | tr -d ' ')"

[ "$plugin_id" = "io.github.crazycoder.mcp-steroid" ] || { echo "unexpected plugin id '$plugin_id'" >&2; exit 4; }
[ -n "$since" ] || { echo "no since-build in plugin.xml" >&2; exit 4; }

jq -n --arg version "$version" --arg pluginId "$plugin_id" --arg since "$since" \
  --arg until "$until_build" --arg sha "$sha" --argjson size "$size" \
  '{version:$version, pluginId:$pluginId, sinceBuild:$since,
    untilBuild:(if $until == "" then null else $until end),
    sha256:$sha, size:$size, zip:"mcp-steroid-plugin.zip"}'
