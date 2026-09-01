package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.EnvironmentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuiStateMachineTest {

    @Test
    fun `disabled only starts through initializing`() {
        assertThat(GuiState.DISABLED.canTransitionTo(GuiState.INITIALIZING)).isTrue()
        assertThat(GuiState.DISABLED.canTransitionTo(GuiState.RUNNING)).isFalse()
        assertThat(GuiState.DISABLED.canTransitionTo(GuiState.READY)).isFalse()
    }

    @Test
    fun `starting cannot jump straight to running without observed readiness`() {
        assertThat(GuiState.STARTING.canTransitionTo(GuiState.RUNNING)).isFalse()
        assertThat(GuiState.STARTING.canTransitionTo(GuiState.READY)).isTrue()
        assertThat(GuiState.READY.canTransitionTo(GuiState.RUNNING)).isTrue()
    }

    @Test
    fun `every active state can reach error and shutdown`() {
        listOf(GuiState.INITIALIZING, GuiState.STARTING, GuiState.READY, GuiState.RUNNING)
            .forEach { assertThat(it.canTransitionTo(GuiState.ERROR)).isTrue() }
        assertThat(GuiState.STOPPING.canTransitionTo(GuiState.STOPPED)).isTrue()
        assertThat(GuiState.ERROR.canTransitionTo(GuiState.INITIALIZING)).isTrue()
    }

    @Test
    fun `only ready and running are usable`() {
        val usable = GuiState.entries.filter { it.isUsable }
        assertThat(usable).containsExactly(GuiState.READY, GuiState.RUNNING)
    }

    @Test
    fun `stopped and disabled are not active`() {
        assertThat(GuiState.STOPPED.isActive).isFalse()
        assertThat(GuiState.DISABLED.isActive).isFalse()
        assertThat(GuiState.STOPPING.isActive).isTrue()
    }
}

class GraphicsCapabilitiesTest {

    @Test
    fun `unprobed reports nothing available`() {
        val caps = GraphicsCapabilities.UNPROBED
        assertThat(caps.results).hasSize(GraphicsCapability.entries.size)
        assertThat(caps.results.none { it.isAvailable }).isTrue()
        assertThat(caps.hasAnyPresentationPath).isFalse()
        assertThat(caps.hasHardwareAcceleration).isFalse()
    }

    @Test
    fun `missing capability defaults to not probed rather than available`() {
        val caps = GraphicsCapabilities(results = emptyList())
        val egl = caps.result(GraphicsCapability.EGL)
        assertThat(egl.outcome).isEqualTo(ProbeOutcome.NOT_PROBED)
        assertThat(egl.isAvailable).isFalse()
    }

    @Test
    fun `presentation path detected from probed surface support`() {
        val caps = GraphicsCapabilities(
            results = listOf(
                CapabilityProbeResult(
                    capability = GraphicsCapability.ANDROID_SURFACE,
                    outcome = ProbeOutcome.AVAILABLE,
                    evidence = "ANativeWindow acquired",
                ),
                CapabilityProbeResult(
                    capability = GraphicsCapability.OPENGL_ES,
                    outcome = ProbeOutcome.AVAILABLE,
                    evidence = "OpenGL ES 3.2",
                    hardwareAccelerated = true,
                ),
            ),
        )
        assertThat(caps.hasAnyPresentationPath).isTrue()
        assertThat(caps.hasHardwareAcceleration).isTrue()
        assertThat(caps.summary()).contains("OPENGL_ES=AVAILABLE(hw)")
    }
}

class GuiFailureTest {

    @Test
    fun `describe includes kind message and cause`() {
        val failure = GuiFailure(
            kind = GuiFailureKind.COMPOSITOR_READINESS_TIMEOUT,
            message = "compositor did not become ready",
            detail = "socket=/run/linuxdroid/wayland-0",
            cause = IllegalStateException("timeout"),
        )
        val text = failure.describe()
        assertThat(text).contains("COMPOSITOR_READINESS_TIMEOUT")
        assertThat(text).contains("compositor did not become ready")
        assertThat(text).contains("wayland-0")
        assertThat(text).contains("IllegalStateException: timeout")
    }
}

