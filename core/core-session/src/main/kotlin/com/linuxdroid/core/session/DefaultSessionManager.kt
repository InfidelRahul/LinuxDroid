package com.linuxdroid.core.session

import com.linuxdroid.core.audio.AudioManager
import com.linuxdroid.core.display.DisplayManager
import com.linuxdroid.core.display.GuiHostController
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.gpu.GpuManager
import com.linuxdroid.core.input.InputManager
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.network.NetworkManager
import com.linuxdroid.core.package_mgr.ApplicationManager
import com.linuxdroid.core.runtime.RuntimeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val guiHostController: GuiHostController? = null,
    private val applicationManager: ApplicationManager? = null,
) : SessionManager {

    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val log = LinuxDroidLogger(LogSubsystem.SESSION)
    private val sessionMap = ConcurrentHashMap<SessionId, Session>()

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

            // 8. Configure Wayland socket & launch Wayland compositor
            session = session.copy(state = SessionState.STARTING_COMPOSITOR)
            sessionMap[sessionId] = session
            _sessions.value = sessionMap.toMap()

            val waylandSocket = "wayland-0"
            displayManager?.applyConfig(environment.configuration.display)
            guiHostController?.start()

            // Wire application launch requests from native desktop launcher to runtimeBackend
            guiHostController?.setAppLaunchListener { name, execPath ->
                sessionScope.launch {
                    log.info("App launch requested from native desktop launcher: app='$name' exec='$execPath'")
                    try {
                        runtimeBackend.execute(
                            environment = environment,
                            command = listOf("/bin/sh", "-c", execPath),
                            workingDirectory = "/home/user",
                            extraEnv = mapOf(
                                "WAYLAND_DISPLAY" to waylandSocket,
                                "XDG_RUNTIME_DIR" to "/tmp",
                                "DISPLAY" to ":0",
                            ),
                            sessionId = sessionId,
                        )
                    } catch (e: Exception) {
                        log.error("Failed to launch application '$name' ($execPath)", e)
                    }
                }
            }

            // Sync discovered FreeDesktop applications into native desktop launcher
            if (applicationManager != null) {
                try {
                    val apps = applicationManager.discoverApplications(environment)
                    if (apps.isNotEmpty()) {
                        guiHostController?.updateDesktopApplications(
                            names = apps.map { it.name }.toTypedArray(),
                            execs = apps.map { it.executable }.toTypedArray(),
                            categories = apps.map { it.categories.firstOrNull() ?: "Utilities" }.toTypedArray(),
                            icons = apps.map { it.iconName.ifBlank { "application" } }.toTypedArray(),
                        )
                    }
                } catch (e: Exception) {
                    log.warn("Failed to discover desktop applications for launcher: ${e.message}")
                }
            }

            val rootfsDir = storage.rootfsDir(environment.id)
            ensureGuiSessionEnvironment(rootfsDir)

            val sessionProcess = runtimeBackend.execute(
                environment = environment,
                command = listOf("/bin/sh", "/usr/local/bin/linuxdroid-session"),
                workingDirectory = "/home/user",
                extraEnv = mapOf(
                    "WAYLAND_DISPLAY" to waylandSocket,
                    "XDG_RUNTIME_DIR" to "/tmp",
                    "DISPLAY" to ":0",
                ),
                sessionId = sessionId,
            )

            // 9. Mark RUNNING
            val runningSession = session.copy(
                state = SessionState.RUNNING,
                waylandSocket = waylandSocket,
                display = if (environment.configuration.desktop.xwaylandEnabled) ":0" else null,
                compositorPid = sessionProcess.pid,
                runtimePid = sessionProcess.pid,
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

            // Teardown partial state safely
            try {
                audioManager?.stop()
                inputManager?.stop()
                guiHostController?.stop()
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

        try {
            guiHostController?.setAppLaunchListener(null)
            audioManager?.stop()
            inputManager?.stop()
            guiHostController?.stop()
        } catch (e: Exception) {
            log.warn("Error stopping audio/input/gui: ${e.message}")
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

    private fun ensureGuiSessionEnvironment(rootfsDir: File) {
        // Ensure /etc/environment exists with Wayland defaults
        val envFile = File(rootfsDir, "etc/environment")
        if (!envFile.exists()) {
            envFile.parentFile?.mkdirs()
            envFile.writeText(
                """
                WAYLAND_DISPLAY=wayland-0
                XDG_RUNTIME_DIR=/tmp
                DISPLAY=:0
                GDK_BACKEND=wayland,x11
                QT_QPA_PLATFORM=wayland;xcb
                CLUTTER_BACKEND=wayland
                SDL_VIDEODRIVER=wayland
                """.trimIndent() + "\n"
            )
        }

        // Ensure session startup script exists
        val sessionScript = File(rootfsDir, "usr/local/bin/linuxdroid-session")
        if (!sessionScript.exists()) {
            sessionScript.parentFile?.mkdirs()
            sessionScript.writeText(
                """
                #!/bin/sh
                export XDG_RUNTIME_DIR=/tmp
                export WAYLAND_DISPLAY=wayland-0
                export DISPLAY=:0
                mkdir -p /tmp
                chmod 1777 /tmp
                # LinuxDroid Wayland compositor is hosted by libweston in Android runtime.
                # Guest GUI clients connect directly to ${'$'}WAYLAND_DISPLAY.
                if command -v foot >/dev/null 2>&1; then
                    exec foot
                elif command -v weston-terminal >/dev/null 2>&1; then
                    exec weston-terminal
                elif command -v xterm >/dev/null 2>&1; then
                    exec xterm
                else
                    exec /bin/sh -c "while true; do sleep 3600; done"
                fi
                """.trimIndent() + "\n"
            )
            sessionScript.setExecutable(true, false)
        }
    }
}
