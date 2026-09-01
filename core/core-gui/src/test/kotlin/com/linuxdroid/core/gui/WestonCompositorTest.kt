package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.GuiError
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WestonCompositorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val log = RecordingGuiLog()
    private val geometry = DisplayGeometry(1080, 2400, 420, 60f)
    private val backend = BackendSelection(
        backend = CompositorBackend.ANDROID_SURFACE,
        rationale = "probed EGL+GLES",
        hardwareAccelerated = true,
    )

    private fun session(dir: File, socketName: String = "wayland-0") = WaylandSessionInfo(
        environmentId = EnvironmentId("debian"),
        sessionId = SessionId("s1"),
        runtimeDir = "/run/linuxdroid",
        hostRuntimeDir = dir.absolutePath,
        socketName = socketName,
        socketPath = "/run/linuxdroid/$socketName",
        hostSocketPath = File(dir, socketName).absolutePath,
        logDir = "/run/linuxdroid/logs",
        environment = mapOf(
            "XDG_RUNTIME_DIR" to "/run/linuxdroid",
            "WAYLAND_DISPLAY" to socketName,
        ),
    )

    private fun compositor(
        launcher: CompositorProcessLauncher,
        connectable: Boolean = true,
        geometryOverride: DisplayGeometry? = geometry,
    ) = WestonCompositor(
        launcher = launcher,
        readinessProbe = WaylandReadinessProbe({ connectable }, pollIntervalMs = 1),
        log = log,
        geometryProvider = { geometryOverride },
    )

    private fun request(dir: File, timeoutMs: Long = 1_000) = CompositorLaunchRequest(
        session = session(dir),
        backend = backend,
        readinessTimeoutMs = timeoutMs,
    )

    @Test
    fun `reaches READY only after the socket is verified`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val launcher = FakeCompositorProcessLauncher()
        val weston = compositor(launcher)

        val status = weston.start(request(dir))

        assertThat(status.state).isEqualTo(GuiState.READY)
        assertThat(status.waylandSocket).isEqualTo("wayland-0")
        assertThat(status.pid).isEqualTo(4242)
        assertThat(log.hasMessageContaining("wayland readiness verified")).isTrue()
    }

    @Test
    fun `cannot reach RUNNING without passing through READY`() = runTest {
        val dir = temp.newFolder("runtime")
        val launcher = FakeCompositorProcessLauncher(executableAvailable = false)
        val weston = compositor(launcher)

        runCatching { weston.start(request(dir)) }

        assertThat(weston.status.state).isEqualTo(GuiState.ERROR)
        // markRunning is a no-op unless the compositor actually became READY.
        weston.markRunning()
        assertThat(weston.status.state).isEqualTo(GuiState.ERROR)
    }

    @Test
    fun `markRunning is only honoured from READY`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val weston = compositor(FakeCompositorProcessLauncher())
        weston.start(request(dir))

        weston.markRunning()

        assertThat(weston.status.state).isEqualTo(GuiState.RUNNING)
    }

    @Test
    fun `missing executable fails with an ENOENT diagnostic and never launches`() = runTest {
        val dir = temp.newFolder("runtime")
        val launcher = FakeCompositorProcessLauncher(executableAvailable = false)
        val weston = compositor(launcher)

        val error = runCatching { weston.start(request(dir)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(GuiError::class.java)
        assertThat(error).hasMessageThat().contains("COMPOSITOR_LAUNCH_FAILED")
        assertThat(error).hasMessageThat().contains("ENOENT")
        assertThat(launcher.launchCount).isEqualTo(0)
        assertThat(weston.status.state).isEqualTo(GuiState.ERROR)
    }

    @Test
    fun `process launch failure is reported as a launch failure`() = runTest {
        val dir = temp.newFolder("runtime")
        val launcher = FakeCompositorProcessLauncher(launchError = RuntimeException("fork failed"))
        val weston = compositor(launcher)

        val error = runCatching { weston.start(request(dir)) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("COMPOSITOR_LAUNCH_FAILED")
        assertThat(error).hasMessageThat().contains("fork failed")
        assertThat(weston.status.failure?.kind).isEqualTo(GuiFailureKind.COMPOSITOR_LAUNCH_FAILED)
    }

    @Test
    fun `compositor exiting early is reported as a crash`() = runTest {
        val dir = temp.newFolder("runtime")
        val process = FakeCompositorProcess(alive = false, exit = 1)
        val launcher = FakeCompositorProcessLauncher(process = process)
        val weston = compositor(launcher)

        val error = runCatching { weston.start(request(dir)) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("COMPOSITOR_CRASHED")
        assertThat(weston.status.state).isEqualTo(GuiState.ERROR)
    }

    @Test
    fun `socket timeout produces a readiness timeout failure and kills the process`() = runTest {
        val dir = temp.newFolder("runtime") // no socket ever appears
        val process = FakeCompositorProcess()
        val launcher = FakeCompositorProcessLauncher(process = process)
        val weston = compositor(launcher)

        val error = runCatching { weston.start(request(dir, timeoutMs = 20)) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("COMPOSITOR_READINESS_TIMEOUT")
        // Cleanup after failure: no orphan compositor is left behind.
        assertThat(process.terminateCount).isAtLeast(1)
        assertThat(weston.status.pid).isEqualTo(-1)
    }

    @Test
    fun `unusable socket fails readiness rather than reporting ready`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val weston = compositor(FakeCompositorProcessLauncher(), connectable = false)

        val error = runCatching { weston.start(request(dir, timeoutMs = 20)) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("COMPOSITOR_READINESS_TIMEOUT")
        assertThat(error).hasMessageThat().contains("SOCKET_CONNECTABLE")
    }

    @Test
    fun `missing display output fails before anything is launched`() = runTest {
        val dir = temp.newFolder("runtime")
        val launcher = FakeCompositorProcessLauncher()
        val weston = compositor(launcher, geometryOverride = null)

        val error = runCatching { weston.start(request(dir)) }.exceptionOrNull()

        assertThat(error).hasMessageThat().contains("DISPLAY_TRANSPORT_FAILED")
        assertThat(launcher.launchCount).isEqualTo(0)
    }

    @Test
    fun `generates a per-session config and passes the real socket name`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-7").writeText("")
        val launcher = FakeCompositorProcessLauncher()
        val weston = compositor(launcher)

        weston.start(
            CompositorLaunchRequest(
                session = session(dir, "wayland-7"),
                backend = backend,
                readinessTimeoutMs = 1_000,
            ),
        )

        val command = launcher.lastCommand!!
        assertThat(command).contains("--socket=wayland-7")
        assertThat(command.first()).isEqualTo("weston")
        assertThat(File(dir, WestonCompositor.CONFIG_FILE_NAME).readText()).contains("renderer=gl")
    }

    @Test
    fun `passes the session environment and binds the runtime directory`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val launcher = FakeCompositorProcessLauncher()

        compositor(launcher).start(request(dir))

        assertThat(launcher.lastEnv).containsEntry("XDG_RUNTIME_DIR", "/run/linuxdroid")
        assertThat(launcher.lastEnv["XDG_RUNTIME_DIR"]).isNotEqualTo("/tmp")
        assertThat(launcher.lastBindings).containsExactly(
            GuestBinding(dir.absolutePath, "/run/linuxdroid"),
        )
    }

    @Test
    fun `stop terminates the compositor and reports STOPPED`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val process = FakeCompositorProcess()
        val weston = compositor(FakeCompositorProcessLauncher(process = process))
        weston.start(request(dir))

        weston.stop()

        assertThat(process.terminateCount).isEqualTo(1)
        assertThat(weston.status.state).isEqualTo(GuiState.STOPPED)
        assertThat(weston.status.pid).isEqualTo(-1)
    }

    @Test
    fun `stop is idempotent`() = runTest {
        val weston = compositor(FakeCompositorProcessLauncher())

        weston.stop()
        weston.stop()

        assertThat(weston.status.state).isEqualTo(GuiState.STOPPED)
    }

    @Test
    fun `failure to terminate is surfaced as an error not a clean stop`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val process = FakeCompositorProcess(terminateSucceeds = false)
        val weston = compositor(FakeCompositorProcessLauncher(process = process))
        weston.start(request(dir))

        weston.stop()

        assertThat(weston.status.state).isEqualTo(GuiState.ERROR)
        assertThat(weston.status.failure?.kind).isEqualTo(GuiFailureKind.SHUTDOWN_FAILED)
    }

    @Test
    fun `compositor logs go to the compositor category`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")

        compositor(FakeCompositorProcessLauncher()).start(request(dir))

        assertThat(log.messages(GuiLogCategory.COMPOSITOR)).isNotEmpty()
        assertThat(log.messages(GuiLogCategory.WAYLAND).any { it.contains("socket detected") }).isTrue()
    }
}
