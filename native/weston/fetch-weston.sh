#!/usr/bin/env bash
#
# LinuxDroid — Weston source acquisition (tracks the mirror's `main` branch).
#
# Acquires the Weston/libweston source by cloning the `main` branch of the
# LinuxDroid Weston development mirror — `https://github.com/InfidelRahul/weston`.
# Because this repository lives under the InfidelRahul/ GitHub org, it tracks
# `main` (NOT an upstream stable release). The exact commit resolved for THIS
# build is recorded and verified (no fixed SHA is pinned):
#
#   source type  = git
#   repository   = https://github.com/InfidelRahul/weston
#   branch       = main
#   resolved HEAD = recorded to native/weston/src/.weston_commit after cloning
#
# The resolved commit is the reproducibility anchor for a given build. Because
# `main` moves, a different build may resolve to a different commit; that is
# intended and captured by the per-build recording.
#
# Usage:
#   native/weston/fetch-weston.sh [--skip-existing]
#
# The verified source is checked out into native/weston/src/.
# A `native/weston/src/.weston_commit` file records the exact resolved commit.
# This script does NOT install any distro Weston package.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC_FILE="$SCRIPT_DIR/weston.spec.json"
SRC_DIR="$SCRIPT_DIR/src"
WORK_DIR="$SCRIPT_DIR/.fetch-work"

# Source of truth: the LinuxDroid Weston development mirror, `main` branch.
WESTON_REPO="https://github.com/InfidelRahul/weston"
WESTON_BRANCH="main"

log() { printf '[weston] %s\n' "$*"; }
die() { printf '[weston] ERROR: %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || die "git not available"

# Cross-check the manifest declares the same source (mirror repo + main branch).
# The resolved commit is intentionally NOT in the manifest — it is recorded
# per-build in src/.weston_commit and verified by verify-weston.sh.
if [[ -f "$SPEC_FILE" ]]; then
    # Parse the "weston" dependency block specifically (the spec now lists many
    # dependencies, so we must not match the first repository/track blindly).
    spec_block="$(awk '/"weston"[[:space:]]*:/{f=1} f{print} f && /^[[:space:]]*}[[:space:]]*,?[[:space:]]*$/ && NR>1 && !/libweston/{exit}' "$SPEC_FILE")"
    spec_repo="$(printf '%s\n' "$spec_block" | grep -o '"source": *"[^"]*"' | head -1 | sed -E 's/.*"source": *"([^"]*)"/\1/')"
    spec_track="$(printf '%s\n' "$spec_block" | grep -o '"track": *"[^"]*"' | head -1 | sed -E 's/.*"track": *"([^"]*)"/\1/')"
    [[ "$spec_repo" == "$WESTON_REPO" ]] || die "manifest repository mismatch: manifest=$spec_repo expected=$WESTON_REPO"
    [[ "$spec_track" == "main" ]] || die "manifest track mismatch: manifest=$spec_track expected=main"
    log "weston.spec.json source config confirmed (repository=$WESTON_REPO branch=$WESTON_BRANCH)."
fi

SKIP_EXISTING=0
if [[ "${1:-}" == "--skip-existing" ]]; then
    SKIP_EXISTING=1
fi

if [[ "$SKIP_EXISTING" -eq 1 && -d "$SRC_DIR/.git" && -f "$SRC_DIR/meson.build" ]]; then
    log "Source already present at $SRC_DIR; skipping acquisition."
    "$SCRIPT_DIR/verify-weston.sh"
    exit $?
fi

rm -rf "$WORK_DIR" "$SRC_DIR"
mkdir -p "$WORK_DIR"

log "Cloning Weston from the development mirror (not distro): $WESTON_REPO (branch $WESTON_BRANCH)"
if ! git clone --depth 1 --branch "$WESTON_BRANCH" "$WESTON_REPO" "$SRC_DIR" 2>"$WORK_DIR/git.err"; then
    cat "$WORK_DIR/git.err" >&2 || true
    die "Unable to clone Weston source from $WESTON_REPO. Network access to the mirror host is required; no distro package is used as a fallback."
fi

[[ -f "$SRC_DIR/meson.build" ]] || die "Cloned source lacks meson.build — not a valid Weston tree."

RESOLVED_COMMIT="$(git -C "$SRC_DIR" rev-parse HEAD)"
printf '%s\n' "$RESOLVED_COMMIT" > "$SRC_DIR/.weston_commit"
log "Resolved Weston $WESTON_BRANCH commit: $RESOLVED_COMMIT"

BUILTIN_VERSION="$(grep -oE "version: *'[0-9]+\.[0-9]+\.[0-9]+'" "$SRC_DIR/meson.build" | head -1 | sed -E "s/.*'([0-9]+\.[0-9]+\.[0-9]+)'.*/\1/")"
if [[ -n "$BUILTIN_VERSION" ]]; then
    log "Source declares Weston version: $BUILTIN_VERSION"
fi

rm -rf "$WORK_DIR"

"$SCRIPT_DIR/verify-weston.sh" --strict-source
log "fetch-weston.sh complete."
