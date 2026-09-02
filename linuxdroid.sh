#!/usr/bin/env bash
set -euo pipefail

# Build/sync the repaired LinuxDroid_proot runtime into LinuxDroid's
# source assets. This does not modify Android application semantics.
#
# Usage:
#   ./linuxdroid.sh [LinuxDroid-dir] [LinuxDroid_proot-dir]
#
# Environment:
#   ANDROID_API=36
#   NDK_ROOT=/path/to/ndk
#   SKIP_PROOT_PATCH=1   # use already-patched PRoot checkout

SCRIPT_NAME="$(basename "$0")"
LINUXDROID_DIR="${1:-${LINUXDROID_DIR:-$(pwd)}}"
PROOT_DIR="${2:-${LINUXDROID_PROOT_DIR:-$(cd "$LINUXDROID_DIR/../LinuxDroid_proot" 2>/dev/null && pwd || true)}}"
ANDROID_API="${ANDROID_API:-36}"

die() { echo "[$SCRIPT_NAME] ERROR: $*" >&2; exit 1; }
info() { echo "[$SCRIPT_NAME] $*"; }

[[ -d "$LINUXDROID_DIR/.git" ]] || die "LinuxDroid checkout not found: $LINUXDROID_DIR"
[[ -d "$PROOT_DIR/.git" ]] || die "LinuxDroid_proot checkout not found: $PROOT_DIR"
[[ -f "$PROOT_DIR/src/arch.h" ]] || die "Invalid PRoot checkout: $PROOT_DIR"

cd "$LINUXDROID_DIR"

if [[ -n "$(git status --porcelain)" ]]; then
    die "LinuxDroid working tree is dirty. Commit/stash changes before syncing runtime assets."
fi

if [[ "${SKIP_PROOT_PATCH:-0}" != "1" ]]; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    PATCHER="$SCRIPT_DIR/linuxdroid_proot.sh"
    [[ -x "$PATCHER" ]] || die "linuxdroid_proot.sh must be beside this script and executable"
    "$PATCHER" "$PROOT_DIR"
fi

cd "$PROOT_DIR"

command -v make >/dev/null 2>&1 || die "make is required"
[[ -n "${NDK_ROOT:-}" || -n "${ANDROID_NDK_ROOT:-}" ]] || die "Set NDK_ROOT or ANDROID_NDK_ROOT"

info "Building repaired PRoot for arm64-v8a with Android API $ANDROID_API..."
make android-arm64 ANDROID_API="$ANDROID_API"

PROOT_BIN="$PROOT_DIR/build/android/arm64-v8a/proot"
LOADER_BIN="$PROOT_DIR/build/android/arm64-v8a/loader"

[[ -f "$PROOT_BIN" ]] || die "Android PRoot artifact not produced: $PROOT_BIN"
[[ -f "$LOADER_BIN" ]] || die "Android loader artifact not produced: $LOADER_BIN"

cd "$LINUXDROID_DIR"

ASSET_DIR="app/src/main/assets/proot/arm64-v8a"
mkdir -p "$ASSET_DIR"

cp "$PROOT_BIN" "$ASSET_DIR/proot"
cp "$LOADER_BIN" "$ASSET_DIR/loader"
chmod 700 "$ASSET_DIR/proot" "$ASSET_DIR/loader"

PROOT_COMMIT="$(git -C "$PROOT_DIR" rev-parse HEAD)"
PROOT_SHORT="$(git -C "$PROOT_DIR" rev-parse --short HEAD)"
PROOT_SHA="$(sha256sum "$ASSET_DIR/proot" | awk '{print $1}')"
LOADER_SHA="$(sha256sum "$ASSET_DIR/loader" | awk '{print $1}')"

cat > "$ASSET_DIR/MANIFEST.txt" <<EOF
LinuxDroid-PRoot dev
commit: $PROOT_COMMIT
ABI: arm64-v8a
arch: aarch64 (ARM64)
android: $ANDROID_API+
sha256:
  proot:  $PROOT_SHA
  loader: $LOADER_SHA
EOF

info "Runtime assets synchronized:"
info "  $ASSET_DIR/proot"
info "  $ASSET_DIR/loader"
info "  $ASSET_DIR/MANIFEST.txt"
info "  PRoot commit: $PROOT_SHORT"

# Verify the Android-side Gradle currency task sees the same source checkout.
if [[ -x ./gradlew ]]; then
    info "Running PRoot currency verification..."
    ./gradlew verifyProotCurrency
fi

info "Running LinuxDroid unit tests..."
./gradlew testDebugUnitTest

info "LinuxDroid PRoot integration update complete."
info "Build the APK normally after reviewing git diff."
git status --short
git diff --stat
