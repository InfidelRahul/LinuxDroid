# LinuxDroid — Weston / libweston Dependency (Milestone 1)

> **Scope:** Milestone 1 establishes the **pinned Weston 16.0.0 + matching
> libweston** build dependency for the Android GUI. It does **not** implement
> any GUI runtime, backend, compositor startup, display output, rendering,
> input, or desktop shell.

## Frozen GUI architecture (GUI stack)

```text
Android Activity/UI
        ↓
SurfaceView
        ↓
Native C/C++ GUI host
        ↓
libweston / Weston 16.0.0
        ↓
LinuxDroid custom Android backend
        ↓
weston_output
        ↓
AHardwareBuffer
        ↓
ASurfaceControl / ASurfaceTransaction
        ↓
SurfaceFlinger
        ↓
Android display
```

Renderer strategy:

```text
Pixman      -> initial bring-up   (Milestone > 1)
GLES/EGL    -> production GPU     (Milestone > 1)
Vulkan      -> future/optional    (Milestone > 1)
```

## Pinned source

| Field | Value |
| --- | --- |
| Weston version | `16.0.0` |
| Pinned commit | `d1882b0a544ae2197b597a6e39478e719bc54302` |
| Release archive SHA-256 | `dfb32e2bccabda957b94a8d0ec6075acd18c71c87ebc543ee3e618d294ca0f7f` |

The authority for these pins is [`native/weston/weston.spec.json`](../../native/weston/weston.spec.json).
The `libweston` used is produced from the **same** pinned source — the
dependency graph is internally consistent by construction.

## Deterministic verification

Verification is performed by [`native/weston/verify-weston.sh`](../../native/weston/verify-weston.sh)
(and mirrored by the Gradle `verifyWeston` task) and asserts, at minimum:

- `Weston version == 16.0.0`
- `Pinned source revision == d1882b0a544ae2197b597a6e39478e719bc54302`

The version is parsed from the actual source `meson.build`; the commit is read
from the source git checkout when available (or from the recorded
`.weston_commit`, anchored by the verified archive SHA-256). This is a
mechanism, not a comment.

## Build

The cross-build targets **only** `arm64-v8a` / **API 36+** using the Android
NDK. No x86, x86_64, or ARM32 targets are produced. See
[`native/weston/build-libweston.sh`](../../native/weston/build-libweston.sh).

`libweston` is an upstream Meson project, so it is cross-built with Meson
against an NDK cross file. This is the single, justified departure from the
Gradle/CMake externalNativeBuild path (which cannot consume the upstream
Meson tree). The build is isolated under `native/weston/` and does not touch
the PRoot / CLI runtime.

## Dependency policy

Minimum dependencies required by `libweston` for the future custom backend:

- `libwayland-server`
- `wayland-protocols`
- `pixman`

Explicitly **not** added as Weston dependencies (fail-fast list in
`weston.spec.json`): `cage`, `xfce`, `gnome`, `kde`, `x11`, `xwayland`, `vnc`,
`pipewire`, distro `weston`, `libweston` from another release.

Distro package installation is **never** used as a substitute for the pinned
build. An APT hold on `weston` may be applied only as a secondary safeguard in
the guest userspace; it is never what supplies Weston/libweston.

## Status

Milestone 1 is **scaffolding + dependency establishment only**. See
[`native/weston/README.md`](../../native/weston/README.md) for the operational
commands and the strictly out-of-scope list.
