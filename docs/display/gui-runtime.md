# LinuxDroid GUI Runtime — Architecture and Phase Log

LinuxDroid does **not** boot, replace, patch or emulate a kernel. The Android
kernel remains the real kernel. The GUI runtime is a Linux userspace graphical
runtime running rootlessly inside Android userspace via PRoot.

## 1. Layering

```
Android
  └── LinuxDroid Android application (:app)
        └── LinuxDroid Runtime
              ├── Environment Manager   (:core:core-database, :linux:bootstrap)
              ├── Process Manager       (:core:core-process)
              ├── Filesystem Layer      (:core:core-filesystem)
              ├── Compatibility Layer   (:core:core-runtime — PRoot)
              ├── Device/Capability     (:core:core-host, :core:core-gpu, …)
              └── GUI Runtime           (:core:core-gui)   ← this document
                    └── Linux rootfs → Linux session → Compositor → Wayland
                          ├── Wayland apps
                          ├── Desktop shell   (later phase)
                          └── XWayland        (optional, later phase)
```

## 2. Existing architecture the GUI runtime plugs into

| Concern | Component | Notes |
| --- | --- | --- |
| Runtime execution | `RuntimeBackend` / `RuntimeManager` (`:core:core-runtime`) | PRoot-based, rootless. `RuntimeSpec` fully describes an execution. |
| Process handles | `ProcessHandle`, `ProcessState` (`:core:core-model`) | Reused for compositor processes in Phase 3. |
| Sessions | `SessionManager`, `DesktopSession` (`:core:core-session`) | `SessionState` already has `STARTING_COMPOSITOR`. |
| Storage layout | `EnvironmentStorage` (`:core:core-filesystem`) | `logsDir/tmpDir/runtimeStateDir` are reused; GUI logs go under `logs/gui/`. |
| Logging | `LinuxDroidLogger` + `LogSubsystem` (`:core:core-logging`) | Extended with `GUI`, `GRAPHICS`, `COMPOSITOR`. |
| Host boundary | `HostGraphics`, `HostInput`, `HostGpu` (`:core:core-host`) | Android-specific code stays behind these. |
| Errors | `LinuxDroidError` hierarchy (`:core:core-model`) | Extended with `GuiError`. |

Nothing above was rewritten. Phase 1 only adds a new module plus three additive
edits (`LogSubsystem` entries, `GuiError`, `settings.gradle.kts`).

## 3. Phase 1 — GUI Runtime interfaces (implemented)

New module `:core:core-gui`. Contracts only; **no compositor, no shell, no
XWayland, no Android graphics changes**.

| File | Contract |
| --- | --- |
| `GuiRuntimeState.kt` | `GuiState` (DISABLED → INITIALIZING → STARTING → READY → RUNNING → STOPPING → STOPPED / ERROR) with validated transitions, `GuiFailureKind`, `GuiFailure`, `GuiRuntimeStatus`. |
| `GraphicsCapabilities.kt` | `GraphicsCapability`, `ProbeOutcome` (incl. `NOT_PROBED`), `CapabilityProbeResult`, `GraphicsCapabilities`, `GraphicsCapabilityProbe`. |
| `Compositor.kt` | `CompositorId`, `CompositorBackend`, `BackendSelection`, `CompositorBackendSelector`, `CompositorStatus`, `CompositorLaunchRequest`, `Compositor`, `CompositorReadinessProbe`, `CompositorFactory`. |
| `WaylandSession.kt` | `WaylandSessionInfo` (XDG_RUNTIME_DIR, socket name/paths, env) and `WaylandSessionProvisioner`. |
| `Transport.kt` | `DisplayGeometry`, `DisplayTransport`, `GuiInputKind`, `InputTransport` — Android-free boundary types. |
| `GuiLog.kt` | `GuiLogCategory` (gui/graphics/input/wayland/compositor/session) and `FileGuiLog`, writing `logs/gui/<category>.log` separately from `console.log` / `proot.log`. |
| `GuiRuntime.kt` | `GuiRuntimeConfig`, `GuiRuntime`, `CompositorRegistry`, `DefaultCompositorRegistry`. |
| `DesktopConfiguration.kt` | Configuration boundary: `ShellMode`, `DockSettings`, `LauncherSettings`, `WindowSettings`, `AppearanceSettings`, `DesktopSettings.from(DesktopConfig)`, `DesktopSettingsProvider`. |
| `GuiSession.kt` | Session lifecycle: `GuiSessionStage`, `GuiSessionStatus`, `GuiSessionController`, plus `ShellState`/`ShellStatus`/`DesktopShell`. |

### Configuration boundary

`DesktopConfig` (`:core:core-model`, persisted per environment by
`:core:core-database`) stays the single source of persisted desktop
configuration; no new configuration framework was added. `DesktopSettings.from`
is the only translation point into GUI-runtime settings, so compositor names,
shell ids and socket defaults are not hard-coded anywhere else. Configuration is
kept distinct from runtime state (`GuiRuntimeStatus` / `GuiSessionStatus`), which
is never written back into configuration.

