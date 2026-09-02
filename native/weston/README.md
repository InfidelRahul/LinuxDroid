# LinuxDroid — Weston / libweston dependency (Milestone 1)

This module establishes the **dependency** for the LinuxDroid Android GUI.
It pins the exact **Weston 16.0.0** source and builds the **matching
libweston**, for **Android arm64-v8a / API 36+** only.

This is **Milestone 1**. Per the frozen architecture it intentionally does
**not** implement any GUI runtime: no custom Android backend, no
`weston_head`, no `weston_output`, no `AHardwareBuffer`, no
`ASurfaceControl`, no compositor startup, no input, no renderer integration,
no desktop shell. Those are later milestones.

---

## Pinned source

| Field | Value |
| --- | --- |
| Weston version | `16.0.0` |
| Pinned commit | `d1882b0a544ae2197b597a6e39478e719bc54302` |
| Release archive SHA-256 | `dfb32e2bccabda957b94a8d0ec6075acd18c71c87ebc543ee3e618d294ca0f7f` |
| libweston version | `16.0.0` (produced from the **same** pinned source) |

The machine-readable authority for these pins is
[`weston.spec.json`](./weston.spec.json).

Quoting the frozen architecture decision:

```text
Version: 16.0.0
Commit:  d1882b0a544ae2197b597a6e39478e719bc54302
Official release archive SHA-256:
dfb32e2bccabda957b94a8d0ec6075acd18c71c87ebc543ee3e618d294ca0f7f
```

## Source verification

Verification is **mechanistic**, not a human-readable comment. It is performed
by [`verify-weston.sh`](./verify-weston.sh), which asserts at minimum:

- Weston version == `16.0.0`
- Pinned source revision == `d1882b0a544ae2197b597a6e39478e719bc54302`

Verification reads the **actual** `meson.build` version and, when a git
checkout is available, `git rev-parse HEAD`. The archive is checked against
the frozen SHA-256 before it is ever unpacked
([`fetch-weston.sh`](./fetch-weston.sh)).

## Build integration

The existing LinuxDroid build system is **Gradle + Android NDK + CMake
externalNativeBuild**. That path is used by the existing `:native:bridge`
JNI module and must not be disrupted.

`libweston` is an upstream **Meson** project, so it is cross-built with Meson
against the Android NDK (`arm64-v8a`, API 36) using an isolated,
dependency-scoped build:

- [`bootstrap-deps.sh`](./bootstrap-deps.sh) — cross-builds the dependency
  sysroot (libwayland + wayland-protocols + pixman + xkbcommon + libinput +
  libevdev + libdrm + libdisplay-info + libffi) for the target.
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

Weston 16.0.0's top-level `meson.build` **unconditionally** requires the
following pkg-config dependencies to configure (no option can disable them):

- `wayland-server`, `wayland-client` (from libwayland)
- `wayland-protocols`
- `pixman`
- `xkbcommon`
- `libinput`
- `libevdev`
- `libdrm`
- `libdisplay-info`
- `wayland-scanner` (HOST tool + pkg-config)
- `libffi` (to build libwayland)

Of these, `libweston-16.so` links only `wayland-server`, `pixman`, `libdrm`
and `xkbcommon` (plus libm/libdl from the NDK); the rest are required at
configure time. All are cross-built by [`bootstrap-deps.sh`](./bootstrap-deps.sh)
into a single sysroot consumed by [`build-libweston.sh`](./build-libweston.sh).

The full Weston **desktop** stack is **not** built. The following are **not**
added as Weston/libweston dependencies:

`cage`, `xfce`, `gnome`, `kde`, `x11`, `xwayland`, `vnc`, `pipewire`,
a distro `weston`, or `libweston` built from any release other than the
pinned 16.0.0 source.

Distro package installation is **never** used as a substitute for the pinned
build. (An APT hold may be used only as a secondary safeguard in the guest
userspace; it is never the mechanism that supplies Weston/libweston.)

## Scope guard

`weston.spec.json` records `disallowedDependencies` so a future backend or
packaging step can fail-fast if it tries to introduce an unsupported stack
component. Milestone 1 does not add any GUI runtime.

## Phase 3: embedded compositor consumption

Phase 3 (`native/bridge`) consumes this pinned libweston. The native bridge
CMake auto-detects the libweston installed here (`native/weston/dist`) and, when
present, compiles the compositor host (`weston_host.cpp`) + the LinuxDroid
custom backend (`linuxdroid_backend.c`) and links `libweston` +
`libwayland-server`. When the install is absent (default checkout / CI) it
builds a no-libweston fallback so the app and CLI/runtime are unaffected. See
[`docs/display/libweston-compositor.md`](../../docs/display/libweston-compositor.md).

## Usage

```bash
# 1. Acquire + verify the pinned source (requires network to upstream host).
native/weston/fetch-weston.sh

# 2. Deterministically verify version + commit.
native/weston/verify-weston.sh --strict-source

# 3. Cross-build the dependency sysroot (requires NDK r29 + meson/ninja/pkg-config).
ANDROID_NDK_ROOT=/opt/ndk \
native/weston/bootstrap-deps.sh

# 4. Cross-build libweston against that sysroot into native/weston/dist.
ANDROID_NDK_ROOT=/opt/ndk \
DEP_SYSROOT="$PWD/native/weston/deps/sysroot" \
DEP_PKG_CONFIG_PATH="$PWD/native/weston/deps/sysroot/share/pkgconfig" \
native/weston/build-libweston.sh

# 5. Phase 3 native bridge now detects native/weston/dist and enables
#    LINUXDROID_HAS_LIBWESTON. Build the release APK with the real path gated:
./gradlew :app:assembleRelease -PreqWeston

# Verification tasks (do not require the NDK):
./gradlew verifyWeston          # version + commit
./gradlew verifyWestonBuild     # Phase 3 hard gate: real libweston present in dist
```
