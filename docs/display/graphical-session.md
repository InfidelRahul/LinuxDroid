# LinuxDroid — Graphical Session Bring-Up

Implementation of the first real Wayland graphical session: a compositor
running inside the Linux userspace, connected to the Android display surface
through the existing host boundary, exposing a verified Wayland socket.

This document covers compositor bring-up only. There is no desktop shell, no
dock, no launcher and no window management yet.

LinuxDroid does not boot, patch or emulate a kernel. The Android kernel remains
the real kernel; everything below runs rootlessly in Android userspace through
the existing PRoot runtime.

## 1. Pipeline

```
Android Surface (SurfaceView)
   ↓  CompositorOutputSurface            (:app)
HostGraphics / display_bridge            (:core:core-host, :native:bridge)
   ↓  AndroidDisplayTransport            (:core:core-display)
DisplayTransport (Android-free contract) (:core:core-gui)
   ↓
GuiRuntime → Compositor → Weston         (:core:core-gui)
   ↓  RuntimeCompositorProcessLauncher   (:core:core-session)
RuntimeManager → ProotRuntimeBackend     (:core:core-runtime)
   ↓
Wayland socket in the per-session runtime directory
   ↓
Wayland clients
```

Android framework types (`Surface`, `ANativeWindow`) terminate at
`HostGraphics`. `:core:core-gui` has no `android.*` import at all.

## 2. Display backend

`AndroidGraphicsCapabilityProbe` probes real capabilities through `HostGpu`
(`gpu_detector`) and `HostGraphics` (`display_bridge`) and records the evidence
for each one. Anything that cannot be executed is reported `NOT_PROBED`, never
assumed available. `HARDWARE_BUFFER` is currently always `NOT_PROBED` because no
buffer-import path exists yet.

`DefaultCompositorBackendSelector` picks the least-privileged viable backend:

| Probed | Selected | Renderer |
| --- | --- | --- |
| Android surface + EGL + GLES | `ANDROID_SURFACE` | `gl` |
| Android surface + shm/software | `SOFTWARE` | `pixman` |
| No output surface | *none* → `NO_VIABLE_BACKEND` failure | — |
| No output surface, headless explicitly enabled | `HEADLESS` | `pixman` |

DRM/KMS is not present in `CompositorBackend` at all — it needs privileges
LinuxDroid does not have. Root is never assumed.

`AndroidDisplayTransport` attaches the output and fails explicitly with
`DisplayError`/`GuiError` when no Surface is attached to the host boundary.

## 3. Wayland runtime directory

Provisioned by `DefaultWaylandSessionProvisioner` inside the **existing**
storage layout — no new storage mechanism:

```
<environment>/runtime-state/wayland/     host XDG_RUNTIME_DIR, mode 0700
        ↕ bound into the guest at
/run/linuxdroid                          guest XDG_RUNTIME_DIR
```

- `/tmp` is never used as the runtime directory.
- The directory is per environment and created before the compositor starts.
- The socket name is **allocated**, not assumed: `wayland-0` is used only if it
  is free, otherwise `wayland-1`…`wayland-N`. Exhausting the candidates is an
  explicit `SESSION_SETUP_FAILED`.
- `release()` deletes only the socket and lock files. The runtime directory is
  retained and the rootfs is never touched.

Generated session environment: `XDG_RUNTIME_DIR`, `WAYLAND_DISPLAY`,
`XDG_SESSION_TYPE=wayland`, plus toolkit hints (`GDK_BACKEND`, `QT_QPA_PLATFORM`,
`SDL_VIDEODRIVER`, `CLUTTER_BACKEND`, `MOZ_ENABLE_WAYLAND`). `DISPLAY` is
deliberately **not** set: XWayland is optional and out of scope here.

## 4. Compositor startup

`WestonCompositor` is the only Weston-aware code besides `WestonConfig`. It:

1. verifies an output geometry exists;
2. verifies the `weston` executable exists in the rootfs (`ENOENT` diagnostic
   otherwise) — before launching anything;
3. generates a per-session `weston.ini` into the runtime directory
   (backend module, renderer, output mode from the real display geometry,
   `panel-position=none` — no desktop environment is configured);
4. launches `weston --socket=<allocated> --config=… --log=…` through
   `CompositorProcessLauncher`;
5. waits for **observed** readiness;
6. transitions to `READY`, then `RUNNING`.

Weston is used strictly as Wayland infrastructure. It is resolved through
`CompositorRegistry`/`CompositorFactory`, so it remains replaceable.

### The removed ad-hoc script

The previous `linuxdroid-session` shell script — duplicated in both
`DefaultSessionManager` and `DesktopSession`, hard-coding `XDG_RUNTIME_DIR=/tmp`,
`WAYLAND_DISPLAY=wayland-0`, `DISPLAY=:0` and `exec weston`/`exec cage`, and
reporting `RUNNING` the moment `exec` succeeded — has been **deleted**. It was
also writing a fixed `/etc/environment` into the rootfs; that is gone too.

## 5. Readiness criteria

`WaylandReadinessProbe` verifies, in order, and polls until they all pass:

| Step | Check |
| --- | --- |
| `PROCESS_ALIVE` | compositor process is running |
| `RUNTIME_DIR_EXISTS` | the Wayland runtime directory is present |
| `SOCKET_EXISTS` | the socket file appeared |
| `SOCKET_CONNECTABLE` | a real `AF_UNIX` connect succeeds (`UnixSocketConnectivityChecker`) |
| `PROCESS_STILL_ALIVE` | the compositor survived the verification |

