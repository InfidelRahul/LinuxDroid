# LinuxDroid — Native Architecture (C++ / NDK)

## 1. Overview
The native layer (`liblinuxdroid_bridge.so`) centralizes all platform and hardware interactions in C++17, ensuring zero-overhead bridges for graphics, GPU detection, audio, and input.

## 2. Component Structure
- `linuxdroid_bridge.cpp` / `linuxdroid_bridge.h`: Central JNI entry point.
- `display_bridge.cpp` / `display_bridge.h`: `ANativeWindow` surface manager with buffer locking and geometry configuration.
- `gpu_detector.cpp` / `gpu_detector.h`: Queries EGL, OpenGL ES, and Vulkan extensions on Android hardware.
- `input_bridge.cpp` / `input_bridge.h`: Thread-safe FIFO input event queue mapping Android touch and keyboard codes.
- `audio_bridge.cpp` / `audio_bridge.h`: PCM stream sink and latency management.
- `process_manager.cpp` / `process_manager.h`: POSIX process signaling (`kill`).
- `filesystem_utils.cpp` / `filesystem_utils.h`: File executable permission verification (`stat`, `chmod`).

## 3. Ownership & Safety
- RAII memory ownership for all native allocations.
- No dangling `jobject` references across JNI boundaries.
- Thread-safe mutex guards on all hardware adapters.

