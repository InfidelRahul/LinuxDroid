package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.EnvironmentMetadata
import com.linuxdroid.core.model.GuiError
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end bring-up of the graphical session with everything wired except the
 * two boundaries that need a device: the compositor process itself and the real
 * UNIX socket connect.
 *
 * The chain exercised here is:
 * ```
 * capability probe -> backend selection -> wayland runtime dir
 *   -> compositor launch -> socket appearance -> readiness probe -> READY
 * ```
 * A fake compositor process creates the socket file exactly as Weston would, so
 * the provisioning, launch-argument, readiness and cleanup logic is covered
 * without an Android device.
 */
class GraphicalSessionBringUpTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var storage: EnvironmentStorage
    private val log = RecordingGuiLog()
    private val envId = EnvironmentId("debian")
    private val sessionId = SessionId("session-1")
    private val geometry = DisplayGeometry(1080, 2400, 420, 60f)

    private lateinit var environment: Environment

    @Before
    fun setUp() {
        storage = EnvironmentStorage(temp.newFolder("environments"))
        environment = Environment(
            metadata = EnvironmentMetadata(
                id = envId,
                name = "Debian",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            rootfsPath = storage.rootfsDir(envId).absolutePath,
            metadataPath = storage.metadataDir(envId).absolutePath,
        )
    }

    /** A launcher whose "compositor" creates the socket, like Weston does. */
    private class SocketCreatingLauncher(
        private val createSocket: Boolean = true,
    ) : CompositorProcessLauncher {
        val process = FakeCompositorProcess()
        val captureProcess = FakeCompositorProcess(pid = 4343, handleId = "capture-handle")
        var lastEnv: Map<String, String> = emptyMap()
            private set
        var lastCommand: List<String> = emptyList()
            private set
        val commands = mutableListOf<List<String>>()

        /** The compositor's own launch, excluding the frame capture helper. */
        val compositorCommand: List<String>?
            get() = commands.firstOrNull { it.firstOrNull() != WestonCompositor.CAPTURE_EXECUTABLE }

        val captureLaunched: Boolean
            get() = commands.any { it.firstOrNull() == WestonCompositor.CAPTURE_EXECUTABLE }

        override suspend fun launch(
            command: List<String>,
            env: Map<String, String>,
            workingDirectory: String,
            bindings: List<GuestBinding>,
            logFilePath: String?,
        ): CompositorProcess {
            commands += command
            if (command.firstOrNull() == WestonCompositor.CAPTURE_EXECUTABLE) {
                return captureProcess
            }
            lastCommand = command
            lastEnv = env
            if (createSocket) {
                // Weston binds $XDG_RUNTIME_DIR/$WAYLAND_DISPLAY; the host
                // binding gives us the host-side path.
                val hostRuntimeDir = bindings.first { it.guestPath == env["XDG_RUNTIME_DIR"] }.hostPath
                File(hostRuntimeDir, env.getValue("WAYLAND_DISPLAY")).writeText("")
            }
            return process
        }

        override suspend fun hasExecutable(name: String): Boolean = true
        override fun rootfsPath(): String = "/rootfs"
    }

    private fun buildRuntime(
        launcher: CompositorProcessLauncher,
        capabilities: GraphicsCapabilities = Capabilities.accelerated,
        connectable: Boolean = true,
        transport: FakeDisplayTransport = FakeDisplayTransport(),
    ): DefaultGuiRuntime = DefaultGuiRuntime(
        capabilityProbe = FixedCapabilityProbe(capabilities),
        backendSelector = DefaultCompositorBackendSelector(),
        sessionProvisioner = DefaultWaylandSessionProvisioner(storage, guiLogFactory = { log }),
        compositorRegistry = DefaultCompositorRegistry(
            listOf(
                WestonCompositorFactory {
                    WestonCompositor(
                        launcher = launcher,
                        readinessProbe = WaylandReadinessProbe({ connectable }, pollIntervalMs = 1),
                        log = log,
                        geometryProvider = { geometry },
                    )
                },
            ),
        ),
        displayTransport = transport,
        guiLogFactory = { log },
        geometryProvider = { geometry },
        sessionIdProvider = { sessionId },
    )

    @Test
    fun `full bring-up reaches RUNNING with a real socket on disk`() = runTest {
        val launcher = SocketCreatingLauncher()
        val gui = buildRuntime(launcher)

        gui.initialize(environment, GuiRuntimeConfig())
        val status = gui.start()

        assertThat(status.state).isEqualTo(GuiState.RUNNING)
        assertThat(status.isUsable).isTrue()

        val session = status.session!!
        // C: per-session Wayland runtime directory under the existing storage layout.
        assertThat(File(session.hostRuntimeDir).isDirectory).isTrue()
        assertThat(File(session.hostRuntimeDir).parentFile).isEqualTo(storage.runtimeStateDir(envId))
        // D: a real socket file exists.
        assertThat(File(session.hostSocketPath).exists()).isTrue()
        // Compositor was told the real socket name and runtime dir, not /tmp.
        assertThat(launcher.compositorCommand).contains("--socket=${session.socketName}")
        assertThat(launcher.lastEnv["XDG_RUNTIME_DIR"]).isEqualTo("/run/linuxdroid")
    }

    @Test
    fun `logs the ordered lifecycle events`() = runTest {
        val gui = buildRuntime(SocketCreatingLauncher())

        gui.initialize(environment, GuiRuntimeConfig())
        gui.start()

        val messages = log.messages()
        val expected = listOf(
            "display initialization started",
            "graphics capabilities probed",
            "display backend selected",
            "wayland runtime directory provisioned",
            "compositor starting",
            "compositor process started",
            "wayland socket detected",
            "wayland readiness verified",
            "gui session READY",
        )
        val indices = expected.map { needle ->
            messages.indexOfFirst { it.contains(needle) }.also {
                assertThat(it).isNotEqualTo(-1)
            }
        }
        assertThat(indices).isInOrder()
    }

    @Test
    fun `socket never appearing fails the session and cleans up`() = runTest {
        val launcher = SocketCreatingLauncher(createSocket = false)
        val transport = FakeDisplayTransport()
        val gui = buildRuntime(launcher, transport = transport)

        gui.initialize(environment, GuiRuntimeConfig(readinessTimeoutMs = 30))
        val error = runCatching { gui.start() }.exceptionOrNull()

        assertThat(error).isInstanceOf(GuiError::class.java)
        assertThat(error).hasMessageThat().contains("COMPOSITOR_READINESS_TIMEOUT")
        assertThat(gui.status.state).isEqualTo(GuiState.ERROR)
        // H: cleanup after a failed session.
        assertThat(launcher.process.terminateCount).isAtLeast(1)
        assertThat(transport.detachCount).isAtLeast(1)
    }

    @Test
    fun `socket present but unusable never reports ready`() = runTest {
        val gui = buildRuntime(SocketCreatingLauncher(), connectable = false)

        gui.initialize(environment, GuiRuntimeConfig(readinessTimeoutMs = 30))
        val error = runCatching { gui.start() }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("SOCKET_CONNECTABLE")
        assertThat(gui.status.state).isEqualTo(GuiState.ERROR)
    }

    @Test
    fun `shutdown removes the socket and stops the compositor`() = runTest {
        val launcher = SocketCreatingLauncher()
        val gui = buildRuntime(launcher)
        gui.initialize(environment, GuiRuntimeConfig())
        val session = gui.start().session!!
        assertThat(File(session.hostSocketPath).exists()).isTrue()

        gui.shutdown()

        assertThat(gui.status.state).isEqualTo(GuiState.STOPPED)
        assertThat(launcher.process.terminateCount).isEqualTo(1)
        // H: Wayland runtime state cleaned; the directory itself is retained.
        assertThat(File(session.hostSocketPath).exists()).isFalse()
        assertThat(File(session.hostRuntimeDir).isDirectory).isTrue()
    }

    @Test
    fun `no viable backend prevents any compositor launch`() = runTest {
        val launcher = SocketCreatingLauncher()
        val gui = buildRuntime(launcher, capabilities = Capabilities.none)

        val error = runCatching {
            gui.initialize(environment, GuiRuntimeConfig())
        }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("NO_VIABLE_BACKEND")
        assertThat(launcher.lastCommand).isEmpty()
    }

    @Test
    fun `software-only capabilities still bring the session up`() = runTest {
        val gui = buildRuntime(SocketCreatingLauncher(), capabilities = Capabilities.softwareOnly)

        gui.initialize(environment, GuiRuntimeConfig())
        val status = gui.start()

        assertThat(status.state).isEqualTo(GuiState.RUNNING)
        assertThat(status.compositor?.backend).isEqualTo(CompositorBackend.SOFTWARE)
    }

    @Test
    fun `no desktop shell or xwayland is started during compositor bring-up`() = runTest {
        val launcher = SocketCreatingLauncher()
        val gui = buildRuntime(launcher)

        gui.initialize(environment, GuiRuntimeConfig())
        gui.start()

        val command = launcher.lastCommand.joinToString(" ")
        assertThat(command).doesNotContain("xwayland")
        assertThat(command).doesNotContain("shell")
        assertThat(launcher.lastEnv).doesNotContainKey("DISPLAY")
    }
}
