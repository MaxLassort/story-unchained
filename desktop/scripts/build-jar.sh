#!/usr/bin/env bash
# Builds the backend Spring Boot fat jar and stages it for the desktop app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

cd "$ROOT/api"
./gradlew bootJar
mkdir -p "$ROOT/desktop/resources"
cp "$ROOT/api/build/libs/api-0.0.1-SNAPSHOT.jar" "$ROOT/desktop/resources/api.jar"
echo "Backend jar -> desktop/resources/api.jar"
