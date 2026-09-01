package com.linuxdroid.core.session

import com.linuxdroid.core.gui.GraphicsCapabilities
import com.linuxdroid.core.gui.GuiRuntime
import com.linuxdroid.core.gui.GuiRuntimeConfig
import com.linuxdroid.core.gui.GuiRuntimeStatus
import com.linuxdroid.core.gui.WaylandSessionInfo
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Builds a [WaylandSessionInfo] without touching the filesystem. */
fun fakeWaylandSession(
    environmentId: EnvironmentId,
    sessionId: SessionId,
    socketName: String = "wayland-0",
    hostRuntimeDir: String = "/tmp/linuxdroid-test/runtime-state/wayland",
): WaylandSessionInfo = WaylandSessionInfo(
    environmentId = environmentId,
    sessionId = sessionId,
    runtimeDir = "/run/linuxdroid",
    hostRuntimeDir = hostRuntimeDir,
    socketName = socketName,
    socketPath = "/run/linuxdroid/$socketName",
    hostSocketPath = "$hostRuntimeDir/$socketName",
    logDir = "/run/linuxdroid/logs",
    environment = mapOf(
        "XDG_RUNTIME_DIR" to "/run/linuxdroid",
        "WAYLAND_DISPLAY" to socketName,
    ),
)

/**
 * Fake [GuiRuntime] used to test session orchestration without a compositor.
 */
class FakeGuiRuntime(
    private val readyStatus: GuiRuntimeStatus = GuiRuntimeStatus(),
    private val initError: Throwable? = null,
    private val startError: Throwable? = null,
) : GuiRuntime {

    private val state = MutableStateFlow(GuiRuntimeStatus())

    var initialized: Boolean = false
        private set
    var started: Boolean = false
        private set
    var shutdownCount: Int = 0
        private set

    override val status: GuiRuntimeStatus get() = state.value
    override val statusUpdates: Flow<GuiRuntimeStatus> = state

    override suspend fun initialize(
        environment: Environment,
        config: GuiRuntimeConfig,
    ): GuiRuntimeStatus {
        initError?.let { throw it }
        initialized = true
        return state.value
    }

    override suspend fun start(): GuiRuntimeStatus {
        startError?.let { throw it }
        started = true
        state.value = readyStatus
        return readyStatus
    }

    override suspend fun shutdown() {
        shutdownCount++
        state.value = GuiRuntimeStatus()
    }

    override suspend fun restart(): GuiRuntimeStatus {
        shutdown()
        return start()
    }

    override fun capabilities(): GraphicsCapabilities = GraphicsCapabilities.UNPROBED
}