`DesktopConfig` defaults were corrected to match the approved architecture:
`waylandCompositor` `cage` → `weston`, `desktopEnvironment` `xfce4` →
`linuxdroid-shell`, and `xwaylandEnabled` `true` → `false` (XWayland is
optional and never a prerequisite). These fields had no production readers other
than the two `display = if (xwaylandEnabled) ":0"` lines in the session layer,
so the change is safe; persisted values are unaffected because the mapper stores
explicit JSON.

### Session lifecycle ownership

`GuiSessionController` owns the ordering
(environment → services → compositor → shell → READY, reversed on shutdown).
`DesktopShell` is a Wayland client only: it never starts the Linux environment
and never manages processes.

### Design rules encoded in the contracts

- **Weston is replaceable.** No file in `:core:core-gui` names Weston except the
  `CompositorId.WESTON` constant; the runtime resolves compositors through
  `CompositorRegistry`/`CompositorFactory`.
- **Readiness is observed, not assumed.** `Compositor.start` is documented to
  return only after `CompositorReadinessProbe.awaitReady` confirms the Wayland
  socket is live. `GuiState.STARTING` cannot transition directly to `RUNNING`.
- **No capability is assumed.** `GraphicsCapabilities.UNPROBED` reports every
  capability as `NOT_PROBED`, and an unknown capability defaults to
  `NOT_PROBED`, never `AVAILABLE`. Backend selection takes the probed
  capabilities as its only input and may return `null` (→
  `GuiFailureKind.NO_VIABLE_BACKEND`).
- **No root / DRM assumptions.** `CompositorBackend` offers only
  `ANDROID_SURFACE`, `SOFTWARE`, `HEADLESS`, `NESTED_WAYLAND`; DRM/KMS is not an
  option in the enum.
- **Failures are never hidden.** `GuiLog.failure` maps to error level, and
  `GuiRuntimeStatus.failure` accompanies `GuiState.ERROR`.
- **No Android types cross into Linux-facing contracts.** `:core:core-gui` has
  no `android.*` imports; Android implementations arrive later behind
  `DisplayTransport` / `InputTransport` and the existing `Host*` interfaces.

### Not in scope for Phase 1

No `GuiRuntime`, `GuiSessionController` or `DesktopShell` implementation, no DI
wiring (there is nothing concrete to inject yet), no changes to
`DefaultSessionManager` / `DesktopSession`, no compositor, no display/input
backend, no dock, launcher or window management. Those arrive from Phase 2
(display backend) onward.

### Verification

- Unit tests in `core/core-gui/src/test/kotlin/com/linuxdroid/core/gui/`:
  `GuiRuntimeContractTest.kt` (state machine, capability defaults, failure
  formatting, compositor registry, GUI log file separation, session validation)
  and `DesktopConfigurationTest.kt` (settings resolution and fallbacks, shell
  mode parsing, XWayland opt-in, session stage ordering, and that
  `stage == READY` alone never reports the desktop as ready).

### Phase 1 acceptance criteria

| Criterion | Status |
| --- | --- |
| GUI module exists and is part of the build | ✅ `:core:core-gui` in `settings.gradle.kts` |
| GUI logging is separated by subsystem | ✅ `GuiLogCategory` + `FileGuiLog` → `logs/gui/*.log` |
| Configuration boundary exists, nothing hard-coded elsewhere | ✅ `DesktopSettings.from` is the sole translation point |
| Runtime interfaces defined | ✅ `GuiRuntime`, `Compositor`, `DisplayTransport`, `InputTransport`, `GraphicsCapabilityProbe` |
| Session lifecycle interfaces defined | ✅ `GuiSessionController`, `GuiSessionStage`, `DesktopShell` |
| No desktop UI, compositor, or Android graphics change | ✅ contracts only; zero `android.*` imports in the module |
- Note: the current development sandbox has no JDK/Android SDK and no network
  access, so `./gradlew :core:core-gui:test` must be run on a machine with the
  Android toolchain. No existing module behaviour was changed.

## 4. Runtime compatibility (explicitly out of GUI scope)

The observed `SIGSYS_TRAPPED` / `SECCOMP_EMULATED` / `getcwd() failed` issues
are PRoot/compatibility-layer defects tracked separately in
`docs/runtime/proot.md`. The GUI runtime does not work around them and does not
require a fake kernel; Phase 3 will add only the minimum compatibility work that
the compositor itself demands.

## 5. Phase roadmap

1. ✅ GUI foundation — module, build integration, logging, configuration
   boundary, runtime interfaces, session lifecycle interfaces
2. ⬜ Display backend (create and maintain a graphical display surface)
3. ⬜ Wayland compositor foundation (a client connects and displays a window)
4. ⬜ Basic window management (create, map, focus, stack, move, resize, close)
5. ⬜ Shell foundation (desktop background, shell process, shell↔compositor link)
6. ⬜ Dock
7. ⬜ Application discovery (`.desktop`)
8. ⬜ Application launcher
9. ⬜ Window state controls (minimize/restore/maximize/fullscreen)
10. ⬜ Session lifecycle integration and clean shutdown
11. ⬜ Stabilization

Out of scope throughout: workspaces, tiling, multi-monitor, GNOME/KDE/Mutter/
GDM/LightDM, notification center, settings app, screenshot/recording.
