package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.EnvironmentMetadata
import com.linuxdroid.core.model.GuiError
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultGuiRuntimeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val log = RecordingGuiLog()
    private val geometry = DisplayGeometry(1080, 2400, 420, 60f)
    private val environment = Environment(
        metadata = EnvironmentMetadata(
            id = EnvironmentId("debian"),
            name = "Debian",
            distribution = Distribution.DEBIAN,
            architecture = Architecture.ARM64,
        ),
        rootfsPath = "/rootfs",
        metadataPath = "/metadata",
    )

    private class StubCompositor(
        private val readyStatus: CompositorStatus,
        private val startError: Throwable? = null,
        override val id: CompositorId = CompositorId.WESTON,
        private val supported: Boolean = true,
    ) : Compositor {
        var stopCount = 0
            private set
        private var current = CompositorStatus(id, GuiState.STOPPED)
        override val status: CompositorStatus get() = current
        override val statusUpdates = kotlinx.coroutines.flow.emptyFlow<CompositorStatus>()
        override suspend fun isSupported(backend: CompositorBackend) = supported
        override suspend fun start(request: CompositorLaunchRequest): CompositorStatus {
            startError?.let {
                current = current.copy(
                    state = GuiState.ERROR,
                    failure = GuiFailure(GuiFailureKind.COMPOSITOR_CRASHED, "boom"),
                )
                throw it
            }
            current = readyStatus
            return readyStatus
        }
        override suspend fun stop() {
            stopCount++
            current = current.copy(state = GuiState.STOPPED)
        }
    }

    private fun runtime(
        capabilities: GraphicsCapabilities = Capabilities.accelerated,
        compositor: Compositor = StubCompositor(
            CompositorStatus(CompositorId.WESTON, GuiState.READY, pid = 99, waylandSocket = "wayland-0"),
        ),
        transport: FakeDisplayTransport = FakeDisplayTransport(),
        provisioner: WaylandSessionProvisioner = FakeWaylandSessionProvisioner(temp.newFolder()),
        geometryOverride: DisplayGeometry? = geometry,
        registry: CompositorRegistry = DefaultCompositorRegistry(
            listOf(WestonCompositorFactory { compositor }),
        ),
    ) = DefaultGuiRuntime(
        capabilityProbe = FixedCapabilityProbe(capabilities),
        backendSelector = DefaultCompositorBackendSelector(),
        sessionProvisioner = provisioner,
        compositorRegistry = registry,
        displayTransport = transport,
        guiLogFactory = { log },
        geometryProvider = { geometryOverride },
        sessionIdProvider = { SessionId("s1") },
    )

    @Test
    fun `initialize probes capabilities selects a backend and provisions the session`() = runTest {
        val transport = FakeDisplayTransport()
        val gui = runtime(transport = transport)

        val status = gui.initialize(environment, GuiRuntimeConfig())

        assertThat(status.state).isEqualTo(GuiState.INITIALIZING)
        assertThat(status.session).isNotNull()
        assertThat(gui.capabilities().isAvailable(GraphicsCapability.ANDROID_SURFACE)).isTrue()
        assertThat(transport.attachCount).isEqualTo(1)
        assertThat(log.hasMessageContaining("display backend selected")).isTrue()
        assertThat(log.hasMessageContaining("gui runtime initialized")).isTrue()
    }

    @Test
    fun `disabled configuration keeps the runtime DISABLED`() = runTest {
        val gui = runtime()

        val status = gui.initialize(environment, GuiRuntimeConfig(enabled = false))

        assertThat(status.state).isEqualTo(GuiState.DISABLED)
    }

    @Test
    fun `no viable backend fails with NO_VIABLE_BACKEND`() = runTest {
        val gui = runtime(capabilities = Capabilities.none)

        val error = runCatching { gui.initialize(environment, GuiRuntimeConfig()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(GuiError::class.java)
        assertThat(error).hasMessageThat().contains("NO_VIABLE_BACKEND")
        assertThat(gui.status.state).isEqualTo(GuiState.ERROR)
    }

    @Test
    fun `missing android surface fails before backend selection`() = runTest {
        val gui = runtime(geometryOverride = null)

        val error = runCatching { gui.initialize(environment, GuiRuntimeConfig()) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("android display surface unavailable")
    }

    @Test
    fun `unregistered compositor is an explicit failure`() = runTest {
        val gui = runtime(registry = DefaultCompositorRegistry(emptyList()))

        val error = runCatching { gui.initialize(environment, GuiRuntimeConfig()) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("not registered")
    }

    @Test
    fun `provisioning failure is reported as SESSION_SETUP_FAILED`() = runTest {
        val gui = runtime(
            provisioner = FakeWaylandSessionProvisioner(
                temp.newFolder(),
                error = GuiError("cannot create dir"),
            ),
        )

        val error = runCatching { gui.initialize(environment, GuiRuntimeConfig()) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("SESSION_SETUP_FAILED")
    }

    @Test
    fun `display attach failure releases the provisioned session`() = runTest {
        val provisioner = FakeWaylandSessionProvisioner(temp.newFolder())
        val gui = runtime(
            transport = FakeDisplayTransport(attachError = GuiError("no surface")),
            provisioner = provisioner,
        )

        val error = runCatching { gui.initialize(environment, GuiRuntimeConfig()) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("DISPLAY_TRANSPORT_FAILED")
        assertThat(provisioner.releaseCount).isEqualTo(1)
    }

    @Test
    fun `start reaches RUNNING through READY`() = runTest {
        val gui = runtime()
        gui.initialize(environment, GuiRuntimeConfig())

        val status = gui.start()

        assertThat(status.state).isEqualTo(GuiState.RUNNING)
        assertThat(log.hasMessageContaining("gui session READY")).isTrue()
    }

    @Test
    fun `compositor startup failure leaves the runtime in ERROR and cleans up`() = runTest {
        val provisioner = FakeWaylandSessionProvisioner(temp.newFolder())
        val transport = FakeDisplayTransport()
        val gui = runtime(
            compositor = StubCompositor(
                CompositorStatus(CompositorId.WESTON, GuiState.READY),
                startError = GuiError("[COMPOSITOR_CRASHED] boom"),
            ),
            transport = transport,
            provisioner = provisioner,
        )
        gui.initialize(environment, GuiRuntimeConfig())

        val error = runCatching { gui.start() }.exceptionOrNull()

        assertThat(error).isInstanceOf(GuiError::class.java)
        assertThat(gui.status.state).isEqualTo(GuiState.ERROR)
        assertThat(gui.status.failure).isNotNull()
        // Cleanup after failure.
        assertThat(transport.detachCount).isAtLeast(1)
        assertThat(provisioner.releaseCount).isAtLeast(1)
    }

    @Test
    fun `a non-usable compositor state never reports READY`() = runTest {
        val gui = runtime(
            compositor = StubCompositor(CompositorStatus(CompositorId.WESTON, GuiState.STARTING)),
        )
        gui.initialize(environment, GuiRuntimeConfig())

        val error = runCatching { gui.start() }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("non-usable state")
        assertThat(gui.status.state).isEqualTo(GuiState.ERROR)
    }

    @Test
    fun `start before initialize is rejected`() = runTest {
        val gui = runtime()

        val error = runCatching { gui.start() }.exceptionOrNull()

        assertThat(error).isInstanceOf(GuiError::class.java)
        assertThat(error).hasMessageThat().contains("initialize()")
    }

    @Test
    fun `shutdown stops the compositor releases display and cleans session state`() = runTest {
        val compositor = StubCompositor(
            CompositorStatus(CompositorId.WESTON, GuiState.READY, pid = 99, waylandSocket = "wayland-0"),
        )
        val transport = FakeDisplayTransport()
        val provisioner = FakeWaylandSessionProvisioner(temp.newFolder())
        val gui = runtime(compositor = compositor, transport = transport, provisioner = provisioner)
        gui.initialize(environment, GuiRuntimeConfig())
        gui.start()

        gui.shutdown()

        assertThat(compositor.stopCount).isEqualTo(1)
        assertThat(transport.detachCount).isEqualTo(1)
        assertThat(provisioner.releaseCount).isEqualTo(1)
        assertThat(gui.status.state).isEqualTo(GuiState.STOPPED)
        assertThat(gui.status.session).isNull()
        assertThat(log.hasMessageContaining("gui session STOPPED cleanly")).isTrue()
    }

    @Test
    fun `shutdown is idempotent`() = runTest {
        val gui = runtime()
        gui.initialize(environment, GuiRuntimeConfig())
        gui.start()

        gui.shutdown()
        gui.shutdown()

        assertThat(gui.status.state).isEqualTo(GuiState.STOPPED)
    }

    @Test
    fun `shutdown order is compositor then display then wayland state`() = runTest {
        val order = mutableListOf<String>()
        val compositor = object : Compositor by StubCompositor(
            CompositorStatus(CompositorId.WESTON, GuiState.READY),
        ) {
            override suspend fun stop() { order += "compositor" }
        }
        val transport = object : DisplayTransport by FakeDisplayTransport() {
            override suspend fun detach() { order += "display" }
        }
        val provisioner = object : WaylandSessionProvisioner {
            private val delegate = FakeWaylandSessionProvisioner(temp.newFolder())
            override suspend fun provision(environmentId: EnvironmentId, sessionId: SessionId) =
                delegate.provision(environmentId, sessionId)
            override suspend fun release(session: WaylandSessionInfo) { order += "wayland" }
        }
        val instrumented = DefaultGuiRuntime(
            capabilityProbe = FixedCapabilityProbe(Capabilities.accelerated),
            backendSelector = DefaultCompositorBackendSelector(),
            sessionProvisioner = provisioner,
            compositorRegistry = DefaultCompositorRegistry(
                listOf(WestonCompositorFactory { compositor }),
            ),
            displayTransport = transport,
            guiLogFactory = { log },
            geometryProvider = { geometry },
            sessionIdProvider = { SessionId("s1") },
        )
        instrumented.initialize(environment, GuiRuntimeConfig())
        instrumented.start()

        instrumented.shutdown()

        assertThat(order).containsExactly("compositor", "display", "wayland").inOrder()
        assertThat(instrumented.status.state).isEqualTo(GuiState.STOPPED)
    }

    @Test
    fun `gui logs are separated from runtime console logs`() = runTest {
        val gui = runtime()
        gui.initialize(environment, GuiRuntimeConfig())

        assertThat(log.messages(GuiLogCategory.GRAPHICS)).isNotEmpty()
        assertThat(log.messages(GuiLogCategory.GUI)).isNotEmpty()
    }
}
