#!/usr/bin/env bash
#
# Builds the usb4java native library (libusb4java.dylib) for darwin-aarch64 (Apple Silicon)
# and installs it where the backend can load it natively:
#   api/src/main/resources/org/usb4java/darwin-aarch64/libusb4java.dylib
#
# Prerequisites (arm64): a JDK 21+, libusb and cmake. On Apple Silicon use the arm64 Homebrew
# (/opt/homebrew); override with ARM64_JDK / LIBUSB_PREFIX / CMAKE_BIN if needed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/api/src/main/resources/org/usb4java/darwin-aarch64/libusb4java.dylib"

# 1. Arm64 JDK (headers for JNI)
ARM64_JDK="${ARM64_JDK:-$(/usr/libexec/java_home -v 21 -a arm64 2>/dev/null || true)}"
if [[ -z "$ARM64_JDK" ]]; then
  echo "ERROR: no arm64 JDK 21 found. Set ARM64_JDK (e.g. a Temurin/Microsoft arm64 install)." >&2
  exit 1
fi
[[ -f "$ARM64_JDK/include/jni.h" ]] || { echo "ERROR: $ARM64_JDK has no include/jni.h" >&2; exit 1; }

# 2. libusb (static) + cmake
LIBUSB_PREFIX="${LIBUSB_PREFIX:-/opt/homebrew/opt/libusb}"
CMAKE_BIN="${CMAKE_BIN:-$(command -v /opt/homebrew/bin/cmake || command -v cmake)}"
[[ -f "$LIBUSB_PREFIX/lib/libusb-1.0.a" ]] || { echo "ERROR: libusb static lib not found at $LIBUSB_PREFIX/lib/libusb-1.0.a" >&2; exit 1; }
[[ -x "$CMAKE_BIN" ]] || { echo "ERROR: cmake not found (brew install cmake)" >&2; exit 1; }

# 3. Build usb4java native into a temp dir
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 https://github.com/usb4java/libusb4java.git "$WORK/libusb4java"

export JAVA_HOME="$ARM64_JDK"
"$CMAKE_BIN" -S "$WORK/libusb4java" -B "$WORK/build" \
  -DLibUsb_USE_STATIC_LIBS=TRUE \
  -DLibUsb_INCLUDE_HINTS="$LIBUSB_PREFIX/include/libusb-1.0" \
  -DLibUsb_LIBRARY_HINTS="$LIBUSB_PREFIX/lib" \
  -DCMAKE_SHARED_LINKER_FLAGS="-framework IOKit -framework CoreFoundation -framework Security" >/dev/null
"$CMAKE_BIN" --build "$WORK/build" -j >/dev/null

DYLIB="$WORK/build/src/libusb4java.dylib"
[[ -f "$DYLIB" ]] || { echo "ERROR: build did not produce $DYLIB" >&2; exit 1; }
file "$DYLIB" | grep -q "arm64" || { echo "ERROR: built library is not arm64: $(file "$DYLIB")" >&2; exit 1; }

mkdir -p "$(dirname "$DEST")"
cp "$DYLIB" "$DEST"
echo "Installed: $DEST"
echo "Rebuild the backend jar so it is bundled: cd api && ./gradlew bootJar"
