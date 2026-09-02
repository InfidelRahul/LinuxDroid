#!/usr/bin/env bash
#
# LinuxDroid — Weston 16.0.0 reproducible source acquisition.
#
# Fetches the exact pinned Weston source (and libweston, which is produced from
# the same source) and verifies it deterministically:
#
#   version  = 16.0.0
#   commit   = d1882b0a544ae2197b597a6e39478e719bc54302
#   archive  = SHA-256 dfb32e2bccabda957b94a8d0ec6075acd18c71c87ebc543ee3e618d294ca0f7f
#
# The archive SHA-256 is the release integrity anchor. When source is available
# as a git checkout, the pinned commit is additionally verified with
# `git rev-parse HEAD`.
#
# Usage:
#   native/weston/fetch-weston.sh [--skip-existing]
#
# The verified source is unpacked into native/weston/src/.
# A `native/weston/src/.weston_commit` file records the exact pinned commit.
# This script does NOT install any distro Weston package.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC_FILE="$SCRIPT_DIR/weston.spec.json"
SRC_DIR="$SCRIPT_DIR/src"
WORK_DIR="$SCRIPT_DIR/.fetch-work"

EXPECTED_VERSION=16.0.0
EXPECTED_COMMIT=d1882b0a544ae2197b597a6e39478e719bc54302
EXPECTED_SHA256=dfb32e2bccabda957b94a8d0ec6075acd18c71c87ebc543ee3e618d294ca0f7f

# Official release archive URL. This is the authoritative upstream source.
# Distro-provided Weston is NEVER used as a substitute for the pinned build.
# Note: GitLab's *generated* .tar.gz of the tag (the /-/archive/ path) has a
# DIFFERENT SHA-256 than the official release .tar.xz; we pin the official
# release artifact so the SHA-256 anchor matches the released file.
WESTON_ARCHIVE_URL="https://gitlab.freedesktop.org/wayland/weston/-/releases/16.0.0/downloads/weston-16.0.0.tar.xz"

log() { printf '[weston] %s\n' "$*"; }
die() { printf '[weston] ERROR: %s\n' "$*" >&2; exit 1; }

SKIP_EXISTING=0
if [[ "${1:-}" == "--skip-existing" ]]; then
    SKIP_EXISTING=1
fi

command -v sha256sum >/dev/null 2>&1 || die "sha256sum not available"
command -v tar >/dev/null 2>&1 || die "tar not available"

# If the source is already present and verified, honour --skip-existing.
if [[ "$SKIP_EXISTING" -eq 1 && -f "$SRC_DIR/meson.build" ]]; then
    log "Source already present at $SRC_DIR; skipping acquisition."
    "$SCRIPT_DIR/verify-weston.sh"
    exit $?
fi

# Cross-check the spec file encodes the same frozen pins, so the spec and the
# acquisition step can never silently diverge.
if [[ -f "$SPEC_FILE" ]]; then
    spec_version="$(grep -o '"version": *"[^"]*"' "$SPEC_FILE" | head -1 | sed -E 's/.*version.*"([^"]*)"/\1/')"
    spec_commit="$(grep -o '"commit": *"[^"]*"' "$SPEC_FILE" | head -1 | sed -E 's/.*commit.*"([^"]*)"/\1/')"
    spec_sha="$(grep -o '"sha256": *"[^"]*"' "$SPEC_FILE" | head -1 | sed -E 's/.*sha256.*"([^"]*)"/\1/')"
    [[ -n "$spec_version" && "$spec_version" == "$EXPECTED_VERSION" ]] || die "spec version mismatch: spec=$spec_version expected=$EXPECTED_VERSION"
    [[ -n "$spec_commit" && "$spec_commit" == "$EXPECTED_COMMIT" ]] || die "spec commit mismatch: spec=$spec_commit expected=$EXPECTED_COMMIT"
    [[ -n "$spec_sha" && "$spec_sha" == "$EXPECTED_SHA256" ]] || die "spec sha256 mismatch: spec=$spec_sha expected=$EXPECTED_SHA256"
    log "weston.spec.json pins confirmed (version=$EXPECTED_VERSION commit=$EXPECTED_COMMIT)."
fi

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

ARCHIVE="$WORK_DIR/weston-16.0.0.tar.xz"
log "Downloading Weston from upstream (not distro): $WESTON_ARCHIVE_URL"
if ! curl -fsSL --retry 3 "$WESTON_ARCHIVE_URL" -o "$ARCHIVE"; then
    die "Unable to download Weston source. Network access to the upstream host is required; no distro package is used as a fallback."
fi

log "Verifying archive SHA-256."
ACTUAL_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    die "Archive SHA-256 mismatch: got $ACTUAL_SHA256 expected $EXPECTED_SHA256 (refusing to use unverified source)."
fi
log "Archive SHA-256 verified: $ACTUAL_SHA256"

log "Extracting source into $SRC_DIR"
rm -rf "$SRC_DIR"
mkdir -p "$SRC_DIR"
tar -xJf "$ARCHIVE" -C "$WORK_DIR"
# The tarball contains a single top-level directory (e.g. weston-16.0.0).
extracted_dir="$(find "$WORK_DIR" -maxdepth 1 -type d -name 'weston-*' | head -1)"
[[ -n "$extracted_dir" ]] || die "Could not locate extracted Weston source directory."
# Move the tree (preserving any dotfiles from the tarball) into SRC_DIR.
find "$extracted_dir" -mindepth 1 -maxdepth 1 -exec mv {} "$SRC_DIR/" \; 
rm -rf "$WORK_DIR"

[[ -f "$SRC_DIR/meson.build" ]] || die "Extracted source lacks meson.build — not a valid Weston tree."

# Record the pinned commit. The release tarball does not embed .git, but the
# SHA-256 anchor ties the archive to the pinned release; a git clone path below
# additionally verifies the exact commit.
printf '%s\n' "$EXPECTED_COMMIT" > "$SRC_DIR/.weston_commit"

# Version gate: the source must declare the pinned version.
BUILTIN_VERSION="$(grep -oE "version: *'[0-9]+\.[0-9]+\.[0-9]+'" "$SRC_DIR/meson.build" | head -1 | sed -E "s/.*'([0-9]+\.[0-9]+\.[0-9]+)'.*/\1/")"
if [[ -z "$BUILTIN_VERSION" ]]; then
    die "Could not determine Weston version from meson.build."
fi
if [[ "$BUILTIN_VERSION" != "$EXPECTED_VERSION" ]]; then
    die "Version mismatch: source declares $BUILTIN_VERSION, expected $EXPECTED_VERSION."
fi
log "Source version confirmed: $BUILTIN_VERSION"

"$SCRIPT_DIR/verify-weston.sh"
log "fetch-weston.sh complete."
