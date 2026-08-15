#!/usr/bin/env bash
# Builds the backend Spring Boot fat jar and stages it for the desktop app.
# Set APP_VERSION to override the jar version (default: 0.0.1-SNAPSHOT).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APP_VERSION="${APP_VERSION:-0.0.1-SNAPSHOT}"

cd "$ROOT/api"
./gradlew bootJar -PappVersion="$APP_VERSION"
mkdir -p "$ROOT/desktop/resources"
cp "$ROOT/api/build/libs/api-${APP_VERSION}.jar" "$ROOT/desktop/resources/api.jar"
echo "Backend jar -> desktop/resources/api.jar"
