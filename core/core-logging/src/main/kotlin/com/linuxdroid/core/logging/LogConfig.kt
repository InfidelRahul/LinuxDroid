package com.linuxdroid.core.logging

/**
 * Global logging configuration for LinuxDroid.
 *
 * Controls whether detailed runtime, process, terminal, and session logs
 * are captured and written to disk.
 */
object LogConfig {
    /**
     * Master switch for log generation. Defaults to true.
     * When set to false, categorized file writing, terminal recording,
     * verbose engine tracing, and debug logging are disabled to eliminate I/O overhead.
     */
    @JvmField
    @Volatile
    var generate_log: Boolean = true

    /**
     * Functional alias / getter / setter for idiomatic Kotlin usage.
     */
    var isLoggingEnabled: Boolean
        get() = generate_log
        set(value) {
            generate_log = value
        }

    /**
     * Verbosity level for PRoot engine tracing when generate_log is true.
     * Level 0 = errors only, 1 = syscall & bindings, 9 = maximum trace.
     */
    @Volatile
    var prootVerboseLevel: Int = 0

    /**
     * Maximum size of an individual log file in bytes before rolling/rotation (5 MB default).
     */
    const val MAX_LOG_FILE_BYTES: Long = 5 * 1024 * 1024

    /**
     * Resets logging configuration to default values.
     */
    fun resetToDefaults() {
        generate_log = true
        prootVerboseLevel = 0
    }
}
