package com.linuxdroid.core.session

import com.linuxdroid.core.audio.AudioManager
import com.linuxdroid.core.display.AndroidDisplayTransport
import com.linuxdroid.core.display.AndroidGraphicsCapabilityProbe
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.gpu.GpuManager
import com.linuxdroid.core.gui.*
import com.linuxdroid.core.input.InputManager
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.network.NetworkManager
import com.linuxdroid.core.process.DefaultProcessManager
import com.linuxdroid.core.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controller for a graphical (Wayland) session.
 *
 * The compositor is started by the [GuiRuntime] through the existing LinuxDroid
 * runtime; this controller only orders the steps and reports the resulting
 * [Session]. The previous hard-coded `linuxdroid-session` shell script — with
 * its fixed `XDG_RUNTIME_DIR=/tmp`, `wayland-0` and `exec weston` — has been
 * removed: the session environment is now generated per session by
 * [DefaultWaylandSessionProvisioner], and readiness is verified rather than
 * assumed.
 *
 * The desktop shell is deliberately not started here; this session ends at a
 * verified Wayland compositor.
 */
class DesktopSession(
    val sessionId: SessionId,
    val environment: Environment,
    private val runtimeManager: RuntimeManager,
    private val storage: EnvironmentStorage,
    private val guiRuntimeFactory: GuiRuntimeFactory,
    private val gpuManager: GpuManager? = null,
    private val inputManager: InputManager? = null,
    private val audioManager: AudioManager? = null,
    private val networkManager: NetworkManager? = null,
) {
    private val log = LinuxDroidLogger(LogSubsystem.SESSION, environment.id, sessionId)
    private val guiLog: GuiLog = FileGuiLog(environment.id, storage)

    @Volatile
    private var guiRuntime: GuiRuntime? = null

    suspend fun start(): Session = withContext(Dispatchers.IO) {
        guiLog.info(GuiLogCategory.SESSION, "gui session start requested: environment=${environment.id}")

        // Host subsystems required before the compositor can present/receive input.
        gpuManager?.detect()
        audioManager?.start(
            sampleRate = if (environment.configuration.audio.latencyHintMs > 0) 48000 else 44100,
            channels = 2,
        )
        inputManager?.start()
        networkManager?.applyConfig(environment.configuration.network)

        val settings = DesktopSettings.from(environment.configuration.desktop)
        val runtime = guiRuntimeFactory.create(environment, sessionId, guiLog)
        guiRuntime = runtime

        val config = GuiRuntimeConfig(
            enabled = true,
            compositorId = settings.compositorId,
            readinessTimeoutMs = READINESS_TIMEOUT_MS,
        )

        try {
            runtime.initialize(environment, config)
            val status = runtime.start()
            val session = status.session
                ?: throw GuiError("GUI runtime reported READY without a Wayland session")

            log.info("Graphical session ready: socket=${session.socketName} state=${status.state}")
            guiLog.info(GuiLogCategory.SESSION, "gui session READY: socket=${session.socketName}")

            Session(
                id = sessionId,
                environmentId = environment.id,
                state = SessionState.RUNNING,
                waylandSocket = session.socketName,
                display = null, // XWayland is optional and not started in this phase.
                compositorPid = status.compositor?.pid ?: -1,
                runtimePid = status.compositor?.pid ?: -1,
            )
        } catch (e: Exception) {
            guiLog.error(GuiLogCategory.SESSION, "gui session startup failed", e)
            runCatching { runtime.shutdown() }
            stopHostSubsystems()
            throw e
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        guiLog.info(GuiLogCategory.SESSION, "gui session stop requested")
        try {
            guiRuntime?.shutdown()
        } catch (e: Exception) {
            log.warn("Error during GUI runtime shutdown", e)
            guiLog.error(GuiLogCategory.SESSION, "gui runtime shutdown failed", e)
        }
        guiRuntime = null
        stopHostSubsystems()
        runCatching { runtimeManager.stop(environment.id) }
            .onFailure { log.warn("Error stopping Linux runtime", it) }
        guiLog.info(GuiLogCategory.SESSION, "gui session STOPPED")
    }

    private suspend fun stopHostSubsystems() {
        runCatching { audioManager?.stop() }.onFailure { log.warn("Error stopping audio", it) }
        runCatching { inputManager?.stop() }.onFailure { log.warn("Error stopping input", it) }
    }

    private companion object {
        const val READINESS_TIMEOUT_MS = 20_000L
    }
}

/**
 * Builds a fully-wired [GuiRuntime] for one environment/session.
 *
 * Kept as an interface so [DesktopSession] can be unit-tested with a fake GUI
 * runtime, and so the Android wiring stays in one place.
 */
fun interface GuiRuntimeFactory {
    fun create(environment: Environment, sessionId: SessionId, guiLog: GuiLog): GuiRuntime
}

/**
 * Default wiring of the graphical stack: Android display boundary + probed
 * capabilities + per-environment Wayland session + Weston, all executing
 * through the existing runtime.
 */
class DefaultGuiRuntimeFactory(
    private val storage: EnvironmentStorage,
    private val runtimeManager: RuntimeManager,
    private val processManager: DefaultProcessManager?,
    private val displayTransport: AndroidDisplayTransport,
    private val capabilityProbe: AndroidGraphicsCapabilityProbe,
    private val connectivityChecker: SocketConnectivityChecker = UnixSocketConnectivityChecker(),
) : GuiRuntimeFactory {

    override fun create(
        environment: Environment,
        sessionId: SessionId,
        guiLog: GuiLog,
    ): GuiRuntime {
        val launcher = RuntimeCompositorProcessLauncher(
            environment = environment,
            sessionId = sessionId,
            runtimeManager = runtimeManager,
            processManager = processManager,
            rootfsDir = storage.rootfsDir(environment.id),
        )
        val readinessProbe = WaylandReadinessProbe(connectivityChecker)
        val geometryProvider = { displayTransport.currentOutputGeometry() }

        return DefaultGuiRuntime(
            capabilityProbe = capabilityProbe,
            backendSelector = DefaultCompositorBackendSelector(),
            sessionProvisioner = DefaultWaylandSessionProvisioner(
                storage = storage,
                guiLogFactory = { guiLog },
            ),
            compositorRegistry = DefaultCompositorRegistry(
                listOf(
                    WestonCompositorFactory {
                        WestonCompositor(
                            launcher = launcher,
                            readinessProbe = readinessProbe,
                            log = guiLog,
                            geometryProvider = geometryProvider,
                        )
                    },
                ),
            ),
            displayTransport = displayTransport,
            guiLogFactory = { guiLog },
            geometryProvider = geometryProvider,
            sessionIdProvider = { sessionId },
        )
    }
}
