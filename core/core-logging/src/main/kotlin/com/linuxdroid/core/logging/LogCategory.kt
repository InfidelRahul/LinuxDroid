package com.linuxdroid.core.logging

/**
 * Categories for structured log routing into distinct file streams.
 */
enum class LogCategory(val filename: String, val displayName: String) {
    /**
     * Complete starting session log: 10 background startup steps, state transitions, teardown.
     */
    SESSION("session.log", "Starting Session Log"),

    /**
     * Host Preboot stage: rootfs verification, binary integrity, isolated environment assembly.
     */
    PREBOOT("preboot.log", "Preboot Log"),

    /**
     * Guest Init (/sbin/linuxdroid-init) execution, directory setup, /etc/linuxdroid/init.d hooks.
     */
    GUEST_INIT("guest_init.log", "Guest Init Log"),

    /**
     * System process execution: background/foreground commands, PIDs, exit codes, signals, stdout/stderr.
     */
    SYSTEM_PROCESS("process.log", "System Process Log"),

    /**
     * Interactive terminal shell session output stream (e.g. `apt install nano` full installation output).
     */
    TERMINAL("terminal.log", "Terminal Log"),

    /**
     * PRoot internal engine traces, syscall translations, and mount mappings.
     */
    PROOT("proot.log", "PRoot Engine Log"),

    /**
     * Unified console stdout and stderr for runtime processes.
     */
    CONSOLE("console.log", "Console Log"),

    /**
     * Diagnostics, health check evaluations, and failure detector events.
     */
    DIAGNOSTICS("diagnostics.log", "Diagnostics Log"),

    /**
     * General application and subsystem events.
     */
    GENERAL("system.log", "General System Log");

    companion object {
        fun fromSubsystem(subsystem: LogSubsystem): LogCategory = when (subsystem) {
            LogSubsystem.SESSION -> SESSION
            LogSubsystem.BOOTSTRAP -> PREBOOT
            LogSubsystem.PROCESS -> SYSTEM_PROCESS
            LogSubsystem.RUNTIME -> PROOT
            LogSubsystem.DIAGNOSTICS -> DIAGNOSTICS
            LogSubsystem.APPLICATION -> GENERAL
            else -> GENERAL
        }
    }
}
