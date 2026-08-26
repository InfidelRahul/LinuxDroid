# LinuxDroid — Android Architecture

## 1. Overview
The Android layer is built with Modern Android Development (MAD) practices:
- **Language:** Kotlin 2.0.21 targeting JVM 17
- **UI Toolkit:** Jetpack Compose with Material 3 & dynamic theming
- **Dependency Injection:** Hilt (Dagger)
- **Database:** Room with SQLite metadata persistence
- **Concurrency:** Kotlin Coroutines & Structured Concurrency (`StateFlow`, `SharedFlow`, `Dispatchers.IO`)
- **Background Execution:** `LinuxSessionService` foreground service with persistent status notification

## 2. Navigation Flow
```
MainActivity
    └── LinuxDroidApp
            ├── HomeScreen (System health & active session chip)
            ├── EnvironmentListScreen (Create dialog, live bootstrap, start/stop/shell)
            ├── TerminalScreen (Interactive proot shell execution)
            ├── DiagnosticsScreen (Live multi-subsystem check report)
            ├── SettingsScreen (Shared storage authorization & permissions)
            └── AboutScreen (Version info & system specifications)
```

## 3. Storage Separation
- **Database (`LinuxDroidDatabase`):** Stores *environment metadata* ("What is this environment?").
- **Filesystem (`EnvironmentStorage`):** Stores *Linux rootfs* ("What is inside this environment?").
The database never serializes the Linux filesystem.

