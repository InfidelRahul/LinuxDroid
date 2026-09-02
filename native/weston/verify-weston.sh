#!/usr/bin/env bash
#
# LinuxDroid — Deterministic Weston / libweston source verification.
#
# Verifies that the checked-out Weston source (native/weston/src) is the
# InfidelRahul/weston development mirror's `main` branch, and that the build's
# recorded commit (src/.weston_commit) matches the git HEAD actually checked out.
# Because `main` moves, there is intentionally NO fixed version or commit: the
# resolved commit (git HEAD) is the per-build reproducibility anchor, and this
# script asserts the recorded anchor equals the checked-out revision.
#
#   Source          = git mirror https://github.com/InfidelRahul/weston (main)
#   Resolved commit = read from git HEAD; recorded in src/.weston_commit
#
# Exit status:
#   0  verification passed
#   1  verification failed
#   2  verification could not be completed (e.g. source not fetched)
#
# Usage:
#   native/weston/verify-weston.sh
#   native/weston/verify-weston.sh --strict-source   # require real source/commit
#
# Invoked from build-libweston.sh, the Gradle verifyWeston/verifyWestonBuild
# tasks (gradle/weston-dependency.gradle.kts), and fetch-weston.sh.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC_FILE="$SCRIPT_DIR/weston.spec.json"
SRC_DIR="$SCRIPT_DIR/src"

WESTON_REPO="https://github.com/InfidelRahul/weston"
WESTON_BRANCH="main"

STRICT_SOURCE=0
STRICT_DEPS=0
for arg in "$@"; do
    case "$arg" in
        --strict-source) STRICT_SOURCE=1 ;;
        --strict-deps)   STRICT_DEPS=1 ;;
        *) die "unknown argument: $arg" ;;
    esac
done

log() { printf '[weston-verify] %s\n' "$*"; }
die()  { printf '[weston-verify] FAIL: %s\n' "$*" >&2; exit 1; }

# --- Load expected source config from the manifest (authoritative) ------------
spec_repo=""
spec_track=""
if [[ -f "$SPEC_FILE" ]]; then
    # Parse the "weston" dependency block specifically (the spec now lists many
    # dependencies, so we must not match the first repository/track blindly).
    spec_block="$(awk '/"weston"[[:space:]]*:/{f=1} f{print} f && /^[[:space:]]*}[[:space:]]*,?[[:space:]]*$/ && NR>1 && !/libweston/{exit}' "$SPEC_FILE")"
    spec_repo="$(printf '%s\n' "$spec_block" | grep -o '"source": *"[^"]*"' | head -1 | sed -E 's/.*"source": *"([^"]*)"/\1/')"
    spec_track="$(printf '%s\n' "$spec_block" | grep -o '"track": *"[^"]*"' | head -1 | sed -E 's/.*"track": *"([^"]*)"/\1/')"
fi

if [[ -n "$spec_repo" ]]; then
    [[ "$spec_repo" == "$WESTON_REPO" ]] || die "manifest repository '$spec_repo' != frozen constant '$WESTON_REPO'"
fi
if [[ -n "$spec_track" ]]; then
    [[ "$spec_track" == "$WESTON_BRANCH" ]] || die "manifest track '$spec_track' != frozen constant '$WESTON_BRANCH'"
fi

# --- Source-level verification -------------------------------------------------
source_present=0
source_version=""
libweston_major=""
resolved_commit=""
recorded_commit=""
source_repo=""

if [[ -d "$SRC_DIR" && -f "$SRC_DIR/meson.build" ]]; then
    source_present=1
    source_version="$(grep -oE "version: *'[0-9]+\.[0-9]+\.[0-9]+'" "$SRC_DIR/meson.build" 2>/dev/null \
        | head -1 | sed -E "s/.*'([0-9]+\.[0-9]+\.[0-9]+)'.*/\1/")"
    libweston_major="$(grep -oE '^libweston_major *= *[0-9]+' "$SRC_DIR/meson.build" 2>/dev/null \
        | head -1 | sed -E 's/.*= *([0-9]+).*/\1/')"

    if [[ -d "$SRC_DIR/.git" ]]; then
        resolved_commit="$(git -C "$SRC_DIR" rev-parse HEAD 2>/dev/null || true)"
        source_repo="$(git -C "$SRC_DIR" config --get remote.origin.url 2>/dev/null || true)"
    fi
    if [[ -f "$SRC_DIR/.weston_commit" ]]; then
        recorded_commit="$(tr -d '[:space:]' < "$SRC_DIR/.weston_commit")"
    fi
else
    log "No source tree at $SRC_DIR; falling back to spec-level verification only."
