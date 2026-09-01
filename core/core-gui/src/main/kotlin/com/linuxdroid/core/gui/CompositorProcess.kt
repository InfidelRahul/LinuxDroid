package com.linuxdroid.core.gui

/**
 * A handle to a compositor process that was started through the existing
 * LinuxDroid runtime/process infrastructure.
 *
 * This is a *view* over the already-tracked process; it deliberately carries no
 * process-management state of its own so that no second process registry is
 * introduced.
 */
interface CompositorProcess {
    /** Handle id in the existing process registry. */
    val handleId: String

    /** Host PID, or -1 when unknown. */
    val pid: Int

    /** True while the compositor process is still running. */
    fun isAlive(): Boolean

    /** Exit code once terminated, or null while running/unknown. */
    fun exitCode(): Int?

    /** Requests termination and waits up to [timeoutMs] for the process to exit. */
    suspend fun terminate(timeoutMs: Long = 5_000): Boolean
}

/**
 * Starts a compositor inside the Linux userspace.
 *
 * Implemented by the session layer on top of the existing
 * `RuntimeManager`/`RuntimeBackend`, so the GUI module stays independent of the
 * runtime implementation and remains unit-testable with a fake launcher.
 */
interface CompositorProcessLauncher {
    /**
     * Executes [command] inside the Linux environment.
     *
     * @param env environment variables for the process (already complete; the
     * launcher must not inject Android host environment values).
     * @param bindings host→guest directories that must be visible to the
     * compositor, e.g. the Wayland runtime directory.
     * @throws com.linuxdroid.core.model.GuiError if the process cannot be started.
     */
    suspend fun launch(
        command: List<String>,
        env: Map<String, String>,
        workingDirectory: String,
        bindings: List<GuestBinding>,
        logFilePath: String?,
    ): CompositorProcess

    /**
     * Checks whether an executable is present inside the Linux rootfs.
     * Used to fail early with "compositor executable missing" diagnostics.
     */
    suspend fun hasExecutable(name: String): Boolean

    /** Absolute host path of the rootfs, used to resolve guest paths. */
    fun rootfsPath(): String
}

/** A host→guest directory binding required by the graphical session. */
data class GuestBinding(
    val hostPath: String,
    val guestPath: String,
    val readOnly: Boolean = false,
)
