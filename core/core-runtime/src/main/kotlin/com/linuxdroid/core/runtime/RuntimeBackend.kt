package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.ProcessHandle
import com.linuxdroid.core.model.ProcessResult
import com.linuxdroid.core.model.ProcessStateEvent
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.flow.Flow

/**
 * RuntimeBackend is the primary abstraction for the Linux runtime engine.
 *
 * All runtime operations pass through this interface.
 * The rest of LinuxDroid must NOT directly manipulate Linux processes.
 *
 * Current implementation: proot-based rootless chroot (no root required).
 *
 * Thread safety: All methods are safe to call from any coroutine context.
 * Implementations must serialize internal state changes.
 */
interface RuntimeBackend {

    /**
     * Prepares the runtime engine for the given environment.
     * Called once before [initialize]. Validates binary availability,
     * extracts bundled binaries if necessary.
     *
     * @throws RuntimeError if preparation fails.
     */
    suspend fun prepare(environment: Environment)

    /**
     * Initializes the runtime for [environment].
     * Sets up mount points, environment variables, working directory.
     * Does NOT start any Linux processes.
     *
     * @throws RuntimeError if initialization fails.
     */
    suspend fun initialize(environment: Environment)

    /**
     * Starts the Linux runtime for [environment].
     * After this returns successfully, Linux processes can be executed.
     *
     * @throws RuntimeError if startup fails.
     */
    suspend fun start(environment: Environment)

    /**
     * Stops the Linux runtime for [environment].
     * Gracefully terminates all managed processes.
     *
     * @throws RuntimeError if stop fails unrecoverably.
     */
    suspend fun stop(environment: Environment)

    /**
     * Restarts the runtime (stop + start).
     */
    suspend fun restart(environment: Environment)

    /**
     * Executes a command inside the Linux environment.
     * Returns a [ProcessHandle] that can be used to monitor the process.
     *
     * @throws RuntimeError if execution cannot be started.
     */
    suspend fun execute(
        environment: Environment,
        command: List<String>,
        workingDirectory: String = "/",
        extraEnv: Map<String, String> = emptyMap(),
        sessionId: SessionId? = null,
    ): ProcessHandle

    /**
     * Executes a command and waits for it to complete, collecting stdout/stderr.
     * Suitable for short-lived commands (e.g. package installation queries).
     *
     * @throws RuntimeError if execution fails to start.
     */
    suspend fun executeAndWait(
        environment: Environment,
        command: List<String>,
        workingDirectory: String = "/",
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000,
    ): ProcessResult

    /**
     * Inspects the current state of a running process.
     */
    suspend fun inspect(handleId: String): ProcessHandle?

    /**
     * Performs a health check on the runtime.
     * Returns true if the runtime is operational for the given environment.
     */
    suspend fun healthCheck(environment: Environment): Boolean

    /**
     * Cleans up all runtime resources for the given environment.
     * Called during shutdown or error recovery.
     * Must NOT delete the rootfs.
     */
    suspend fun cleanup(environment: Environment)

    /**
     * Spawns an interactive shell inside a pseudo-terminal (PTY).
     */
    suspend fun startInteractiveShell(
        environment: Environment,
        rows: Int = 24,
        cols: Int = 80,
        command: List<String> = listOf("/bin/sh")
    ): PtySession

    /**
     * A Flow that emits events when a managed process changes state.
     */
    val processEvents: Flow<ProcessStateEvent>
}
