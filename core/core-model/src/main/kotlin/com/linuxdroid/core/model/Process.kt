package com.linuxdroid.core.model

/**
 * State of a managed Linux process.
 */
enum class ProcessState {
    /** Process is being prepared. */
    PENDING,
    /** Process is running. */
    RUNNING,
    /** Process exited normally. */
    EXITED,
    /** Process was killed by a signal. */
    SIGNALED,
    /** Process state is unknown (e.g. after crash). */
    UNKNOWN;

    fun isTerminal(): Boolean = this in setOf(EXITED, SIGNALED, UNKNOWN)
}

/**
 * A handle to a managed Linux process.
 */
data class ProcessHandle(
    /** Unique handle ID within LinuxDroid. */
    val handleId: String,
    /** The environment this process belongs to. */
    val environmentId: EnvironmentId,
    /** The session this process belongs to (null if not part of a session). */
    val sessionId: SessionId? = null,
    /** The command and arguments. */
    val command: List<String>,
    /** Working directory inside Linux. */
    val workingDirectory: String = "/",
    /** OS host process ID. -1 if not yet started. */
    val pid: Int = -1,
    /** Guest virtual PID if tracked. */
    val guestPid: Int? = null,
    /** Logical role of the process (e.g. "terminal", "desktop", "daemon", "exec"). */
    val processRole: String = "exec",
    /** Current state. */
    val state: ProcessState = ProcessState.PENDING,
    /** When the process was started (epoch ms). */
    val startedAt: Long = System.currentTimeMillis(),
    /** When the process exited (epoch ms). */
    val exitedAt: Long? = null,
    /** Exit code (valid only when state == EXITED). */
    val exitCode: Int? = null,
    /** Signal number (valid only when state == SIGNALED). */
    val signal: Int? = null,
    /** Reason for process termination if known. */
    val terminationReason: String? = null,
) {
    init {
        require(command.isNotEmpty()) { "Command must not be empty" }
    }

    val executable: String get() = command.first()
}

/**
 * Result of executing a command that has completed.
 */
data class ProcessResult(
    val handleId: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
