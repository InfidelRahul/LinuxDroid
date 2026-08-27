package com.linuxdroid.core.session

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.RuntimeSpec
import com.linuxdroid.core.model.SessionId
import com.linuxdroid.core.runtime.PtySession
import com.linuxdroid.core.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controller managing an interactive terminal / PTY session inside a Linux environment.
 */
class TerminalSession(
    val sessionId: SessionId,
    val environment: Environment,
    private val runtimeManager: RuntimeManager,
) {
    private val log = LinuxDroidLogger(LogSubsystem.SESSION, environment.id, sessionId)

    @Volatile
    var ptySession: PtySession? = null
        private set

    suspend fun open(
        rows: Int = 24,
        cols: Int = 80,
        command: List<String> = listOf("/bin/sh"),
    ): PtySession = withContext(Dispatchers.IO) {
        log.info("Opening interactive terminal session (${cols}x$rows)")
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = command,
            workingDirectory = environment.configuration.homeDir.ifBlank { "/root" },
        )
        val pty = runtimeManager.startInteractiveShell(spec, rows, cols)
        ptySession = pty
        pty
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        log.info("Closing terminal session")
        ptySession?.close()
        ptySession = null
        runtimeManager.stop(environment.id)
    }
}
