#!/usr/bin/env bash
# Sets VERSION, commits, and tags v<version>: tag-release.sh <version>
# Needs release/notes/<version>.md, which the release build embeds as change notes.
set -euo pipefail
version="${1:-}"
[[ "$version" =~ ^[0-9]+(\.[0-9]+)+$ ]] || { echo "usage: tag-release.sh <version like 0.103>" >&2; exit 2; }
cd "$(git rev-parse --show-toplevel)"
[ -z "$(git status --porcelain)" ] || { echo "working tree is not clean" >&2; exit 3; }
[ "$(git branch --show-current)" = "main" ] || { echo "not on main" >&2; exit 3; }
[ -f "release/notes/$version.md" ] || { echo "missing release/notes/$version.md" >&2; exit 4; }
git rev-parse -q --verify "refs/tags/v$version" >/dev/null && { echo "tag v$version exists" >&2; exit 5; }
printf '%s' "$version" > VERSION
git add VERSION
git commit -m "release: $version"
git tag -a "v$version" -m "MCP Steroid Plus $version"
echo "Tagged v$version. Push with: git push fork main v$version"
