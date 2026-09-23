#!/usr/bin/env bash
# Prints updatePlugins.xml, the custom plugin repository the IDE checks for
# plugin updates: make-update-plugins-xml.sh <zip> <download-url>
# The version is the plugin's own, read from the zip, so the IDE offers the
# release exactly when it is newer than the installed build.
set -euo pipefail
zip="$1"
url="$2"
[ -f "$zip" ] || { echo "no such zip: $zip" >&2; exit 2; }
[[ "$url" =~ ^https:// ]] || { echo "download url must be https: $url" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
jar="$(unzip -Z1 "$zip" | grep -E '^mcp-steroid-plus/lib/ij-plugin-[^/]*\.jar$' | head -1 || true)"
[ -n "$jar" ] || { echo "$zip has no mcp-steroid-plus/lib/ij-plugin-*.jar" >&2; exit 3; }
unzip -p "$zip" "$jar" > "$work/ij.jar"
xml="$(unzip -p "$work/ij.jar" META-INF/plugin.xml)"

plugin_id="$(printf '%s' "$xml" | sed -n 's#.*<id>\([^<]*\)</id>.*#\1#p' | head -1)"
name="$(printf '%s' "$xml" | sed -n 's#.*<name>\([^<]*\)</name>.*#\1#p' | head -1)"
version="$(printf '%s' "$xml" | sed -n 's#.*<version>\([^<]*\)</version>.*#\1#p' | head -1)"
since="$(printf '%s' "$xml" | sed -n 's#.*since-build="\([^"]*\)".*#\1#p' | head -1)"
until_build="$(printf '%s' "$xml" | sed -n 's#.*until-build="\([^"]*\)".*#\1#p' | head -1)"

[ "$plugin_id" = "io.github.crazycoder.mcp-steroid" ] || { echo "unexpected plugin id '$plugin_id'" >&2; exit 4; }
[ -n "$version" ] || { echo "no version in plugin.xml" >&2; exit 4; }
[ -n "$since" ] || { echo "no since-build in plugin.xml" >&2; exit 4; }

until_attr=""
[ -n "$until_build" ] && until_attr=" until-build=\"$until_build\""
cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="$plugin_id" url="$url" version="$version">
    <idea-version since-build="$since"$until_attr/>
    <name>$name</name>
  </plugin>
</plugins>
EOF
