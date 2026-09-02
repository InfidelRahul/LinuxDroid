# LinuxDroid — Weston / libweston dependency (Milestone 1)

This module establishes the **dependency** for the LinuxDroid Android GUI.
It cross-builds the **matching libweston** and its dependency stack for
**Android arm64-v8a / API 36+** only, from a **deterministic, recorded source
manifest** ([`weston.spec.json`](./weston.spec.json)).

**Source policy.** Repositories under the **`InfidelRahul/` GitHub org**
(LinuxDroid mirrors — `weston`, `wayland`, `wayland-protocols`, `pixman`) are
tracked on their **`main`** branch: each build resolves the current `main` HEAD
and records that exact commit (there is **no fixed version/commit pin** for
them). All other (official upstream) dependencies use the **latest stable
release** and record its exact commit (`libxkbcommon` 1.13.2, `xkeyboard-config`
2.48, plus the stable `libffi`/`libdrm`/`libinput`/`libevdev`/
`libdisplay-info`/`zlib`/`libpng`/`cairo` build deps).

This is **Milestone 1**. Per the frozen architecture it intentionally does
**not** implement any GUI runtime: no custom Android backend, no
`weston_head`, no `weston_output`, no `AHardwareBuffer`, no
`ASurfaceControl`, no compositor startup, no input, no renderer integration,
no desktop shell. Those are later milestones.

This is **Milestone 1**. Per the frozen architecture it intentionally does
**not** implement any GUI runtime: no custom Android backend, no
`weston_head`, no `weston_output`, no `AHardwareBuffer`, no
`ASurfaceControl`, no compositor startup, no input, no renderer integration,
no desktop shell. Those are later milestones.

---

## Source (development mirror, `main` branch)

| Field | Value |
| --- | --- |
| Source type | git |
| Repository | `https://github.com/InfidelRahul/weston` (LinuxDroid development mirror) |
| Branch | `main` |
| Resolved commit | recorded per build in `native/weston/src/.weston_commit` |
| libweston major | `17` (on the current mirror `main`; produces `libweston-17`) |

The machine-readable authority for this source configuration is
[`weston.spec.json`](./weston.spec.json). There is intentionally **no fixed
version or commit**: because the mirror `main` moves, a different build may
resolve to a different commit — which is intended and is exactly what the
per-build recording captures. The resolved commit is the reproducibility
anchor for a given build.

## Source verification

Verification is **mechanistic**, not a human-readable comment. It is performed
by [`verify-weston.sh`](./verify-weston.sh), which asserts at minimum:

- The source is a checkout of the `InfidelRahul/weston` mirror (`main`).
- The recorded commit (`src/.weston_commit`) equals the actual git `HEAD`.
- The source declares a valid Weston version and an integer `libweston_major`.
- With `--strict-deps` (used after `bootstrap-deps.sh`), each tracked
  InfidelRahul dependency (`wayland`, `wayland-protocols`, `pixman`) has a
  recorded `main` commit (`deps/sysroot/.git-commits/<name>`) that equals its
  clone's git `HEAD`.

Verification reads the **actual** `meson.build` version and `libweston_major`,
reads `git rev-parse HEAD`, and cross-checks the recorded anchor against it
([`fetch-weston.sh`](./fetch-weston.sh) records the commit). No SHA-256 archive
anchor is needed because the source is a git clone, not a release tarball.

## Build integration

The existing LinuxDroid build system is **Gradle + Android NDK + CMake
externalNativeBuild**. That path is used by the existing `:native:bridge`
JNI module and must not be disrupted.

`libweston` is an upstream **Meson** project, so it is cross-built with Meson
against the Android NDK (`arm64-v8a`, API 36) using an isolated,
dependency-scoped build:

- [`bootstrap-deps.sh`](./bootstrap-deps.sh) — clones the InfidelRahul mirror
  `main` deps (wayland, wayland-protocols, pixman), records/verifies their
  resolved commits, builds a HOST `wayland-scanner`, then cross-builds the
  dependency sysroot (libwayland + wayland-protocols + pixman + libxkbcommon
  (X11 disabled) + xkeyboard-config data + libinput + libevdev + libdrm +
  libdisplay-info + libffi, plus the Cairo/libpng/zlib build deps that the
  mirror `main` requires at configure/link time) for the target. It also
  verifies every produced shared library is AArch64 ELF.
- [`build-libweston.sh`](./build-libweston.sh) — reproducible cross-build of
  libweston against the dependency sysroot into `native/weston/dist`.
- [`meson-cross-android-arm64.ini.in`](./meson-cross-android-arm64.ini.in) —
  Meson cross file template for the NDK arm64-v8a target.

This is the one place a non-Gradle build tool is used, and it is required
because the upstream `libweston` tree cannot be consumed by the Android
Gradle/CMake externalNativeBuild path. The build lives entirely under
`native/weston/` and does not touch the PRoot/CLI runtime.

A Gradle verification task, `verifyWeston` (defined in
`gradle/weston-dependency.gradle.kts`), wires the deterministic check into
the existing build graph without altering the app build. (`build/` and `src/`
are git-ignored generated artifacts.)

## Dependency policy

