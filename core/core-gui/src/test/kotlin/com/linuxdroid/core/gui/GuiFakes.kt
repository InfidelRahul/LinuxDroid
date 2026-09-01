package com.linuxdroid.core.gui

import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.SessionId
import java.io.File

/** In-memory [GuiLog] that records every entry for assertions. */
class RecordingGuiLog : GuiLog {
    data class Entry(val category: GuiLogCategory, val level: String, val message: String)

    val entries = mutableListOf<Entry>()

    override fun info(category: GuiLogCategory, message: String) {
        entries += Entry(category, "I", message)
    }

    override fun warn(category: GuiLogCategory, message: String, throwable: Throwable?) {
        entries += Entry(category, "W", message)
    }

    override fun error(category: GuiLogCategory, message: String, throwable: Throwable?) {
        entries += Entry(category, "E", message)
    }

    override fun failure(category: GuiLogCategory, failure: GuiFailure) {
        error(category, failure.describe())
    }

    fun messages(category: GuiLogCategory? = null): List<String> =
        entries.filter { category == null || it.category == category }.map { it.message }

    fun hasMessageContaining(text: String): Boolean = entries.any { it.message.contains(text) }
}

/** Controllable [CompositorProcess] fake. */
class FakeCompositorProcess(
    override val pid: Int = 4242,
    override val handleId: String = "compositor-handle",
    private var alive: Boolean = true,
    private var exit: Int? = null,
    private val terminateSucceeds: Boolean = true,
) : CompositorProcess {

    var terminateCount: Int = 0
        private set

    override fun isAlive(): Boolean = alive
    override fun exitCode(): Int? = exit

    override suspend fun terminate(timeoutMs: Long): Boolean {
        terminateCount++
        if (terminateSucceeds) {
            alive = false
            exit = 0
        }
        return terminateSucceeds
    }

    fun die(exitCode: Int = 1) {
        alive = false
        exit = exitCode
    }
}

/** Records launches and can simulate launch failures / missing executables. */
class FakeCompositorProcessLauncher(
    private val process: CompositorProcess? = FakeCompositorProcess(),
    private val executableAvailable: Boolean = true,
    private val launchError: Throwable? = null,
    private val rootfs: String = "/rootfs",
) : CompositorProcessLauncher {

    var lastCommand: List<String>? = null
        private set
    var lastEnv: Map<String, String> = emptyMap()
        private set
    var lastBindings: List<GuestBinding> = emptyList()
        private set
    var launchCount: Int = 0
        private set

    override suspend fun launch(
        command: List<String>,
        env: Map<String, String>,
        workingDirectory: String,
        bindings: List<GuestBinding>,
        logFilePath: String?,
    ): CompositorProcess {
        launchCount++
        lastCommand = command
        lastEnv = env
        lastBindings = bindings
        launchError?.let { throw it }
        return process ?: error("no process configured")
    }

    override suspend fun hasExecutable(name: String): Boolean = executableAvailable

    override fun rootfsPath(): String = rootfs
}

/** Probe returning a fixed capability set. */
class FixedCapabilityProbe(
    private val capabilities: GraphicsCapabilities,
    private val error: Throwable? = null,
) : GraphicsCapabilityProbe {
    override suspend fun probe(): GraphicsCapabilities {
        error?.let { throw it }
        return capabilities
    }
}

/** Records display transport lifecycle calls. */
class FakeDisplayTransport(
    private val attachError: Throwable? = null,
) : DisplayTransport {
    var attachCount = 0
        private set
    var detachCount = 0
        private set

    override var isAttached: Boolean = false
        private set
    override var geometry: DisplayGeometry? = null
        private set

    override suspend fun attach(session: WaylandSessionInfo, geometry: DisplayGeometry) {
        attachError?.let { throw it }
        attachCount++
        isAttached = true
        this.geometry = geometry
    }

    override suspend fun onGeometryChanged(geometry: DisplayGeometry) {
        this.geometry = geometry
    }

    override suspend fun detach() {
        detachCount++
        isAttached = false
        geometry = null
    }
}

/** Provisioner returning a prepared session rooted in a real temp directory. */
class FakeWaylandSessionProvisioner(
    private val hostRuntimeDir: File,
    private val socketName: String = "wayland-0",
    private val error: Throwable? = null,
) : WaylandSessionProvisioner {

    var releaseCount = 0
        private set

    override suspend fun provision(
        environmentId: EnvironmentId,
        sessionId: SessionId,
    ): WaylandSessionInfo {
        error?.let { throw it }
        hostRuntimeDir.mkdirs()
        return WaylandSessionInfo(
            environmentId = environmentId,
            sessionId = sessionId,
            runtimeDir = "/run/linuxdroid",
            hostRuntimeDir = hostRuntimeDir.absolutePath,
            socketName = socketName,
            socketPath = "/run/linuxdroid/$socketName",
            hostSocketPath = File(hostRuntimeDir, socketName).absolutePath,
            logDir = "/run/linuxdroid/logs",
            environment = mapOf(
                "XDG_RUNTIME_DIR" to "/run/linuxdroid",
                "WAYLAND_DISPLAY" to socketName,
            ),
        )
    }

    override suspend fun release(session: WaylandSessionInfo) {
        releaseCount++
    }
}

/** Capability builder helpers for tests. */
object Capabilities {
    fun of(vararg available: GraphicsCapability): GraphicsCapabilities =
        GraphicsCapabilities(
            results = GraphicsCapability.entries.map { capability ->
                CapabilityProbeResult(
                    capability = capability,
                    outcome = if (capability in available) {
                        ProbeOutcome.AVAILABLE
                    } else {
                        ProbeOutcome.UNAVAILABLE
                    },
                    hardwareAccelerated = capability in available &&
                        capability in setOf(GraphicsCapability.OPENGL_ES, GraphicsCapability.EGL),
                )
            },
        )

    val accelerated: GraphicsCapabilities = of(
        GraphicsCapability.ANDROID_SURFACE,
        GraphicsCapability.EGL,
        GraphicsCapability.OPENGL_ES,
        GraphicsCapability.SHARED_MEMORY_BUFFER,
        GraphicsCapability.SOFTWARE_RENDERING,
    )

    val softwareOnly: GraphicsCapabilities = of(
        GraphicsCapability.ANDROID_SURFACE,
        GraphicsCapability.SHARED_MEMORY_BUFFER,
        GraphicsCapability.SOFTWARE_RENDERING,
    )

    val none: GraphicsCapabilities = of()
}
