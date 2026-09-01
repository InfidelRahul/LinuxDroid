package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WaylandReadinessProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun session(dir: File, socketName: String = "wayland-0") = WaylandSessionInfo(
        environmentId = EnvironmentId("debian"),
        sessionId = SessionId("s1"),
        runtimeDir = "/run/linuxdroid",
        hostRuntimeDir = dir.absolutePath,
        socketName = socketName,
        socketPath = "/run/linuxdroid/$socketName",
        hostSocketPath = File(dir, socketName).absolutePath,
        logDir = "/run/linuxdroid/logs",
        environment = emptyMap(),
    )

    @Test
    fun `process creation alone is never readiness`() = runTest {
        val dir = temp.newFolder("runtime")
        val probe = WaylandReadinessProbe({ true }, pollIntervalMs = 1)
        val process = FakeCompositorProcess()

        // Process is alive, but no socket has appeared yet.
        val result = probe.verify(session(dir), process)

        assertThat(result.ready).isFalse()
        assertThat(result.failedStep).isEqualTo(ReadinessStep.SOCKET_EXISTS)
    }

    @Test
    fun `socket that exists but refuses connections is not ready`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val probe = WaylandReadinessProbe({ false }, pollIntervalMs = 1)

        val result = probe.verify(session(dir), FakeCompositorProcess())

        assertThat(result.ready).isFalse()
        assertThat(result.failedStep).isEqualTo(ReadinessStep.SOCKET_CONNECTABLE)
        assertThat(result.detail).contains("refused a connection")
    }

    @Test
    fun `missing runtime directory fails before the socket check`() = runTest {
        val dir = File(temp.root, "never-created")
        val probe = WaylandReadinessProbe({ true }, pollIntervalMs = 1)

        val result = probe.verify(session(dir), FakeCompositorProcess())

        assertThat(result.failedStep).isEqualTo(ReadinessStep.RUNTIME_DIR_EXISTS)
    }

    @Test
    fun `dead compositor fails immediately without waiting for the timeout`() = runTest {
        val dir = temp.newFolder("runtime")
        val process = FakeCompositorProcess(alive = false, exit = 1)
        val probe = WaylandReadinessProbe({ true }, pollIntervalMs = 1)

        val result = probe.awaitReadyFor(session(dir), process, timeoutMs = 60_000)

        assertThat(result.ready).isFalse()
        assertThat(result.failedStep).isEqualTo(ReadinessStep.PROCESS_ALIVE)
        assertThat(result.detail).contains("exited")
    }

    @Test
    fun `times out when the socket never appears`() = runTest {
        val dir = temp.newFolder("runtime")
        var now = 0L
        val probe = WaylandReadinessProbe({ true }, pollIntervalMs = 1, clock = { now })
        val process = object : CompositorProcess by FakeCompositorProcess() {
            override fun isAlive(): Boolean {
                now += 50 // advance the virtual clock on each poll
                return true
            }
        }

        val result = probe.awaitReadyFor(session(dir), process, timeoutMs = 200)

        assertThat(result.ready).isFalse()
        assertThat(result.failedStep).isEqualTo(ReadinessStep.SOCKET_EXISTS)
        assertThat(result.detail).contains("timeout after 200ms")
    }

    @Test
    fun `is ready only once every step passes`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val probe = WaylandReadinessProbe({ true }, pollIntervalMs = 1)

        val result = probe.awaitReadyFor(session(dir), FakeCompositorProcess(), timeoutMs = 1_000)

        assertThat(result.ready).isTrue()
        assertThat(result.failedStep).isNull()
    }

    @Test
    fun `socket appearing later is detected by polling`() = runTest {
        val dir = temp.newFolder("runtime")
        val socket = File(dir, "wayland-0")
        var polls = 0
        val probe = WaylandReadinessProbe(
            connectivityChecker = { socket.exists() },
            pollIntervalMs = 1,
        )
        val process = object : CompositorProcess by FakeCompositorProcess() {
            override fun isAlive(): Boolean {
                if (++polls == 3) socket.writeText("")
                return true
            }
        }

        val result = probe.awaitReadyFor(session(dir), process, timeoutMs = 5_000)

        assertThat(result.ready).isTrue()
    }

    @Test
    fun `compositor dying during verification is reported as a crash`() = runTest {
        val dir = temp.newFolder("runtime")
        File(dir, "wayland-0").writeText("")
        val process = FakeCompositorProcess()
        val probe = WaylandReadinessProbe(
            connectivityChecker = {
                process.die(139) // crashes exactly while we are connecting
                true
            },
            pollIntervalMs = 1,
        )

        val result = probe.verify(session(dir), process)

        assertThat(result.ready).isFalse()
        assertThat(result.failedStep).isEqualTo(ReadinessStep.PROCESS_STILL_ALIVE)
    }
}
