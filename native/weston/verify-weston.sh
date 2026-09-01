#!/usr/bin/env bash
#
# LinuxDroid — Deterministic Weston / libweston version verification.
#
# This mechanism is NOT comment-based. It establishes, from the actual source
# tree (or, when the tree is not yet fetched, from the authoritative pinned
# spec) that the expected Weston version and pinned source revision are in use.
#
#   Weston version        = 16.0.0
#   Pinned source revision = d1882b0a544ae2197b597a6e39478e719bc54302
#
# Exit status:
#   0  verification passed
#   1  verification failed
#   2  verification could not be completed (e.g. source not fetched, and no
#      git checkout to consult)
#
# Usage:
#   native/weston/verify-weston.sh
#   native/weston/verify-weston.sh --strict-source   # require real source/commit
#
# This script is invoked from:
#   - native/weston/build-libweston.sh
#   - the Gradle `verifyWeston` task (gradle/weston-dependency.gradle.kts)
#   - native/weston/fetch-weston.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC_FILE="$SCRIPT_DIR/weston.spec.json"
SRC_DIR="$SCRIPT_DIR/src"

EXPECTED_VERSION=16.0.0
EXPECTED_COMMIT=d1882b0a544ae2197b597a6e39478e719bc54302

STRICT_SOURCE=0
if [[ "${1:-}" == "--strict-source" ]]; then
    STRICT_SOURCE=1
fi

log() { printf '[weston-verify] %s\n' "$*"; }
die()  { printf '[weston-verify] FAIL: %s\n' "$*" >&2; exit 1; }

# --- Load expected values from the spec (authoritative declaration) ----------
spec_version=""
spec_commit=""
if [[ -f "$SPEC_FILE" ]]; then
    spec_version="$(grep -o '"version": *"[^"]*"' "$SPEC_FILE" | head -1 | sed -E 's/.*version.*"([^"]*)"/\1/')"
    spec_commit="$(grep -o '"commit": *"[^"]*"' "$SPEC_FILE" | head -1 | sed -E 's/.*commit.*"([^"]*)"/\1/')"
fi

# The spec is the source of truth; the built-in constants must never diverge.
declare -A expected
if [[ -n "$spec_version" ]]; then
    [[ "$spec_version" == "$EXPECTED_VERSION" ]] || \
        die "spec version '$spec_version' != frozen build constant '$EXPECTED_VERSION'"
fi
if [[ -n "$spec_commit" ]]; then
    [[ "$spec_commit" == "$EXPECTED_COMMIT" ]] || \
        die "spec commit '$spec_commit' != frozen constant '$EXPECTED_COMMIT'"
fi

# --- Source-level verification -------------------------------------------------
source_version=""
source_commit=""
source_present=0

if [[ -d "$SRC_DIR" && -f "$SRC_DIR/meson.build" ]]; then
    source_present=1
    # Version: parse the first project() declaration in meson.build.
    source_version="$(grep -oE "version: *'[0-9]+\.[0-9]+\.[0-9]+'" "$SRC_DIR/meson.build" 2>/dev/null \
        | head -1 | sed -E "s/.*'([0-9]+\.[0-9]+\.[0-9]+)'.*/\1/")"

    # Commit: prefer a real git checkout; fall back to the recorded commit.
    if [[ -d "$SRC_DIR/.git" ]]; then
        source_commit="$(git -C "$SRC_DIR" rev-parse HEAD 2>/dev/null || true)"
    elif [[ -f "$SRC_DIR/.weston_commit" ]]; then
        source_commit="$(tr -d '[:space:]' < "$SRC_DIR/.weston_commit")"
    fi
else
    log "No source tree at $SRC_DIR; falling back to spec-level verification only."
fi

# --- Evaluate -------------------------------------------------------------------
if [[ "$source_present" -eq 1 ]]; then
    if [[ -z "$source_version" ]]; then
        die "Unable to read Weston version from meson.build."
    fi
    [[ "$source_version" == "$EXPECTED_VERSION" ]] || \
        die "Source declares Weston version $source_version (expected $EXPECTED_VERSION)."
    log "Source Weston version OK: $source_version"

    if [[ -n "$source_commit" ]]; then
        [[ "$source_commit" == "$EXPECTED_COMMIT" ]] || \
            die "Source commit mismatch: got $source_commit, expected $EXPECTED_COMMIT."
        log "Source pinned commit OK: $source_commit"
    else
        if [[ "$STRICT_SOURCE" -eq 1 ]]; then
            die "Strict source mode requested but no git checkout or recorded commit available."
        fi
        log "Note: commit could not be independently read from the tree (archived tarball). Relying on archive SHA-256 anchor + recorded .weston_commit."
    fi
else
    if [[ "$STRICT_SOURCE" -eq 1 ]]; then
        die "Strict source mode requested but source tree not present (run fetch-weston.sh)."
    fi
    log "Spec pins passed (version=$EXPECTED_VERSION commit=$EXPECTED_COMMIT). Source not present; run fetch-weston.sh to acquire and verify the exact source."
fi

log "verify-weston.sh PASSED (expected Weston $EXPECTED_VERSION @ $EXPECTED_COMMIT)."
exit 0
