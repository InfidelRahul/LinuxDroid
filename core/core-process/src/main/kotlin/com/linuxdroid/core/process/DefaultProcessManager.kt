package com.linuxdroid.core.process

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.native_bridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
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
        val isNew = !processMap.containsKey(handle.handleId)
        processMap[handle.handleId] = handle
        _processes.value = processMap.toMap()
        if (isNew) {
            _events.tryEmit(ProcessStateEvent.Started(handle.handleId, handle.pid))
            log.info("Registered process ${handle.handleId} (PID ${handle.pid})")
        }
    }

    fun updateProcess(handle: ProcessHandle) {
        processMap[handle.handleId] = handle
        _processes.value = processMap.toMap()
    }

    override suspend fun getProcess(handleId: String): ProcessHandle? {
        return processMap[handleId]
    }

    override suspend fun stopProcess(handleId: String, graceful: Boolean) = withContext(Dispatchers.IO) {
        val handle = processMap[handleId] ?: return@withContext
        val pid = handle.pid
        if (pid > 0) {
            val signal = if (graceful) 15 else 9 // SIGTERM vs SIGKILL
            log.info("Sending signal $signal to process $handleId (PID $pid)")
            NativeBridge.sendSignal(pid, signal)

            if (graceful) {
                // Give up to 1000ms for graceful shutdown before escalating
                var stillAlive = true
                for (step in 0 until 10) {
                    delay(100)
                    if (NativeBridge.sendSignal(pid, 0) != 0) {
                        stillAlive = false
                        break
                    }
                }
                if (stillAlive) {
                    log.warn("Process $handleId (PID $pid) did not terminate on SIGTERM; escalating to SIGKILL")
                    NativeBridge.sendSignal(pid, 9)
                    delay(50)
                }
            }
            // Non-blocking reap attempt
            NativeBridge.waitpid(pid, false)
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

