#!/usr/bin/env bash
# Builds the Angular frontend with a relative base href (file:// loading in Electron)
# and stages it for the desktop app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT/library-web"

[[ -d node_modules ]] || npm install
npx ng build --base-href ./

rm -rf "$ROOT/desktop/dist"
mkdir -p "$ROOT/desktop/dist"
cp -R "$ROOT/library-web/dist/library-web/browser" "$ROOT/desktop/dist/browser"
echo "Frontend build -> desktop/dist/browser"