fi

# --- Evaluate -------------------------------------------------------------------
if [[ "$source_present" -eq 1 ]]; then
    if [[ -z "$source_version" ]]; then
        die "Unable to read Weston version from meson.build."
    fi
    log "Source Weston version: $source_version"

    if [[ -z "$libweston_major" || ! "$libweston_major" =~ ^[0-9]+$ ]]; then
        die "Could not read a valid integer libweston_major from meson.build."
    fi
    log "Source libweston_major: $libweston_major"

    if [[ -n "$source_repo" ]]; then
        [[ "$source_repo" == "$WESTON_REPO" ]] || die "Source clone origin is '$source_repo', expected '$WESTON_REPO'."
        log "Source origin OK: $source_repo"
    fi

    if [[ -n "$resolved_commit" ]]; then
        log "Resolved git HEAD: $resolved_commit"
        if [[ -n "$recorded_commit" && "$recorded_commit" != "$resolved_commit" ]]; then
            die "Recorded commit '$recorded_commit' != checked-out HEAD '$resolved_commit'."
        fi
        if [[ -z "$recorded_commit" ]]; then
            if [[ "$STRICT_SOURCE" -eq 1 ]]; then
                die "Strict source mode requested but no recorded commit found in src/.weston_commit."
            fi
            log "Note: no recorded commit in .weston_commit; relying on git HEAD only."
        else
            log "Recorded commit OK: $recorded_commit"
        fi
    else
        if [[ "$STRICT_SOURCE" -eq 1 ]]; then
            die "Strict source mode requested but no git checkout available to read HEAD."
        fi
        log "Note: commit could not be read (no git checkout); relying on recorded .weston_commit."
    fi
else
    if [[ "$STRICT_SOURCE" -eq 1 ]]; then
        die "Strict source mode requested but source tree not present (run fetch-weston.sh)."
    fi
    log "Manifest source config passed (repository=$WESTON_REPO branch=$WESTON_BRANCH). Source not present; run fetch-weston.sh to acquire and verify the exact source."
fi

# --- Verify tracked InfidelRahul dependency commits (wayland, protocols, pixman) --
# bootstrap-deps.sh clones each InfidelRahul mirror from `main` and records the
# resolved commit under $DEP_SYSROOT/.git-commits/<name>. When those clones are
# present, assert each recorded commit equals the clone's git HEAD so a moved
# `main` is only ever consumed after its commit is recorded and re-verified.
DEP_SYSROOT="${DEP_SYSROOT:-$SCRIPT_DIR/deps/sysroot}"
TRACKED_DEP_NAMES=(wayland wayland-protocols pixman)
if [[ "$STRICT_DEPS" -eq 1 ]]; then
    commits_dir="$DEP_SYSROOT/.git-commits"
    if [[ ! -d "$commits_dir" ]]; then
        die "Strict-deps mode requested but no $commits_dir (run native/weston/bootstrap-deps.sh)."
    fi
    for name in "${TRACKED_DEP_NAMES[@]}"; do
        rec="$commits_dir/$name"
        clone="$SCRIPT_DIR/deps/src/$name"
        if [[ ! -f "$rec" ]]; then
            die "Tracked dependency '$name' has no recorded commit at $rec."
        fi
        recorded="$(tr -d '[:space:]' < "$rec")"
        if [[ -z "$recorded" ]]; then
            die "Tracked dependency '$name' has an empty recorded commit."
        fi
        if [[ -d "$clone/.git" ]]; then
            head="$(git -C "$clone" rev-parse HEAD 2>/dev/null || true)"
            [[ "$head" == "$recorded" ]] || die "Tracked dependency '$name' recorded commit '$recorded' != clone HEAD '$head'."
        fi
        log "Tracked dependency '$name' recorded commit OK: $recorded"
    done
elif [[ -d "$DEP_SYSROOT/.git-commits" ]]; then
    for name in "${TRACKED_DEP_NAMES[@]}"; do
        if [[ -f "$DEP_SYSROOT/.git-commits/$name" ]]; then
            rec="$(tr -d '[:space:]' < "$DEP_SYSROOT/.git-commits/$name")"
            log "Tracked dependency '$name' recorded commit: $rec"
        fi
    done
fi

if [[ -n "$resolved_commit" ]]; then
    anchor="$resolved_commit"
elif [[ -n "$recorded_commit" ]]; then
    anchor="$recorded_commit"
else
    anchor="unknown"
fi
log "verify-weston.sh PASSED (Weston source = $WESTON_REPO branch $WESTON_BRANCH @ resolved commit $anchor)."
exit 0
