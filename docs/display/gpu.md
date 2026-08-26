# LinuxDroid — GPU Acceleration & Capability Detection

## 1. Detection
`GpuManager` detects hardware acceleration through `GpuDetector` in `liblinuxdroid_bridge.so`:
- Queries EGL configuration (`eglQueryString`) for vendor, renderer, and EGL version.
- Probes OpenGL ES 2.0 / 3.0 / 3.2 support.
- Checks Vulkan capability (`VK_` extensions and `EGL_KHR_fence_sync`).

## 2. Software Fallback
If GPU hardware initialization is blocked by OEM driver constraints, `DefaultGpuManager` engages a software rasterizer fallback (`llvmpipe` / `swiftshader`), ensuring the session remains fully functional.