Only then does the state go `STARTING → READY → RUNNING`. A dead compositor
fails immediately instead of waiting out the timeout. Process creation alone is
never treated as readiness, and `GuiState` itself forbids `STARTING → RUNNING`.

## 6. Process lifecycle

The compositor runs through `RuntimeCompositorProcessLauncher`, which builds a
`RuntimeSpec` and calls the existing `RuntimeManager` — the same path as every
other Linux process. The resulting `ProcessHandle` (role `compositor`) is
registered with the existing `DefaultProcessManager`; there is no second process
registry. Liveness is read from `/proc/<pid>`; termination is SIGTERM through
the registry, escalating to SIGKILL and then `runtimeManager.stop(environmentId)`.

```
start:  probe capabilities → select backend → provision Wayland runtime
        → attach display → launch compositor → observe process
        → await socket → verify readiness → READY → RUNNING

stop:   STOPPING → stop compositor → verify termination
        → detach display → clean Wayland runtime state → STOPPED
```

`DesktopSession` orders these steps; `DefaultSessionManager` delegates to it and
tears it down in `stopSession` before stopping audio/input.

## 7. Failure handling

Every case produces a structured `GuiFailure` with a diagnostic detail line, is
logged at error level, and results in `GuiState.ERROR` — never `READY`.

| Case | Failure kind |
| --- | --- |
| No output surface / display backend unavailable | `DISPLAY_TRANSPORT_FAILED` |
| No probed capability supports any backend | `NO_VIABLE_BACKEND` |
| Runtime directory cannot be created; no free socket name | `SESSION_SETUP_FAILED` |
| `weston` missing; unregistered compositor; spawn failure | `COMPOSITOR_LAUNCH_FAILED` |
| Invalid/unwritable compositor configuration | `SESSION_SETUP_FAILED` |
| Socket never appears; socket exists but unusable | `COMPOSITOR_READINESS_TIMEOUT` |
| Compositor exits during startup/verification | `COMPOSITOR_CRASHED` |
| Compositor will not terminate | `SHUTDOWN_FAILED` |

Cleanup runs on both paths: a failed startup terminates the compositor, detaches
the display and releases the Wayland session state, so no orphan process or
stale socket is left behind.

## 8. Logging

GUI events use the existing `GuiLog` categories, written to
`<environment>/logs/gui/*.log`, separate from `console.log` and `proot.log`.
Weston's own output goes to `compositor.log` in the runtime directory.

Ordered lifecycle events (asserted by test): display initialization started →
graphics capabilities probed → display backend selected → wayland runtime
directory provisioned → compositor starting → compositor process started →
wayland socket detected → wayland readiness verified → gui session READY.
Environment variable *values* are not logged.

## 9. Testing

Unit and integration-style tests, all runnable on the JVM without a device:

- `DefaultWaylandSessionProvisionerTest` — directory creation and permissions,
  never `/tmp`, socket-name allocation, environment generation, exhaustion and
  creation failures, cleanup, release-after-crash.
- `WaylandReadinessProbeTest` — each verification step, dead compositor,
  timeout, socket appearing late, crash during verification, and that a live
  process alone is not readiness.
- `CompositorBackendSelectorTest` / `WestonConfigTest` — selection, fallback,
  unavailable backend, `null` on no capability, no DRM anywhere.
- `WestonCompositorTest` — READY only after verification, missing executable,
  launch failure, early exit, socket timeout, unusable socket, config
  generation, cleanup after failure, shutdown, idempotency.
- `DefaultGuiRuntimeTest` — full lifecycle, error states, cleanup, and shutdown
  ordering (compositor → display → wayland).
- `GraphicalSessionBringUpTest` — the chain from capability probe through a real
  socket file on disk to a verified READY session, plus its failure and shutdown
  paths.
- `SessionHierarchyTest` — `DesktopSession` reports RUNNING from the verified GUI
  runtime session and tears down on failure.

### Verification limitation

The development sandbox has **no JDK, no Android SDK, and no access to Maven
Central / Gradle distributions**, so `./gradlew test` and `assembleDebug` could
not be executed and no on-device graphical run was performed. The tests above
are written but have not been run. Two boundaries are therefore unproven on real
hardware: the actual `weston` process behaviour under PRoot, and
`UnixSocketConnectivityChecker` against a real Weston socket. Both are isolated
behind `CompositorProcessLauncher` and `SocketConnectivityChecker` and are faked
in the tests.

## 10. Known issues related to this feature

- **Weston is not yet installed by the bootstrapper.** `DefaultPackageManager`
  still lists `cage` in its GUI package set. Until `weston` is present in the
  rootfs, bring-up fails fast and correctly with
  `COMPOSITOR_LAUNCH_FAILED … reason=ENOENT`.
- **Weston backend module.** `WestonConfig` currently maps both presenting
  backends to `headless-backend.so`, because Weston has no Android-surface
  backend; the compositor renders offscreen and the pixels are not yet blitted
  to the `ANativeWindow`. Wiring that blit is display-transport work, not part
  of compositor bring-up.
- **`AndroidDisplayTransport` is wired with a null GUI log** in `AppModule`
  (`{ null }`) because the log is per environment while the transport is a
  singleton; transport events currently reach only the structured logger.
- **Unrelated, not addressed (per scope):** the existing
  `getcwd() failed: Function not implemented` apt/dpkg failure. It will block
  *installing* Weston with apt, but it is a PRoot compatibility issue and was
  explicitly out of scope for this phase.
