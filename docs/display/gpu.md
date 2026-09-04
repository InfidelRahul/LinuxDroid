# LinuxDroid — GPU Acceleration & Rendering Architecture

## 1. Overview

LinuxDroid provides a flexible, high-performance rendering architecture tailored for Android SoC graphics drivers. Graphical rendering occurs at two distinct layers:
1. **Compositor Rendering (libweston):** The embedded compositor uses a native OpenGL ES 3.0+ hardware renderer (`gl-renderer`) to composite all active Wayland client surfaces into Android output buffers, with an automatic fallback to an optimized Pixman software renderer.
2. **Guest Client Acceleration:** Wayland client applications running inside the rootless Linux userspace can render via standard software rasterization (`llvmpipe`), native Wayland SHM shared memory, or hardware-accelerated Vulkan/Zink drivers.

```text
┌─────────────────────────────────────────────────────────────┐
│                 Guest Wayland Clients                       │
│    (GUI Apps, Desktop Shell, Browsers, Terminal Emulators)  │
└──────────────┬───────────────────────────────┬──────────────┘
               │ SHM Buffers                   │ DMA-BUF / GLES
               ▼                               ▼
┌─────────────────────────────────────────────────────────────┐
│              libweston Compositor Repaint Loop              │
│  ┌─────────────────────────────┐ ┌───────────────────────┐  │
│  │ GLES Renderer (gl-renderer) │ │ Pixman Software (CPU) │  │
│  │ - EGL Display & Context     │ │ - NEON-accelerated    │  │
│  │ - Offscreen FBO / Textures  │ │ - Automatic Fallback  │  │
│  └──────────────┬──────────────┘ └───────────┬───────────┘  │
└─────────────────┼────────────────────────────┼──────────────┘
                  │ Rendered Frame             │ Rendered Frame
                  ▼                            ▼
┌─────────────────────────────────────────────────────────────┐
│               Android Presentation Pipeline                 │
│         AHardwareBuffer Pool (GPU/Overlay Usage)            │
│                       ASurfaceControl                       │
│                    Android SurfaceFlinger                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. GLES / EGL Hardware Renderer (`libgl-renderer.so`)

The primary rendering engine for the compositor is the embedded `gl-renderer` plugin, compiled specifically for Android's Bionic C library and NDK OpenGL ES headers.

### 2.1 EGL Initialization on Android
* **Display Connection:** Connects directly to the Android platform display using `EGL_DEFAULT_DISPLAY`.
* **Config Matching:** Selects an EGL configuration compatible with Android `AHardwareBuffer`:
  - `EGL_RENDERABLE_TYPE`: `EGL_OPENGL_ES3_BIT` (or `EGL_OPENGL_ES2_BIT` on legacy hardware)
  - `EGL_SURFACE_TYPE`: `EGL_WINDOW_BIT | EGL_PBUFFER_BIT`
  - Color Channels: 8-bit Red, Green, Blue, Alpha
* **Context Creation:** Creates an OpenGL ES 3.0 context (`EGL_CONTEXT_CLIENT_VERSION = 3`).

### 2.2 Texture Sampling & Shaders
* Client surfaces (whether ARGB8888 SHM buffers, YUV video frames, or hardware DMA-BUFs) are bound to EGL image targets or uploaded to 2D textures.
* Weston's fragment shaders handle color space conversion, alpha blending, window clipping, and transformation matrices with hardware GPU acceleration.

---

## 3. Pixman Software Renderer Fallback

To guarantee 100% reliability across all Android devices—including devices with non-standard OEM GPU driver constraints, sandboxed EGL restrictions, or headless emulators—LinuxDroid includes an integrated **Pixman software renderer**:

* **AArch64 NEON Optimization:** Compiled with `-Da64-neon=enabled` to utilize 128-bit ARM NEON SIMD vector registers for image compositing and blitting.
* **Automatic Graceful Fallback:** If `gl-renderer` fails to acquire an EGL display or create a valid context, the LinuxDroid backend automatically falls back to `pixman_renderer_init` without crashing the application.
* **Direct CPU Blitting:** Renders directly into mapped `AHardwareBuffer` CPU memory regions using `AHardwareBuffer_lock` and `AHardwareBuffer_unlock`.

---

## 4. Hardware Acceleration & Future Graphics Capabilities

### 4.1 Mesa Zink over Android Vulkan
LinuxDroid is architected to support Mesa's **Zink** driver inside the rootless Linux userspace. Zink translates standard desktop OpenGL (up to GL 4.6) into Vulkan API calls, which are executed directly on the host SoC's native Android Vulkan driver (`/system/lib64/libvulkan.so` or vendor drivers like Adreno / Mali).

### 4.2 VirGL Hardware Passthrough
For client workloads requiring virtualized 3D acceleration, VirGL creates an IPC command buffer stream between guest Linux applications and the host renderer, allowing 3D draw calls to be evaluated directly on Android's native GPU.

