# Real Android Display Presentation

How the Linux compositor's rendered output actually reaches the Android
`Surface`. This is the phase that closes the gap left by
[graphical-session.md](graphical-session.md), where Weston came up and served a
Wayland socket but its pixels were never presented.

## 1. Why a capture path

The obvious design — a Weston backend that renders straight into an
`ANativeWindow` — does not exist. Weston's backends are `drm`, `wayland`,
`x11`, `rdp`, `vnc`, `pipewire` and `headless`; there is no Android one, and
writing a new libweston backend would mean maintaining an out-of-tree patch
against a fast-moving internal API.

Weston's supported mechanism for reading an output's pixels is the
`weston_output_capture_v1` protocol. It captures into a `wl_shm` buffer, i.e.
CPU-accessible memory, which is exactly what is needed to hand bytes to
`ANativeWindow_lock`. So the compositor runs headless and a small capture
client pulls each frame out.

The cost is one CPU copy per frame. That is accepted for now: it is the
smallest maintainable option, and it keeps Weston stock. A zero-copy path
(dmabuf/AHardwareBuffer) is a future optimisation, not a prerequisite for
getting pixels on screen.

## 2. Pipeline

```
Linux application
      │ wayland
      ▼
Weston (headless backend, pixman renderer)
      │ weston_output_capture_v1  → wl_shm buffer
      ▼
linuxdroid-capture            (C client, inside the rootfs)
      │ mmap'd frame file in $XDG_RUNTIME_DIR
      ▼
SharedMemoryFrameSource       :core:core-gui   (platform-independent)
      │ CompositorFrame
      ▼
FramePump                     :core:core-gui   (platform-independent)
      │ FrameSink.present()
      ▼
AndroidFrameSink              :core:core-display
      │ HostGraphics.presentFrame()
      ▼
NativeBridge (JNI)            :native:bridge
      │ DisplayBridge::presentFrame()
      ▼
ANativeWindow_lock → convert → ANativeWindow_unlockAndPost
      ▼
Android Surface  (CompositorOutputSurface, a SurfaceView)
```

The renderer is **pixman**, not GL. The capture path needs the finished frame
in CPU memory; pixman composites straight into a shm buffer, whereas the GL
renderer would add a `glReadPixels` round-trip per frame.

## 3. The Android boundary

`:core:core-gui` stays free of Android. The frame contracts
(`FrameSource`, `FrameSink`, `CompositorFrame`, `FrameDescriptor`,
`FramePixelFormat`, `SurfaceLifecycle`) name no Android type; `Surface` and
`ANativeWindow` appear only in `:core:core-display`, `:core:core-host`,
`:native:bridge` and `:app`. `PresentationBoundaryTest` enforces this by
scanning the module's imports and signatures.

## 4. Pixel format

Format is the easiest thing to get silently wrong, so it is explicit.

DRM fourcc names describe **little-endian words**, while Android's
`WINDOW_FORMAT_RGBA_8888` describes **memory order**. They disagree:

| DRM fourcc | Bytes in memory | `FramePixelFormat` | Android conversion |
|---|---|---|---|
| `XRGB8888` | B,G,R,X | `BGRX_8888` | swap R/B, force alpha `0xFF` |
| `ARGB8888` | B,G,R,A | `BGRA_8888` | swap R/B, keep alpha |
| `XBGR8888` | R,G,B,X | `RGBX_8888` | copy, force alpha `0xFF` |
| `ABGR8888` | R,G,B,A | `RGBA_8888` | straight copy |

Treating `ARGB8888` as if it were Android's `RGBA_8888` swaps red and blue —
an easy bug that looks "nearly right" on screen. Any other fourcc is rejected
with `UNSUPPORTED_FORMAT` rather than guessed at.

## 5. Stride

Stride is carried explicitly end to end and is **never** inferred as
`width × 4`:

- Weston's capture buffer may pad rows.
- `ANativeWindow_Buffer.stride` is in **pixels**, and routinely exceeds the
  window width — the native layer multiplies by 4 to get bytes.

`FrameDescriptor` rejects a stride smaller than one row, and the native copy
walks source and destination rows independently.

## 6. Frame buffer protocol

`linuxdroid-capture` and `SharedMemoryFrameSource` share a memory-mapped file
in the session's Wayland runtime directory
(`compositor-output.fb`). 32-byte little-endian header, then pixels:

| Offset | Field | Meaning |
|---|---|---|
| 0 | `magic` | `'LDFB'`; a foreign file is rejected |
| 4 | `version` | protocol version |
| 8 | `sequence` | bumped after each completed frame |
| 12 | `width` | pixels |
| 16 | `height` | pixels |
| 20 | `stride` | **bytes** per row |
| 24 | `format` | DRM fourcc |
| 28 | `status` | 0 = writing, 1 = ready |

**Tearing.** The writer sets `status = writing`, writes pixels, then bumps
`sequence` and sets `status = ready`, with release fences between. The reader
only accepts `ready` frames with a new sequence, and re-checks the sequence
after copying — if it moved, the frame was overwritten mid-copy and is dropped
rather than displayed torn.

