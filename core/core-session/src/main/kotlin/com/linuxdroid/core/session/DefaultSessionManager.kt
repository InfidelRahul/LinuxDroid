package com.linuxdroid.core.session

import com.linuxdroid.core.audio.AudioManager
import com.linuxdroid.core.display.DisplayManager
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.gpu.GpuManager
import com.linuxdroid.core.input.InputManager
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.network.NetworkManager
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete implementation of [SessionManager].
 * Coordinates multi-subsystem startup (Runtime, GPU, Display/Wayland, Audio, Input, Network)
 * and graceful session termination.
 */
class DefaultSessionManager(
    private val runtimeBackend: RuntimeBackend,
    private val storage: EnvironmentStorage,
    private val displayManager: DisplayManager? = null,
    private val gpuManager: GpuManager? = null,
    private val inputManager: InputManager? = null,
    private val audioManager: AudioManager? = null,
    private val networkManager: NetworkManager? = null,
    private val runtimeManager: RuntimeManager? = null,
    private val guiRuntimeFactory: GuiRuntimeFactory? = null,
) : SessionManager {

    private val log = LinuxDroidLogger(LogSubsystem.SESSION)
    private val sessionMap = ConcurrentHashMap<SessionId, Session>()
    private val desktopSessions = ConcurrentHashMap<SessionId, DesktopSession>()

    private val _sessions = MutableStateFlow<Map<SessionId, Session>>(emptyMap())
    override val sessions: Flow<Map<SessionId, Session>> = _sessions.asStateFlow()

    override suspend fun startSession(environment: Environment): Session = withContext(Dispatchers.IO) {
        val sessionId = SessionId.generate()
        log.info("Initiating 14-step session startup sequence: Session=$sessionId for ${environment.id}")

        // 1. Validate Environment & Rootfs
        if (!storage.verifyRootfs(environment.id)) {
            throw FilesystemError(
                path = storage.rootfsDir(environment.id).path,
                message = "Cannot start session: Rootfs is missing or incomplete",
            )
        }

        var session = Session(
            id = sessionId,
            environmentId = environment.id,
            state = SessionState.INITIALIZING,
            startedAt = System.currentTimeMillis(),
        )
        sessionMap[sessionId] = session
        _sessions.value = sessionMap.toMap()

        try {
            // 2. Initialize and start Runtime
            session = session.copy(state = SessionState.STARTING_RUNTIME)
            sessionMap[sessionId] = session
            _sessions.value = sessionMap.toMap()

            runtimeBackend.prepare(environment)
            runtimeBackend.initialize(environment)
            runtimeBackend.start(environment)

            // 3. Verify shell & uname execute inside Linux environment
            val shellResult = runtimeBackend.executeAndWait(
                environment = environment,
                command = listOf("/bin/sh", "-c", "uname -a && echo 'SHELL_ACTIVE'"),
                timeoutMs = 10_000
            )
            if (shellResult.exitCode != 0 || !shellResult.stdout.contains("SHELL_ACTIVE")) {
                throw RuntimeError(
                    environmentId = environment.id,
                    message = "Linux /bin/sh (uname -a) verification failed (exit=${shellResult.exitCode}): ${shellResult.stderr.ifBlank { shellResult.stdout }}",
                )
            }
            log.info("Linux /bin/sh (uname -a) verified successfully: ${shellResult.stdout.trim()}")

            // 4. Initialize GPU
            gpuManager?.detect()

            // 5. Initialize Audio
            audioManager?.start(
                sampleRate = if (environment.configuration.audio.latencyHintMs > 0) 48000 else 44100,
                channels = 2
            )

            // 6. Initialize Input
            inputManager?.start()

            // 7. Initialize Network
            networkManager?.applyConfig(environment.configuration.network)

            // 8. Start the graphical session (compositor bring-up + verified readiness)
            session = session.copy(state = SessionState.STARTING_COMPOSITOR)
            sessionMap[sessionId] = session
            _sessions.value = sessionMap.toMap()

            displayManager?.applyConfig(environment.configuration.display)

            val graphicalSession = startGraphicalSession(environment, sessionId)

            // 9. Mark RUNNING only after the compositor reported verified readiness
            val runningSession = session.copy(
                state = SessionState.RUNNING,
                waylandSocket = graphicalSession.waylandSocket,
                display = graphicalSession.display,
                compositorPid = graphicalSession.compositorPid,
                runtimePid = graphicalSession.runtimePid,
            )
            sessionMap[sessionId] = runningSession
            _sessions.value = sessionMap.toMap()
            log.info("Session $sessionId is now fully active (RUNNING)")
            runningSession
        } catch (e: Exception) {
            log.error("Session startup failure for $sessionId", e)
            val failedSession = session.copy(
                state = SessionState.FAILED,
                failureMessage = e.message ?: "Failed to start session",
                stoppedAt = System.currentTimeMillis(),
            )
            sessionMap[sessionId] = failedSession
            _sessions.value = sessionMap.toMap()

            // Teardown partial state safely (GUI first, then host subsystems).
            try {
                desktopSessions.remove(sessionId)?.stop()
                audioManager?.stop()
                inputManager?.stop()
                runtimeBackend.stop(environment)
            } catch (cleanupEx: Exception) {
                log.warn("Secondary error during cleanup: ${cleanupEx.message}")
            }
            throw e
        }
    }

    override suspend fun stopSession(sessionId: SessionId) = withContext(Dispatchers.IO) {
        val session = sessionMap[sessionId] ?: return@withContext
        log.info("Stopping session $sessionId")

        val stoppingSession = session.copy(state = SessionState.STOPPING)
        sessionMap[sessionId] = stoppingSession
        _sessions.value = sessionMap.toMap()

        // Ordered shutdown: graphical session (compositor + Wayland state) first,
        // then the host subsystems that were started for it.
        try {
            desktopSessions.remove(sessionId)?.stop()
        } catch (e: Exception) {
            log.warn("Error stopping graphical session: ${e.message}")
        }

        try {
            audioManager?.stop()
            inputManager?.stop()
        } catch (e: Exception) {
            log.warn("Error stopping audio/input: ${e.message}")
        }

        val stoppedSession = stoppingSession.copy(
            state = SessionState.STOPPED,
            stoppedAt = System.currentTimeMillis(),
        )
        sessionMap[sessionId] = stoppedSession
        _sessions.value = sessionMap.toMap()
        log.info("Session $sessionId cleanly STOPPED")
    }

    override suspend fun getSession(environmentId: EnvironmentId): Session? {
        return sessionMap.values.firstOrNull {
            it.environmentId == environmentId && it.state.isActive()
        }
    }

    /**
     * Starts the graphical session through [DesktopSession], which drives the
     * GUI runtime (Wayland provisioning, compositor startup, readiness
     * verification). Returns without a compositor when no GUI factory is wired,
     * so headless/CLI sessions remain supported.
     */
    private suspend fun startGraphicalSession(
        environment: Environment,
        sessionId: SessionId,
    ): Session {
        val factory = guiRuntimeFactory
        if (factory == null) {
            log.info("No GUI runtime configured; session $sessionId stays non-graphical")
            return Session(
                id = sessionId,
                environmentId = environment.id,
                state = SessionState.RUNNING,
            )
        }
        val desktopSession = DesktopSession(
            sessionId = sessionId,
            environment = environment,
            runtimeManager = requireNotNull(runtimeManager) {
                "A RuntimeManager is required to start a graphical session"
            },
            storage = storage,
            guiRuntimeFactory = factory,
            gpuManager = gpuManager,
            inputManager = inputManager,
            audioManager = audioManager,
            networkManager = networkManager,
        )
        desktopSessions[sessionId] = desktopSession
        return desktopSession.start()
    }
}
