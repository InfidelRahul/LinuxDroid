# LinuxDroid — Input Subsystem & Event Translation Architecture

## 1. Overview & Data Flow

LinuxDroid captures user interactions directly from Android's UI system (`MotionEvent` and `KeyEvent` from `GuiSurfaceView`), translates them into standard Linux `evdev` events, and feeds them into the embedded `libweston` seat to deliver seamless touch, mouse, and keyboard input to Wayland client applications.

```text
Android View (GuiSurfaceView)
           │
           ▼ (onGenericMotionEvent, onTouchEvent, onKeyDown, onKeyUp)
Java / Kotlin JNI Bridge (NativeBridge)
           │
           ▼ (nativeInjectPointer, nativeInjectTouch, nativeInjectKey)
C++ InputBridge (Thread-Safe Event Queue)
           │
 ┌─────────┴─────────┐
 │                   ▼
 │       High-Frequency Event Coalescing (Motion/Touch)
 │                   │
 │                   ▼
 │       eventfd Notification (wake_fd_)
 │                   │
 ▼                   ▼
Compositor Event Loop (libweston Thread)
           │
           ▼
InputTranslator (Android Keycode → Linux evdev KEY_*)
           │
           ▼
libweston Seat ('default')
 ├── wl_pointer (motion, buttons, axis scroll)
 ├── wl_touch   (down, motion, up, frame)
 └── wl_keyboard (keymap, keycode, state)
           │
           ▼
Wayland Client Applications (Desktop Shell, Terminal, GUI Apps)
```

---

## 2. Event Types & Translation Mechanics

### 2.1 Pointer & Mouse Input
* **Source:** Captured from physical mice, trackpads, Bluetooth pointers, and stylus devices via Android `MotionEvent`.
* **Button Mapping:** Supports bitmask decoding for all standard mouse buttons:
  - `BUTTON_PRIMARY` → `BTN_LEFT` (0x110)
  - `BUTTON_SECONDARY` → `BTN_RIGHT` (0x111)
  - `BUTTON_TERTIARY` → `BTN_MIDDLE` (0x112)
  - `BUTTON_BACK` → `BTN_SIDE` (0x113)
  - `BUTTON_FORWARD` → `BTN_EXTRA` (0x114)
* **Coordinate Bounds Clamping:** All pointer coordinates are strictly clamped against the active output resolution:
  $$x_{\text{clamped}} = \max(0, \min(x, \text{width} - 1))$$
  $$y_{\text{clamped}} = \max(0, \min(y, \text{height} - 1))$$
* **Scrolling:** Vertical and horizontal axis scrolling (`AXIS_VSCROLL`, `AXIS_HSCROLL`) are translated into discrete `wl_pointer_send_axis` events.

### 2.2 Multi-Touch Input
* **Source:** Direct touchscreen interactions from `MotionEvent` (`ACTION_DOWN`, `ACTION_POINTER_DOWN`, `ACTION_MOVE`, `ACTION_POINTER_UP`, `ACTION_UP`).
* **Slot Tracking:** Android pointer IDs are mapped to discrete Wayland touch slots.
* **Frame Synchronization:** Emits `wl_touch_send_frame` after processing all active pointers in a multi-touch packet to ensure Wayland clients process gestures atomically.

### 2.3 Keyboard Input (`InputTranslator`)
* **Source:** Physical hardware keyboards, Bluetooth keyboards, and soft IME keyboards via `KeyEvent`.
* **Keycode Translation:** `InputTranslator` maps Android keycodes (`AKEYCODE_*`) to standard Linux `linux/input-event-codes.h` keysyms (`KEY_*`).
* **Offset Compensation:** Adjusts for the standard Linux/X11 8-offset evdev scancode requirement ($scancode = evdev + 8$).
* **Modifiers:** Tracks active modifier state (`Shift`, `Ctrl`, `Alt`, `Meta/Super`, `CapsLock`) and synchronizes state with the libweston seat keymap.

---

## 3. Performance & Queue Architecture

### 3.1 Bounded Thread-Safe Queue
* Input events are enqueued in `InputBridge` using a bounded FIFO queue (`MAX_QUEUE_SIZE = 512`).
* Thread synchronization between Android's UI thread and the compositor's rendering thread is managed via mutex and condition variable without blocking the UI thread.
* When events are available, an `eventfd` writes 1 byte, waking the libweston event loop non-blockingly.

### 3.2 High-Frequency Motion Event Coalescing
During rapid dragging, scrolling, or high-polling-rate gaming mice interactions, Android can produce hundreds of motion events per second. To eliminate queue bloat and latency:
* Consecutive `MOUSE_MOVE` events in the queue are coalesced in-place by updating the coordinates and timestamp of the latest event.
* Matching `TOUCH_MOVE` events with the same slot ID are coalesced similarly.
* This guarantees minimum input-to-display latency while preserving the exact order of button clicks and keyboard events.

---

## 4. Lifecycle & State Reset

* **Compositor Teardown:** When the compositor is stopped or paused, `InputBridge::getInstance().clear()` is called to drain remaining events and reset touch slot tracking.
* **Stale State Prevention:** Prevents stuck keys or orphan touch slots across Activity recreation, rotation, or session restart.

