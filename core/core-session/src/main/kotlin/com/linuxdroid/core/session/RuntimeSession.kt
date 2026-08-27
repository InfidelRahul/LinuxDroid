package com.linuxdroid.core.session

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controller representing an active runtime execution instance for an environment.
 */
class RuntimeSession(
    val sessionId: SessionId,
    val environment: Environment,
    private val runtimeManager: RuntimeManager,
) {
    private val log = LinuxDroidLogger(LogSubsystem.SESSION, environment.id, sessionId)

    suspend fun start(spec: RuntimeSpec): ProcessResult = withContext(Dispatchers.IO) {
        log.info("Starting RuntimeSession for ${environment.id}")
        runtimeManager.prepare(spec)
        runtimeManager.executeAndWait(spec, timeoutMs = 10_000)
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        log.info("Stopping RuntimeSession for ${environment.id}")
        runtimeManager.stop(environment.id)
    }

    suspend fun execute(command: List<String>, workingDirectory: String = "/"): ProcessHandle {
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = command,
            workingDirectory = workingDirectory,
        )
        return runtimeManager.execute(spec, sessionId)
    }
}
