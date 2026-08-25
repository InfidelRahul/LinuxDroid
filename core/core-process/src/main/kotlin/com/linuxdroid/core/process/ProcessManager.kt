package com.linuxdroid.core.process

import com.linuxdroid.core.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Event emitted when a managed process changes state.
 * Defined here in core-process to avoid circular dependency with core-runtime.
 */
sealed class ProcessStateEvent {
    abstract val handleId: String
    data class Started(override val handleId: String, val pid: Int) : ProcessStateEvent()
    data class Exited(override val handleId: String, val exitCode: Int) : ProcessStateEvent()
    data class Signaled(override val handleId: String, val signal: Int) : ProcessStateEvent()
    data class Failed(override val handleId: String, val error: String) : ProcessStateEvent()
}

/**
 * ProcessManager tracks and manages all Linux processes spawned by LinuxDroid.
 *
 * All process state changes are event-driven (no polling loops).
 *
 * Implementation: Phase 9 of the development roadmap.
 */
interface ProcessManager {
    /** All managed processes. */
    val processes: Flow<Map<String, ProcessHandle>>

    /** Process state change events. */
    val events: Flow<ProcessStateEvent>

    /** Returns a process handle by ID. */
    suspend fun getProcess(handleId: String): ProcessHandle?

    /** Stops a process by handle ID. */
    suspend fun stopProcess(handleId: String, graceful: Boolean = true)

    /** Returns all processes for a given environment. */
    fun getProcessesForEnvironment(environmentId: EnvironmentId): Flow<List<ProcessHandle>>
}
