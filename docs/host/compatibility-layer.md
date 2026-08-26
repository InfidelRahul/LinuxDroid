# LinuxDroid — Host Compatibility Layer

## 1. Purpose
Exposes Android's platform capabilities (Graphics, GPU, Audio, Input, Storage, Network, Camera, Sensors) to the Linux userspace through clean interfaces without pretending Android is a standard desktop Linux kernel.

## 2. High-Performance Design
- High-frequency paths (framebuffer rendering, touch events, PCM audio) are executed in C++ via `NativeBridge`.
- Low-frequency paths (configuration, lifecycle, networking status) are coordinated in Kotlin.
- Avoids Java/JNI loops for active rendering and audio streams.
