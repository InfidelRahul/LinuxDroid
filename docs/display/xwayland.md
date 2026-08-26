# LinuxDroid — XWayland Compatibility Layer

## 1. Overview
XWayland provides compatibility for legacy X11 Linux applications without replacing the primary Wayland pipeline.

## 2. Pipeline
```
X11 Linux Application (e.g. gedit, xterm)
         ↓ (X11 socket :0)
      XWayland
         ↓ (Wayland protocol)
  Wayland Compositor
         ↓
  ANativeWindow
```
XWayland is started as a child bridge process when `DesktopConfig.xwaylandEnabled = true`.