## 7. Surface lifecycle

A non-null surface is not treated as usable. `SurfaceLifecycle` is the single
authority, shared by `CompositorOutputSurface` (which reports Android
callbacks) and `AndroidDisplayTransport` (which presents):

```
NONE → CREATED → ATTACHED → ACTIVE → DETACHING → DESTROYED → CREATED → …
```

Only `ACTIVE` permits presentation, and a surface reaches it only once the
sink has successfully configured the output for a real frame. Illegal
transitions are refused and logged rather than forced.

`generation` increments on every new surface, so a frame acquired against an
older surface is discarded instead of posted into a replaced window.

**The compositor session is independent of the surface.** Destroying and
recreating the Android surface — rotation, backgrounding — stops presentation
and resumes it. It never tears down the Linux environment, the rootfs or the
compositor.

### Resize

`onGeometryChanged` drops an `ACTIVE` surface back to `ATTACHED`, so no frame
is presented against stale geometry until the sink is reconfigured. The pump
clears its cached descriptor and tells the capture helper to remap. Dimensions
always come from the actual surface; no resolution is hardcoded.

## 8. Synchronization

`FramePump` holds a mutex across the whole acquire → present → release cycle,
and reconfiguration and shutdown take the same lock. A frame therefore cannot
be posted while the sink is being reconfigured or released. The surface state
is re-checked *inside* the acquire callback, closing the window between "is
the surface alive?" and the frame actually arriving.

If the frame is larger than the window mid-resize, the native layer clips to
the overlap rather than overrunning the buffer.

## 9. Failures

Every failure is explicit; none is converted into a success.

| Condition | `PresentationFailureKind` |
|---|---|
| No surface provided | `SURFACE_UNAVAILABLE` |
| Surface destroyed, possibly mid-frame | `SURFACE_DESTROYED` |
| Zero/negative dimensions | `INVALID_GEOMETRY` |
| Unmappable fourcc, or stride < one row | `UNSUPPORTED_FORMAT` |
| `ANativeWindow` geometry/lock failure | `BUFFER_ALLOCATION_FAILED` |
| `unlockAndPost` failure | `FRAME_SUBMISSION_FAILED` |
| Missing/truncated/corrupt frame buffer | `COMPOSITOR_OUTPUT_UNAVAILABLE` |
| JNI threw, or bridge not loaded | `NATIVE_BRIDGE_FAILURE` |

A "no surface yet" condition is a `Skipped`, not a failure — it is normal
during startup and rotation. The pump stops after 30 consecutive real
failures rather than spinning forever.

If `linuxdroid-capture` is absent from the rootfs, compositor startup logs a
warning and continues: the Wayland session is genuinely usable without
presentation, and the display layer reports the missing frames with a far more
specific diagnostic than "compositor failed".

## 10. Logging

Reuses `GuiLog`; presentation events go to `GuiLogCategory.GRAPHICS`.

Logged at INFO: surface created/attached/activated/destroyed, geometry
changes, backend selection, output configuration (dimensions, stride, format),
capture helper start/stop, frames presented at detach.

**Frame-level logging is off by default.** `FramePump(traceFrames = true)`
enables per-frame lines; at 60fps they would otherwise flood the log.
Failures are logged at ERROR.

## 11. Testing

Executed (see [`tools/offline-verify`](../../tools/offline-verify/)):

- **149** `:core:core-gui` tests — formats, stride, descriptors, surface
  lifecycle, frame pump, shared-memory protocol, boundary contract, plus the
  pre-existing bring-up suite.
- **10** `:core:core-display` tests — `AndroidFrameSink` against a faked
  `HostGraphics`.
- **12** native C++ checks — the real `DisplayBridge::presentFrame` on real
  pixel data: R/B swap, alpha forcing, padded source *and* destination strides,
  clipping, and every rejection path.
- **7** C→Kotlin interop checks — a frame written by C, read by the production
  Kotlin reader.
- **End-to-end**: C-written frame → Kotlin reader → native present → verified
  output pixels.

Not executed: `:core:core-session` and `:app` (need MockK and the Android SDK).

## 12. Not verified

**No real Android device or emulator was available, so no pixel has been
confirmed on a physical screen.** Unverified boundaries:

1. `linuxdroid-capture` against a real Weston — it compiles only against
   stub headers here; no Wayland or Weston is installed in the build sandbox.
   Whether the rootfs's Weston exposes `weston_capture_v1` at all is unproven.
2. Real `ANativeWindow` behaviour — the native code was tested against a fake
   window. Actual formats, strides and locking on a device are untested.
3. End-to-end latency and frame rate.
4. `linuxdroid-capture` is **not yet built or installed** by the bootstrapper,
   so on a real device the warning path is what would currently execute.

## 13. Known limitations

- One CPU copy per frame (capture → file → JNI → window). Acceptable for
  first light; a dmabuf path would remove it.
- Polled at a fixed interval rather than driven by frame callbacks.
- `AndroidDisplayTransport` is wired with a null GUI log in `AppModule`
  because the log is per-environment while the transport is a singleton.
- No damage tracking: every frame is a full copy.
