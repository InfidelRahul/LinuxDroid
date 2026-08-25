package com.linuxdroid.core.process

import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.ProcessStateEvent
import kotlinx.coroutines.flow.Flow

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
