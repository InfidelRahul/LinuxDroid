package com.linuxdroid.native_bridge

/**
 * Kotlin entry point for the LinuxDroid native bridge.
 *
 * This is the ONLY place in LinuxDroid where JNI calls are made.
 * No other Kotlin class may call System.loadLibrary or declare
 * native methods directly.
 *
 * All native operations pass through this class, which provides:
 * - Type-safe wrappers around raw JNI functions
 * - Error handling and logging
 * - A stable API surface (native layer can be refactored without
 *   changing callers)
 */
object NativeBridge {

    init {
        System.loadLibrary("linuxdroid_bridge")
    }

    /**
     * Returns the native bridge version number.
     * Used to verify JNI connectivity during startup.
     */
    fun getBridgeVersion(): Int = nativeGetBridgeVersion()

    /**
     * Returns true if [path] exists and is executable.
     */
    fun isExecutable(path: String): Boolean = nativeIsExecutable(path)

    /**
     * Sets [path] as executable (chmod +x).
     * @return 0 on success, errno value on failure.
     */
    fun setExecutable(path: String): Int = nativeSetExecutable(path)

    /**
     * Returns the current device ABI (e.g. "arm64-v8a").
     * Detected from the compiled native binary, not from Android Java APIs.
     */
    fun getAbi(): String = nativeGetAbi()

    /**
     * Sends a POSIX signal to a process by PID.
     * @return 0 on success, errno value on failure.
     */
    fun sendSignal(pid: Int, signal: Int): Int = nativeSendSignal(pid, signal)

    /**
     * Returns available system memory in bytes from /proc/meminfo.
     * Returns -1 if unavailable.
     */
    fun getAvailableMemoryBytes(): Long = nativeGetAvailableMemoryBytes()

    // ─── JNI declarations ─────────────────────────────────────────────────────────
    // These are the ONLY native function declarations in the project.

    @JvmStatic private external fun nativeGetBridgeVersion(): Int
    @JvmStatic private external fun nativeIsExecutable(path: String): Boolean
    @JvmStatic private external fun nativeSetExecutable(path: String): Int
    @JvmStatic private external fun nativeGetAbi(): String
    @JvmStatic private external fun nativeSendSignal(pid: Int, signal: Int): Int
    @JvmStatic private external fun nativeGetAvailableMemoryBytes(): Long
}
