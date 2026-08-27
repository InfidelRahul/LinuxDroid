package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.ProcessHandle
import com.linuxdroid.core.model.ProcessResult
import com.linuxdroid.core.model.RuntimeSpec
import com.linuxdroid.core.model.SessionId
import java.io.File

/**
 * Domain-facing entry point for managing Linux runtime execution instances.
 * Application code and session managers interact with RuntimeManager rather than low-level backends.
 */
interface RuntimeManager {

    /**
     * Prepares and validates the runtime engine according to [spec].
     */
    suspend fun prepare(spec: RuntimeSpec)

    /**
     * Executes a process inside the Linux environment as defined by [spec].
     */
    suspend fun execute(
        spec: RuntimeSpec,
        sessionId: SessionId? = null,
    ): ProcessHandle

    /**
     * Executes a command and awaits completion, collecting stdout and stderr.
     */
    suspend fun executeAndWait(
        spec: RuntimeSpec,
        timeoutMs: Long = 30_000,
    ): ProcessResult

    /**
     * Spawns an interactive shell inside a pseudo-terminal (PTY).
     */
    suspend fun startInteractiveShell(
        spec: RuntimeSpec,
        rows: Int = 24,
        cols: Int = 80,
    ): PtySession

    /**
     * Stops all running processes belonging to [environmentId].
     */
    suspend fun stop(environmentId: EnvironmentId)

    /**
     * Returns the exact deterministic command line string that would be executed for [spec].
     */
    fun showRuntimeCommand(spec: RuntimeSpec): String
}

/**
 * Default implementation of [RuntimeManager] coordinating validation, command construction, and backend execution.
 */
class DefaultRuntimeManager(
    private val backend: ProotRuntimeBackend,
    private val validator: RuntimeValidator = RuntimeValidator(),
    private val commandBuilder: RuntimeCommandBuilder = ProotCommandBuilder(),
) : RuntimeManager {

    override suspend fun prepare(spec: RuntimeSpec) {
        validator.validate(spec)
        backend.ensureProotBinary()
    }

    override suspend fun execute(spec: RuntimeSpec, sessionId: SessionId?): ProcessHandle {
        validator.validate(spec)
        return backend.executeWithSpec(spec, sessionId)
    }

    override suspend fun executeAndWait(spec: RuntimeSpec, timeoutMs: Long): ProcessResult {
        validator.validate(spec)
        return backend.executeAndWaitWithSpec(spec, timeoutMs)
    }

    override suspend fun startInteractiveShell(spec: RuntimeSpec, rows: Int, cols: Int): PtySession {
        validator.validate(spec)
        return backend.startInteractiveShellWithSpec(spec, rows, cols)
    }

    override suspend fun stop(environmentId: EnvironmentId) {
        backend.stopForEnvironment(environmentId)
    }

    override fun showRuntimeCommand(spec: RuntimeSpec): String {
        val prootBinPath = spec.customProotPath ?: backend.getProotBinaryPath()
        val cmdList = commandBuilder.build(spec, File(prootBinPath))
        return cmdList.joinToString(" ")
    }
}