class CompositorRegistryTest {

    private class FakeFactory(override val id: CompositorId) : CompositorFactory {
        override fun create(): Compositor = throw UnsupportedOperationException("phase 3")
    }

    @Test
    fun `registry resolves registered compositors only`() {
        val registry = DefaultCompositorRegistry(listOf(FakeFactory(CompositorId.WESTON)))
        assertThat(registry.ids()).containsExactly(CompositorId.WESTON)
        assertThat(registry.factory(CompositorId.WESTON)).isNotNull()
        assertThat(registry.factory(CompositorId("cage"))).isNull()
    }

    @Test
    fun `compositor id rejects blank values`() {
        try {
            CompositorId("  ")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("CompositorId")
        }
    }
}

class FileGuiLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `writes each category to its own file`() {
        val storage = EnvironmentStorage(baseDir = temp.newFolder("environments"))
        val envId = EnvironmentId("debian")
        val log = FileGuiLog(envId, storage) { 1_000L }

        log.info(GuiLogCategory.GUI, "gui runtime initialized")
        log.info(GuiLogCategory.COMPOSITOR, "compositor starting")
        log.failure(
            GuiLogCategory.GRAPHICS,
            GuiFailure(GuiFailureKind.NO_VIABLE_BACKEND, "no presentation path"),
        )

        val guiFile = log.logFile(GuiLogCategory.GUI)
        val compositorFile = log.logFile(GuiLogCategory.COMPOSITOR)
        val graphicsFile = log.logFile(GuiLogCategory.GRAPHICS)

        assertThat(guiFile.readText()).contains("gui runtime initialized")
        assertThat(compositorFile.readText()).contains("compositor starting")
        assertThat(graphicsFile.readText()).contains("NO_VIABLE_BACKEND")
        assertThat(guiFile.readText()).doesNotContain("compositor starting")
        assertThat(guiFile.readText()).startsWith("1000 I [GUI]")
    }

    @Test
    fun `gui logs live beside but separate from runtime logs`() {
        val storage = EnvironmentStorage(baseDir = temp.newFolder("environments"))
        val envId = EnvironmentId("debian")
        val log = FileGuiLog(envId, storage)
        log.info(GuiLogCategory.WAYLAND, "socket created")

        assertThat(log.guiLogDir().parentFile).isEqualTo(storage.logsDir(envId))
        assertThat(storage.consoleLogFile(envId).exists()).isFalse()
        assertThat(storage.prootLogFile(envId).exists()).isFalse()
    }
}

class WaylandSessionInfoTest {

    @Test
    fun `requires an absolute runtime dir`() {
        try {
            WaylandSessionInfo(
                environmentId = EnvironmentId("debian"),
                sessionId = com.linuxdroid.core.model.SessionId("s1"),
                runtimeDir = "run/linuxdroid",
                hostRuntimeDir = "/data/run",
                socketName = "wayland-0",
                socketPath = "/run/linuxdroid/wayland-0",
                hostSocketPath = "/data/run/wayland-0",
                logDir = "/run/linuxdroid/logs",
                environment = emptyMap(),
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("absolute")
        }
    }
}

/** Compile-time check that the runtime contract can be implemented off-Android. */
private class ContractStub : GuiRuntime {
    override val status = GuiRuntimeStatus()
    override val statusUpdates: Flow<GuiRuntimeStatus> = emptyFlow()
    override suspend fun initialize(
        environment: com.linuxdroid.core.model.Environment,
        config: GuiRuntimeConfig,
    ) = status
    override suspend fun start() = status
    override suspend fun shutdown() = Unit
    override suspend fun restart() = status
    override fun capabilities() = GraphicsCapabilities.UNPROBED
}
