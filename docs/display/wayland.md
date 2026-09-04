# LinuxDroid — Wayland Compositor & Display Architecture

## 1. Overview & End-to-End Pipeline

LinuxDroid implements a native, embedded Wayland graphical environment running directly inside the Android application process. Rather than relying on VNC, X11 network forwarding, or standalone server apps, LinuxDroid embeds the official **libweston-17** compositor into its native C++ runtime and presents rendered frames directly to Android's `SurfaceFlinger` through high-performance Android NDK APIs.

```text
Android UI (GuiSurfaceView)
           │
           ▼
Native GuiHost / JNI (NativeBridge)
           │
 ┌─────────┴─────────┐
 │                   ▼
 │       VSyncBridge (AChoreographer)
 │                   │
 │                   ▼
 │       libweston Repaint Scheduler
 │                   │
 ▼                   ▼
LinuxDroid Backend (linuxdroid_backend.c)
 │                   │
 │                   ▼
 │          Weston Scene Graph
 │                   │
 │                   ▼
 │         GLES / EGL Renderer
 │                   │
 │                   ▼
 │     AHardwareBuffer Pool (Triple-Buffered)
 │                   │
 └───────────────────┼───────────────────┐
                     ▼                   ▼
         ASurfaceControl Transaction (Sync Fences)
                     │
                     ▼
         Android SurfaceFlinger
                     │
                     ▼
         Android Physical Display (60/90/120 Hz)
```

---

## 2. Embedded Compositor Engine

* **Host Process:** The compositor runs on a dedicated background native thread managed by `GuiHost`. It does not block the Android UI thread or the guest application execution thread.
* **libweston-17:** LinuxDroid uses upstream `libweston-17` compiled for ARM64 (`aarch64-linux-android`) with standard Wayland protocols (`xdg-shell`, `linux-dmabuf`, `presentation-time`, `viewporter`).
* **Single-Threaded Compositor Model:** libweston is strictly single-threaded. To avoid data races:
  - All Android lifecycle events, surface changes, and window management actions are posted to a thread-safe action queue (`PendingWindowAction`).
  - An `eventfd` (`wake_fd_`) wakes up the Wayland event loop, which drains and executes pending actions safely on the compositor thread.

---

## 3. LinuxDroid Custom Backend (`linuxdroid_backend.c`)

The compositor uses a custom libweston backend written specifically for Android:
* **Head & Output Management:** Dynamically creates a virtual `weston_output` matching the exact physical pixel dimensions and density (DPI) of the Android `SurfaceView`.
* **Renderer Initialization:** Loads and initializes the hardware GLES renderer (`gl-renderer`) with automatic fallback to the Pixman software renderer if EGL context initialization fails.
* **Repaint Loop:** Integrates with `android_presentation` to acquire offscreen render buffers, invoke libweston surface repainting, and submit finished frames to the display pipeline.
* **Repaint Storm Protection:** On unrecoverable buffer acquisition errors (e.g. during surface destruction or backgrounding), repaints are cancelled rather than rescheduled immediately in an infinite busy loop.

---

## 4. Hardware-Accelerated GLES Renderer & Presentation Pipeline

### 4.1 GLES / EGL Renderer (`libgl-renderer.so`)
* Configured using Android's native EGL display (`EGL_DEFAULT_DISPLAY`).
* Creates offscreen GLES 3.0 render targets matching the output resolution.
* Supports texture mapping of Wayland client SHM buffers and hardware DMA-BUF textures.

### 4.2 Triple-Buffered AHardwareBuffer Pool
* Implemented in `android_presentation.cpp`.
* Manages a thread-safe pool of `AHardwareBuffer` instances configured with:
  - Format: `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` (or `RGBX_8888`)
  - Usage: `AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY`
* **Buffer State Machine:**
  ```text
  FREE ──acquire──> ACQUIRED ──submit──> SUBMITTED ──release──> FREE
  ```
* **Asynchronous Buffer Release:**
  - Uses `ASurfaceTransaction_setBufferWithRelease` (API 36+) or fence-based callbacks to return buffers to the pool once SurfaceFlinger completes composition.
  - Implements a lifetime token mechanism to eliminate use-after-free bugs on asynchronous Binder callbacks.

---

## 5. Android VSync & Display Timing (`vsync_bridge.cpp`)

To eliminate tearing, judder, and unnecessary power consumption, LinuxDroid synchronizes compositor repaints directly to Android's physical display clock:

* **Dedicated ALooper Thread:** Hosts `AChoreographer` on a dedicated thread with an Android event loop.
* **Dynamic Refresh-Rate Adaptation:** Listens for display refresh rate changes via `AChoreographer_registerRefreshRateCallback`, seamlessly adapting between 60 Hz, 90 Hz, and 120 Hz displays.
* **Authoritative Clock Synchronization:** Converts Android VSync timestamps (`CLOCK_MONOTONIC`) into libweston repaint schedules (`timespec`).
* **Decoupled Frame Completion:** `weston_output_finish_frame` is driven by authoritative display VSync completion signals rather than artificial timers.
* **Zero-Wake Idle Gating:** When clients produce no new damage or animations, `wake_requested_` remains false, guaranteeing zero CPU/GPU wakeups while the desktop is idle.
* **Pause / Resume Recovery:** On Android Activity pause/resume, callback pending state is tracked and gracefully re-armed, preventing VSync pipeline stalls.

---

## 6. Minimal Wayland Desktop Shell (`DesktopShellClient`)

LinuxDroid includes a built-in, lightweight Wayland desktop shell client that runs inside the Wayland session as an independent client:

* **3-Tier Layer Hierarchy:**
  1. **Background Layer:** Fullscreen wallpaper surface (`#181f2a` slate with branding) rendered via zero-dependency SHM ARGB8888.
  2. **Normal Layer:** Standard guest application windows (`xdg_toplevel`).
  3. **UI / Panel Layer:** Bottom-anchored 48px taskbar panel that remains above normal windows.
* **Taskbar Features:**
  - **Launcher Button:** Opens a popup menu to launch guest Linux applications (Terminal, File Manager, Text Editor, Web Browser).
  - **Dynamic Window List:** Displays active application windows with title pills; clicking a pill raises and activates the corresponding window.
  - **Digital Clock:** Real-time system clock updated every minute.
* **Dynamic Geometry:** Automatically adapts to orientation changes, multi-window mode, and screen resolution adjustments without restarting guest applications.
* **DesktopWindowTracker:** Provides thread-safe, bidirectional state synchronization between the libweston compositor scene graph and the desktop shell UI.
* **Failure Isolation:** If the desktop shell process terminates unexpectedly, `GuiHost::restartDesktopShell()` cleanly recreates the shell UI without restarting the compositor or killing running guest applications.

---

## 7. Lifecycle & Process Management

* **Surface Lifecycle:**
  - `onSurfaceCreated(surface, width, height)`: Attaches the native window and initializes or resumes presentation.
  - `onSurfaceChanged(surface, width, height, format)`: Updates compositor output geometry and reallocates the buffer pool.
  - `onSurfaceDestroyed()`: Pauses the VSync bridge and safely drains in-flight buffers before releasing native window handles.
* **Zombie Process Reaping:** Installs a `SIGCHLD` event source on the Wayland event loop (`wl_event_loop_add_signal`) that reaps terminated guest processes non-blockingly via `waitpid(-1, &status, WNOHANG)`.

