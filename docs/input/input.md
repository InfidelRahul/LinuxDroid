# LinuxDroid — Input Subsystem

## 1. Input Architecture
```
Android Touch / Pointer / Key Events
               ↓
`InputBridge` (native FIFO event queue)
               ↓
Wayland Input Protocol / evdev codes
               ↓
Linux Desktop Applications
```

## 2. Event Routing
- **Touch:** Mapped to screen resolution with clamped bounds and pressure metrics.
- **Mouse:** Supports left, right, middle clicks, hover moves, and vertical/horizontal scrolling.
- **Keyboard:** Converts Android keycodes to standard Linux `linux/input-event-codes.h` keysyms and unicode characters.