The mirror main's top-level `meson.build` **unconditionally** requires the
following pkg-config dependencies to configure (no option can disable them):

- `wayland-server`, `wayland-client` (from libwayland, `>= 1.24`)
- `wayland-protocols` (`>= 1.46`)
- `pixman`
- `xkbcommon`
- `libinput`
- `libevdev`
- `libdrm`
- `libdisplay-info`
- `wayland-scanner` (HOST tool + pkg-config)
- `libffi` (to build libwayland)

The mirror main's `shared/meson.build` also declares `lib_cairo_shared`
(`dependency('cairo')` + `dependency('libpng')`) unconditionally, and its
`headless-backend` links it — so **cairo + libpng + zlib** are required at
configure AND link time. These are **build dependencies only**: they are NOT
part of the LinuxDroid renderer ([Pixman → GLES/EGL → Vulkan]) and are NOT
linked into the LinuxDroid bridge.

Of the above, `libweston-17.so` links only `wayland-server`, `pixman`, `libdrm`
and `xkbcommon` (plus libm/libdl from the NDK); the rest are required at
configure/build time. All are cross-built by
[`bootstrap-deps.sh`](./bootstrap-deps.sh) into a single sysroot consumed by
[`build-libweston.sh`](./build-libweston.sh). Cairo/libpng/zlib are built as a
minimal image-only Cairo (freetype/fontconfig/glib/x11 disabled).

`xkeyboard-config` is **separate keyboard-layout data** (not `libxkbcommon`):
the bootstrap cross-installs its exported `2.48` XKB database into
`deps/sysroot/share/X11/xkb` so `libxkbcommon` can locate the keymap data at
runtime from the standard Linux XKB data path in the Linux (PRoot) userspace.
No Android host path is hardcoded and no Android keyboard config is copied.

The full Weston **desktop** stack is **not** built. The following are **not**
added as Weston/libweston dependencies:

`cage`, `xfce`, `gnome`, `kde`, `x11`, `xwayland`, `vnc`, `pipewire`,
a distro `weston`, or `libweston` built from any other source than the
resolved mirror-main checkout. `libxkbcommon` is built **without** X11/XCB
(`-Denable-x11=false`; no `libxkbcommon-x11`, no `xcb-xkb`).

Distro package installation is **never** used as a substitute for the resolved
build. (An APT hold may be used only as a secondary safeguard in the guest
userspace; it is never the mechanism that supplies Weston/libweston.)

## Scope guard

`weston.spec.json` records `disallowedDependencies` so a future backend or
packaging step can fail-fast if it tries to introduce an unsupported stack
component. Milestone 1 does not add any GUI runtime.

## Phase 3: embedded compositor consumption

Phase 3 (`native/bridge`) consumes this resolved libweston. The native bridge
CMake auto-detects the libweston installed here (`native/weston/dist`) and, when
present, compiles the compositor host (`weston_host.cpp`) + the LinuxDroid
custom backend (`linuxdroid_backend.c`) and links `libweston` +
`libwayland-server` (plus the transitively required `libpixman-1`, `libxkbcommon`
and `libdrm` which `build-libweston.sh` stages into `dist/lib`). When the
install is absent (default checkout / CI) it builds a no-libweston fallback so
the app and CLI/runtime are unaffected. See
[`docs/display/libweston-compositor.md`](../../docs/display/libweston-compositor.md).

## Usage

```bash
# 1. Fetch the InfidelRahul/weston mirror main and record/verify the resolved commit.
native/weston/fetch-weston.sh

# 2. Deterministically verify the resolved mirror-main source/commit.
native/weston/verify-weston.sh --strict-source

# 3. Cross-build the dependency sysroot (requires NDK r29 + meson/ninja/pkg-config
#    + libexpat1-dev for the HOST wayland-scanner). Clones the InfidelRahul main
#    deps, records their commits, builds the HOST wayland-scanner, cross-builds
#    all deps + xkeyboard-config data, and verifies the produced libs are AArch64.
ANDROID_NDK_ROOT=/opt/ndk \
DEP_SYSROOT="$PWD/native/weston/deps/sysroot" \
native/weston/bootstrap-deps.sh

# 3b. Verify the tracked InfidelRahul dependency commits (wayland/protocols/pixman).
DEP_SYSROOT="$PWD/native/weston/deps/sysroot" \
native/weston/verify-weston.sh --strict-source --strict-deps

# 4. Cross-build libweston against that sysroot into native/weston/dist.
ANDROID_NDK_ROOT=/opt/ndk \
DEP_SYSROOT="$PWD/native/weston/deps/sysroot" \
DEP_PKG_CONFIG_PATH="$PWD/native/weston/deps/sysroot/lib/pkgconfig:$PWD/native/weston/deps/sysroot/share/pkgconfig" \
native/weston/build-libweston.sh

# 5. Phase 3 native bridge now detects native/weston/dist and enables
#    LINUXDROID_HAS_LIBWESTON. Build the release APK with the real path gated:
./gradlew :app:assembleRelease -PreqWeston

# Verification tasks (do not require the NDK):
./gradlew verifyWeston          # resolved mirror-main source/commit
./gradlew verifyWestonBuild     # Phase 3 hard gate: real libweston present in dist
```
