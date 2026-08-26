# LinuxDroid — Wayland Compositor & Display Architecture

## 1. Pipeline
Wayland is the primary graphical system:
```
Linux Application
       ↓ (Wayland client)
Wayland Compositor (cage / weston)
       ↓ (liblinuxdroid_bridge.so)
ANativeWindow / Android Surface
       ↓
Android Graphics Stack & Display
```

## 2. DisplayManager Lifecycle
- `onSurfaceCreated(surface, width, height)`: Attaches native window buffer.
- `onSurfaceChanged(surface, width, height, format)`: Updates buffer geometry and DPI.
- `onSurfaceDestroyed()`: Releases `ANativeWindow` safely to prevent crashes across Activity recreation.
