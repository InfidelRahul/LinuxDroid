# LinuxDroid — libweston Compositor Initialization (Phase 3)

> **Scope:** Phase 3 initializes and runs the pinned **libweston 16.0.0** inside
> the existing native GUI host, and adds the **LinuxDroid custom backend**
> integration point. It does **not** implement `weston_head` → Android display,
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
libweston / Weston 16.0.0
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
  `weston_compositor` (Weston 16 API:
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

`linuxdroid_backend_init()` installs a `struct weston_backend` on
`compositor->backend`, establishing the **backend/output integration point**.
It creates no heads, no outputs, and presents no buffers. A later phase extends
the `linuxdroid_backend` struct to map `weston_head` → Android display and
`weston_output` → AHardwareBuffer-backed SurfaceControl.

### Build integration (`native/bridge/src/main/cpp/CMakeLists.txt`)

The bridge auto-detects the pinned libweston build output under
`native/weston/dist` (overridable via `-DLINUXDROID_WESTON_PREFIX=...`):

- If found: `LINUXDROID_HAS_LIBWESTON` is defined, `linuxdroid_backend.c` is
  compiled, and the bridge links the installed libweston + libwayland-server.
- If not found (default checkout / CI): `weston_host.cpp` compiles its
  no-libweston fallback (logs "libweston not built") and the app + CLI/runtime
  still build normally.

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
the pinned libweston 16.0.0 build (`native/weston/build-libweston.sh`) plus a
cross sysroot (libwayland-server, wayland-protocols, pixman) and the Android
NDK/Meson toolchain, which are not present in this repository's default CI
(CI builds the app + native bridge without libweston). Consequently:

- **Build verification** (CI): the default app/native build compiles the
  fallback path and the CLI/runtime modules; the real libweston path is
  conditionally compiled only when the pinned libweston artifacts are present.
- **Device/runtime verification**: not performed in this environment; repeated
  host create/destroy cycling of the compositor must be exercised on-device
  once the pinned libweston is built and linked.
