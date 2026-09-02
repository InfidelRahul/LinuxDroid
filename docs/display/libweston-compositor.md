# LinuxDroid — libweston Compositor Initialization (Phase 3)

> **Scope:** Phase 3 initializes and runs the resolved **libweston** (tracked
> from the `InfidelRahul/weston` development mirror `main`; produces
> `libweston-17` on the current mirror) inside the existing native GUI host, and
> adds the **LinuxDroid custom backend** integration point. It does **not**
> implement `weston_head` → Android display,
> `weston_output` → SurfaceControl, AHardwareBuffer, SurfaceControl buffer
> submission, Pixman/GLES/EGL/Vulkan rendering, input, a desktop shell, or a
> VSync/frame timeline. Those belong to later phases.

## Goal

Initialize and run a minimal embedded Weston compositor instance with a clean
lifecycle, driven by the existing `GuiHost` lifecycle:

```text
GuiHost::onGuiHostCreated()   ->  WestonHost::start()  (create + init + run)
GuiHost::onGuiHostDestroyed() ->  WestonHost::stop()   (terminate + tear down)
```

## Architecture

```text
Android Activity/UI
        ↓
SurfaceView (GuiSurfaceView)
        ↓
Native C/C++ GUI host (linuxdroid::GuiHost)
        ↓
linuxdroid::WestonHost
        ↓
libweston / Weston (mirror main)
        ↓
LinuxDroid custom Android backend (linuxdroid_backend.c)
        ↓
weston_output            (Phase 4+)
        ↓
AHardwareBuffer         (Phase 4+)
        ↓
ASurfaceControl/...     (Phase 4+)
```

## Implementation

### Compositor host (`native/bridge/src/main/cpp/weston_host.{h,cpp}`)

`linuxdroid::WestonHost` is a singleton with a minimal, deterministic lifecycle:

- `start()` creates a `wl_display`, a `weston_log_context`, and the
  `weston_compositor` (mirror-main API, unchanged from the 16 series:
  `weston_compositor_create(display, log_ctx, user_data, /*testsuite=*/NULL)`),
  registers the LinuxDroid backend via `linuxdroid_backend_init()`, and begins
  the libweston **event loop** on a dedicated worker thread
  (`wl_display_run()`).
- `stop()` calls `wl_display_terminate()`, joins the event-loop thread,
  destroys the compositor, log context, and display, and frees the compositor
  state. It is idempotent and safe to call repeatedly.

The worker thread is the libweston event-dispatch loop (Wayland socket) — it is
NOT a rendering/frame thread, and there is no frame scheduler.

### Custom backend (`native/bridge/src/main/cpp/linuxdroid_backend.c`)

`linuxdroid_backend_init()` allocates a `struct linuxdroid_backend` (whose first
member is `struct weston_backend`) and inserts it into
`compositor->backend_list` via `wl_list_insert()`, setting
`compositor->primary_backend` and `base.destroy` / `base.supported_presentation_clocks`.
This establishes the **backend/output integration point**. It creates no heads,
no outputs, and presents no buffers. A later phase extends the
`linuxdroid_backend` struct to map `weston_head` → Android display and
`weston_output` → AHardwareBuffer-backed SurfaceControl.

(The full `struct weston_backend` definition lives in the private
`libweston/backend.h`, which `build-libweston.sh` installs beside the public
headers so the bridge can embed it. Weston 16 has no `compositor->backend`
member — backends are tracked in `compositor->backend_list`.)

### Build integration (`native/bridge/src/main/cpp/CMakeLists.txt`)

The bridge auto-detects the resolved libweston build output under
`native/weston/dist` (overridable via `-DLINUXDROID_WESTON_PREFIX=...`):

- If found: `LINUXDROID_HAS_LIBWESTON` is defined, `linuxdroid_backend.c` is
  compiled, and the bridge links the installed libweston + libwayland-server.
- If not found (default checkout / local, without the CMake gate):
  `weston_host.cpp` compiles its no-libweston fallback (logs "libweston not
  built") and the app + CLI/runtime still build normally.

When the build is gated with `-PreqWeston` (CI), the bridge CMake sets
`LINUXDROID_REQUIRE_LIBWESTON=ON`, which makes `CMakeLists.txt` **fail the whole
build** if the real libweston install is not detected. A fallback-only build
therefore cannot pass a gated Phase 3 CI build.

### Lifecycle wiring (`native/bridge/src/main/cpp/gui_host.cpp`)

- `onGuiHostCreated()` → `WestonHost::getInstance().start()`
- `onGuiHostDestroyed()` → `WestonHost::getInstance().stop()` (before releasing
  the surface/window, so no compositor callback observes a torn-down window)
- Surface lifecycle (`onSurfaceCreated/Changed/Destroyed`) is unchanged and
  remains available for the next phase.

## Logging

Reuses the existing native logging (`__android_log_print` under
`LinuxDroid/WestonHost` and `LinuxDroid/WestonBackend`) — no new logging system.

## Verification status

This is the low-level compositor integration. Building and running it requires
the resolved libweston build (`native/weston/build-libweston.sh`) plus the
cross sysroot (from `native/weston/bootstrap-deps.sh`) and the Android
NDK/Meson toolchain. The CI workflow **does** now provision all of these:

1. `native/weston/fetch-weston.sh` — clone + record/verify the resolved
   `InfidelRahul/weston` mirror `main` commit.
2. `native/weston/verify-weston.sh --strict-source` — verify the resolved
   commit matches the checked-out HEAD.
3. `native/weston/bootstrap-deps.sh` — clone the InfidelRahul `main` deps
   (wayland, wayland-protocols, pixman) + record their commits, build a HOST
   wayland-scanner, then cross-build the dependency sysroot for arm64-v8a /
   API 36 (libwayland, wayland-protocols, pixman, libxkbcommon with X11
   disabled, xkeyboard-config XKB data, libinput, libevdev, libdrm,
   libdisplay-info, libffi, plus the Cairo/libpng/zlib build deps the mirror
   `main` requires). It also verifies every produced library is AArch64 ELF.
3b. `native/weston/verify-weston.sh --strict-source --strict-deps` — verify each
   tracked InfidelRahul dependency's recorded `main` commit.
4. `native/weston/build-libweston.sh` — cross-build libweston (mirror `main`,
   currently `libweston-17`) into `native/weston/dist`.
5. `./gradlew :app:assembleRelease -PreqWeston` — build the release APK with the
   native CMake gate `LINUXDROID_REQUIRE_LIBWESTON=ON`, which **fails** if the
   real libweston path is not detected.

Consequently:

- **Build verification** (CI): the release build compiles + links the real
  libweston path (`linuxdroid_backend.c` compiled, `weston_host.cpp` compiled
  with `LINUXDROID_HAS_LIBWESTON`, bridge links `libweston-<major>.so`). A
  fallback-only build cannot pass.
- **Device/runtime verification**: not performed in this environment; repeated
  host create/destroy cycling of the compositor must be exercised on-device
  once the resolved libweston is built and linked.
