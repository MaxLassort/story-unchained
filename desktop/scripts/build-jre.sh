#!/usr/bin/env bash
# Builds a minimal arm64 JRE with jlink from an arm64 JDK 21 (Apple Silicon).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ARM64_JDK="${ARM64_JDK:-$(/usr/libexec/java_home -v 21 -a arm64 2>/dev/null || true)}"
if [[ -z "$ARM64_JDK" ]]; then
  echo "ERROR: no arm64 JDK 21 found. Set ARM64_JDK." >&2
  exit 1
fi
[[ -x "$ARM64_JDK/bin/jlink" ]] || { echo "ERROR: $ARM64_JDK has no bin/jlink" >&2; exit 1; }

DEST="$ROOT/desktop/resources/jre"
# Modules required by Spring Boot + H2 + AWT/ImageIO/Sound (java.desktop).
MODULES="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.unsupported,jdk.crypto.ec,jdk.localedata"

rm -rf "$DEST"
"$ARM64_JDK/bin/jlink" \
  --add-modules "$MODULES" \
  --output "$DEST" \
  --strip-debug --no-man-pages --no-header-files --compress=2

echo "JRE -> desktop/resources/jre"
"$DEST/bin/java" -version
