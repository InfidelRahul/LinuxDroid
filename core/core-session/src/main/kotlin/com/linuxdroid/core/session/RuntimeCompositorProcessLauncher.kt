package com.linuxdroid.core.session

import com.linuxdroid.core.gui.CompositorProcess
import com.linuxdroid.core.gui.CompositorProcessLauncher
import com.linuxdroid.core.gui.GuestBinding
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.GuiError
import com.linuxdroid.core.model.ProcessHandle
import com.linuxdroid.core.model.ProcessState
import com.linuxdroid.core.model.RuntimeBinding
import com.linuxdroid.core.model.RuntimeSpec
import com.linuxdroid.core.model.SessionId
import com.linuxdroid.core.process.DefaultProcessManager
import com.linuxdroid.core.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs the Wayland compositor through the existing LinuxDroid runtime.
 *
 * No new process-execution mechanism is introduced: the compositor is launched
 * via [RuntimeManager] exactly like every other Linux process, and the
 * resulting handle is registered with the existing [DefaultProcessManager]
 * registry so the compositor is observable alongside all other processes.
 */
class RuntimeCompositorProcessLauncher(
    private val environment: Environment,
    private val sessionId: SessionId,
    private val runtimeManager: RuntimeManager,
    private val processManager: DefaultProcessManager?,
    private val rootfsDir: File,
) : CompositorProcessLauncher {

    override fun rootfsPath(): String = rootfsDir.absolutePath

    override suspend fun hasExecutable(name: String): Boolean = withContext(Dispatchers.IO) {
        if (name.startsWith("/")) {
            return@withContext File(rootfsDir, name.removePrefix("/")).canExecute()
        }
        GUEST_BIN_DIRS.any { dir -> File(rootfsDir, "$dir/$name").let { it.exists() && it.canExecute() } }
    }

    override suspend fun launch(
        command: List<String>,
        env: Map<String, String>,
        workingDirectory: String,
        bindings: List<GuestBinding>,
        logFilePath: String?,
    ): CompositorProcess {
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = command,
            workingDirectory = workingDirectory,
            extraEnv = env,
            extraBindings = bindings.map {
                RuntimeBinding(hostPath = it.hostPath, guestPath = it.guestPath, readOnly = it.readOnly)
            },
            logFilePath = logFilePath,
        )

        val handle = try {
            runtimeManager.execute(spec, sessionId)
        } catch (e: Exception) {
            throw GuiError(
                "compositor launch failed: executable=${command.firstOrNull()} reason=${e.message}",
                e,
            )
        }

        val compositorHandle = handle.copy(processRole = PROCESS_ROLE)
        processManager?.registerProcess(compositorHandle)

        return RuntimeCompositorProcess(
            handle = compositorHandle,
            runtimeManager = runtimeManager,
            processManager = processManager,
            environment = environment,
        )
    }

    companion object {
        const val PROCESS_ROLE = "compositor"
        private val GUEST_BIN_DIRS = listOf("usr/bin", "bin", "usr/local/bin", "usr/sbin", "sbin")
    }
}

/**
 * [CompositorProcess] view over a handle owned by the existing runtime and
 * process registry. It holds no independent process state.
 */
internal class RuntimeCompositorProcess(
    private var handle: ProcessHandle,
    private val runtimeManager: RuntimeManager,
    private val processManager: DefaultProcessManager?,
    private val environment: Environment,
) : CompositorProcess {

    override val handleId: String get() = handle.handleId
    override val pid: Int get() = handle.pid

    @Volatile
    private var terminated = false

    override fun isAlive(): Boolean {
        if (terminated) return false
        val pid = handle.pid
        if (pid <= 0) return false
        // /proc is the authoritative liveness source for a PRoot-launched process.
        return File("/proc/$pid").exists()
    }

    override fun exitCode(): Int? = handle.exitCode

    override suspend fun terminate(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (terminated || !isAlive()) {
            terminated = true
            return@withContext true
        }
        // Graceful SIGTERM through the existing process registry.
        processManager?.stopProcess(handle.handleId, graceful = true)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) {
                terminated = true
                markExited()
                return@withContext true
            }
            delay(POLL_INTERVAL_MS)
        }

        // Escalate, then let the runtime reap anything left for this environment.
        processManager?.stopProcess(handle.handleId, graceful = false)
        runCatching { runtimeManager.stop(environment.id) }

        val hardDeadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < hardDeadline) {
            if (!isAlive()) {
                terminated = true
                markExited()
                return@withContext true
            }
            delay(POLL_INTERVAL_MS)
        }
        false
    }

    private fun markExited() {
        handle = handle.copy(state = ProcessState.EXITED, exitedAt = System.currentTimeMillis())
        processManager?.updateProcess(handle)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
