# LinuxDroid — Environment State Machine & Storage Layout

## 1. Directory Structure
```
<context.filesDir>/environments/<env-id>/
    ├── rootfs/          <- Linux root filesystem (STRICTLY PERSISTENT, NEVER DELETED)
    ├── metadata/        <- Environment configuration and state records
    ├── runtime-state/   <- Transient session sockets and PID locks
    └── tmp/             <- Ephemeral scratch space
```

## 2. Environment State Machine
```
CREATED ───► INSTALLING ───► READY ───► STARTING ───► RUNNING
   │              │            ▲           │             │
   ▼              ▼            │           ▼             ▼
 FAILED ◄─────── FAILED   STOPPED ◄─── STOPPING ◄─────── FAILED
   │                           ▲
   └───► RECOVERING ───────────┘
```

Transitions are strictly validated through `EnvironmentState.isValidTransitionFrom(from)`. Any illegal jump raises `IllegalStateTransitionException`.
