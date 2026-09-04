#!/bin/bash
# ==============================================================================
# LinuxDroid Git Auto-Healing Script
# Repairs corrupted 0-byte or truncated .git/index files from HEAD
# ==============================================================================

set -e

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
GIT_DIR="$(git rev-parse --git-dir 2>/dev/null || echo "$REPO_ROOT/.git")"

echo "Checking Git index health in: $GIT_DIR"

if [ -f "$GIT_DIR/index" ]; then
    INDEX_SIZE=$(stat -c %s "$GIT_DIR/index" 2>/dev/null || stat -f %z "$GIT_DIR/index" 2>/dev/null || echo 0)
    echo "Current .git/index size: $INDEX_SIZE bytes"
    
    if [ "$INDEX_SIZE" -lt 12 ]; then
        echo "Detected corrupted/truncated .git/index (< 12 bytes). Repairing..."
        rm -f "$GIT_DIR/index" "$GIT_DIR/index.lock"
        git -C "$REPO_ROOT" reset --quiet
        NEW_SIZE=$(stat -c %s "$GIT_DIR/index" 2>/dev/null || stat -f %z "$GIT_DIR/index" 2>/dev/null || echo 0)
        echo "Index successfully rebuilt! New size: $NEW_SIZE bytes"
    else
        echo "Git index is healthy."
    fi
else
    echo "No .git/index found. Rebuilding from HEAD..."
    rm -f "$GIT_DIR/index.lock"
    git -C "$REPO_ROOT" reset --quiet
    echo "Git index rebuilt."
fi

# Ensure fsync hardening is enabled
git config core.fsync "index,committed,loose-object,pack"
git config core.fsyncMethod fsync

echo "Repository status:"
git -C "$REPO_ROOT" status --short
