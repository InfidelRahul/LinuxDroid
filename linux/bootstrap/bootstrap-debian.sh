#!/usr/bin/env bash
# LinuxDroid — Debian ARM64 rootfs bootstrapper
#
# Downloads and extracts a minimal Debian arm64 rootfs into the specified
# environment directory.
#
# Usage:
#   bootstrap-debian.sh <env-id> <environments-base-dir>
#
# This script runs INSIDE Android via the app's internal storage.
# It must not require root.
#
# The resulting rootfs is a minimal Debian installation suitable for:
# - Running /bin/sh
# - Using apt to install packages
# - Running Wayland applications
#
# IMPORTANT: This script never deletes an existing rootfs.
# If the rootfs already exists, it exits successfully without changes.

set -euo pipefail

ENV_ID="${1:?Usage: $0 <env-id> <environments-base-dir>}"
BASE_DIR="${2:?Usage: $0 <env-id> <environments-base-dir>}"

ROOTFS_DIR="${BASE_DIR}/${ENV_ID}/rootfs"
METADATA_DIR="${BASE_DIR}/${ENV_ID}/metadata"
TMP_DIR="${BASE_DIR}/${ENV_ID}/tmp"

echo "[LinuxDroid Bootstrap] Environment: $ENV_ID"
echo "[LinuxDroid Bootstrap] Rootfs: $ROOTFS_DIR"

# Never recreate existing rootfs
if [ -d "$ROOTFS_DIR/bin" ] && [ -d "$ROOTFS_DIR/etc" ]; then
    echo "[LinuxDroid Bootstrap] Rootfs already exists. Skipping installation."
    exit 0
fi

mkdir -p "$ROOTFS_DIR" "$METADATA_DIR" "$TMP_DIR"

# Debian arm64 minimal rootfs URL
# Using official LinuxContainers infrastructure
DEBIAN_ROOTFS_URL="https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20260831_05:24/rootfs.tar.xz"
DEBIAN_ROOTFS_SHA256="1767187c73bf4f84376d2a48741efdf6bd2ca6c22295c1f1ab5934003de0cab4"
TARBALL="${TMP_DIR}/debian-arm64.tar.xz"

echo "[LinuxDroid Bootstrap] Downloading Debian arm64 rootfs..."
if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 "$DEBIAN_ROOTFS_URL" -o "$TARBALL"
elif command -v wget >/dev/null 2>&1; then
    wget -q --tries=3 "$DEBIAN_ROOTFS_URL" -O "$TARBALL"
else
    echo "[LinuxDroid Bootstrap] ERROR: Neither curl nor wget available." >&2
    exit 1
fi

echo "[LinuxDroid Bootstrap] Extracting rootfs..."
tar -xJf "$TARBALL" -C "$ROOTFS_DIR" --strip-components=1 2>/dev/null || \
    tar -xJf "$TARBALL" -C "$ROOTFS_DIR"

# Clean up tarball immediately to save space
rm -f "$TARBALL"

echo "[LinuxDroid Bootstrap] Configuring rootfs..."

# Write resolv.conf for DNS
mkdir -p "${ROOTFS_DIR}/etc"
cat > "${ROOTFS_DIR}/etc/resolv.conf" << 'RESOLV'
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 1.1.1.1
RESOLV

# Write basic hostname
echo "linuxdroid" > "${ROOTFS_DIR}/etc/hostname"

# Create home directory for the default user
mkdir -p "${ROOTFS_DIR}/home/user"

# Write metadata
cat > "${METADATA_DIR}/bootstrap.json" << METADATA
{
  "env_id": "$ENV_ID",
  "distribution": "DEBIAN",
  "architecture": "ARM64",
  "bootstrap_version": "1.0.0",
  "bootstrapped_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "rootfs_path": "$ROOTFS_DIR"
}
METADATA

echo "[LinuxDroid Bootstrap] Debian arm64 rootfs ready at: $ROOTFS_DIR"
echo "[LinuxDroid Bootstrap] Bootstrap complete."
