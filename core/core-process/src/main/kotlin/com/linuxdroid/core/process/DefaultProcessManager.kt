package com.linuxdroid.core.process

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.native_bridge.NativeBridge
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete implementation of [ProcessManager].
 * Tracks managed Linux processes and coordinates termination via signals.
 */
class DefaultProcessManager : ProcessManager {

    private val log = LinuxDroidLogger(LogSubsystem.PROCESS)
    private val processMap = ConcurrentHashMap<String, ProcessHandle>()

    private val _processes = MutableStateFlow<Map<String, ProcessHandle>>(emptyMap())
    override val processes: Flow<Map<String, ProcessHandle>> = _processes.asStateFlow()

    private val _events = MutableSharedFlow<ProcessStateEvent>(extraBufferCapacity = 64)
    override val events: Flow<ProcessStateEvent> = _events.asSharedFlow()

    fun registerProcess(handle: ProcessHandle) {
        processMap[handle.handleId] = handle
        _processes.value = processMap.toMap()
        _events.tryEmit(ProcessStateEvent.Started(handle.handleId, handle.pid))
        log.info("Registered process ${handle.handleId} (PID ${handle.pid})")
    }

    fun updateProcess(handle: ProcessHandle) {
        processMap[handle.handleId] = handle
        _processes.value = processMap.toMap()
    }

    override suspend fun getProcess(handleId: String): ProcessHandle? {
        return processMap[handleId]
    }

    override suspend fun stopProcess(handleId: String, graceful: Boolean) {
        val handle = processMap[handleId] ?: return
        val pid = handle.pid
        if (pid > 0) {
            val signal = if (graceful) 15 else 9 // SIGTERM vs SIGKILL
            log.info("Sending signal $signal to process $handleId (PID $pid)")
            NativeBridge.sendSignal(pid, signal)
        }
        val updated = handle.copy(state = ProcessState.SIGNALED, exitedAt = System.currentTimeMillis())
        processMap[handleId] = updated
        _processes.value = processMap.toMap()
        _events.tryEmit(ProcessStateEvent.Signaled(handleId, if (graceful) 15 else 9))
    }

    override fun getProcessesForEnvironment(environmentId: EnvironmentId): Flow<List<ProcessHandle>> {
        return _processes.map { map ->
            map.values.filter { it.environmentId == environmentId }
        }
    }
}

