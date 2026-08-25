#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/workspaces/android-sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_ZIP="/tmp/cmdtools.zip"

echo "[1/5] Creating SDK directory: $ANDROID_SDK_ROOT"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

echo "[2/5] Downloading Android Command Line Tools..."
curl -fsSL "$CMDLINE_TOOLS_URL" -o "$CMDLINE_ZIP"

echo "[3/5] Extracting..."
unzip -q "$CMDLINE_ZIP" -d "$ANDROID_SDK_ROOT/cmdline-tools"
mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"

export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

echo "[4/5] Accepting licenses and installing SDK components..."
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses > /dev/null 2>&1 || true
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "ndk;27.2.12479018" \
  "cmake;3.22.1"

echo "[5/5] Done! SDK installed at: $ANDROID_SDK_ROOT"
echo "NDK at: $ANDROID_SDK_ROOT/ndk/27.2.12479018"
