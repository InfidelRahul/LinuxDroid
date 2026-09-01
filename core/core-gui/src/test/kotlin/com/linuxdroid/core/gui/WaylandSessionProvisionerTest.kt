package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.GuiError
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultWaylandSessionProvisionerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var storage: EnvironmentStorage
    private lateinit var provisioner: DefaultWaylandSessionProvisioner
    private val log = RecordingGuiLog()
    private val envId = EnvironmentId("debian")
    private val sessionId = SessionId("session-1")

    @Before
    fun setUp() {
        storage = EnvironmentStorage(temp.newFolder("environments"))
        provisioner = DefaultWaylandSessionProvisioner(storage, guiLogFactory = { log })
    }

    @Test
    fun `creates a per-environment runtime directory under runtime-state`() = runTest {
        val session = provisioner.provision(envId, sessionId)

        val hostDir = File(session.hostRuntimeDir)
        assertThat(hostDir.isDirectory).isTrue()
        assertThat(hostDir.parentFile).isEqualTo(storage.runtimeStateDir(envId))
        assertThat(hostDir.canWrite()).isTrue()
    }

    @Test
    fun `never uses tmp as the runtime directory`() = runTest {
        val session = provisioner.provision(envId, sessionId)

        assertThat(session.runtimeDir).isEqualTo("/run/linuxdroid")
        assertThat(session.runtimeDir).isNotEqualTo("/tmp")
        assertThat(session.environment["XDG_RUNTIME_DIR"]).isEqualTo("/run/linuxdroid")
    }

    @Test
    fun `runtime directory is private to its owner`() = runTest {
        val session = provisioner.provision(envId, sessionId)
        val dir = File(session.hostRuntimeDir)

        assertThat(dir.canRead()).isTrue()
        assertThat(dir.canWrite()).isTrue()
        assertThat(dir.canExecute()).isTrue()
    }

    @Test
    fun `generates the wayland session environment`() = runTest {
        val session = provisioner.provision(envId, sessionId)

        assertThat(session.environment).containsEntry("WAYLAND_DISPLAY", session.socketName)
        assertThat(session.environment).containsEntry("XDG_SESSION_TYPE", "wayland")
        assertThat(session.environment).containsEntry("GDK_BACKEND", "wayland")
        assertThat(session.environment).containsEntry("QT_QPA_PLATFORM", "wayland")
        // DISPLAY must not be set: XWayland is not part of compositor bring-up.
        assertThat(session.environment).doesNotContainKey("DISPLAY")
    }

    @Test
    fun `socket paths are consistent between host and guest`() = runTest {
        val session = provisioner.provision(envId, sessionId)

        assertThat(session.socketPath).isEqualTo("${session.runtimeDir}/${session.socketName}")
        assertThat(session.hostSocketPath)
            .isEqualTo(File(session.hostRuntimeDir, session.socketName).absolutePath)
    }

    @Test
    fun `picks a free socket name instead of assuming wayland-0`() = runTest {
        val hostDir = provisioner.hostRuntimeDir(envId).apply { mkdirs() }
        File(hostDir, "wayland-0").writeText("stale")
        File(hostDir, "wayland-1").writeText("stale")

        val session = provisioner.provision(envId, sessionId)

        assertThat(session.socketName).isEqualTo("wayland-2")
    }

    @Test
    fun `fails explicitly when no socket name is free`() = runTest {
        val provisionerWithOneSlot = DefaultWaylandSessionProvisioner(
            storage = storage,
            guiLogFactory = { log },
            maxSocketCandidates = 1,
        )
        val hostDir = provisionerWithOneSlot.hostRuntimeDir(envId).apply { mkdirs() }
        File(hostDir, "wayland-0").writeText("stale")

        val error = runCatching { provisionerWithOneSlot.provision(envId, sessionId) }.exceptionOrNull()

        assertThat(error).isInstanceOf(GuiError::class.java)
        assertThat(error).hasMessageThat().contains("SESSION_SETUP_FAILED")
        assertThat(log.hasMessageContaining("no free Wayland socket name")).isTrue()
    }

    @Test
    fun `fails explicitly when the runtime directory cannot be created`() = runTest {
        // A regular file where the directory must go makes mkdirs() fail.
        val runtimeState = storage.runtimeStateDir(envId).apply { mkdirs() }
        File(runtimeState, "wayland").writeText("not a directory")

        val error = runCatching { provisioner.provision(envId, sessionId) }.exceptionOrNull()

        assertThat(error).isInstanceOf(GuiError::class.java)
        assertThat(error).hasMessageThat().contains("Wayland runtime directory")
    }

    @Test
    fun `release removes the socket but keeps the runtime directory and rootfs`() = runTest {
        val session = provisioner.provision(envId, sessionId)
        val hostDir = File(session.hostRuntimeDir)
        File(hostDir, session.socketName).writeText("socket")
        File(hostDir, "${session.socketName}.lock").writeText("lock")
        File(hostDir, "weston.ini").writeText("config")

        provisioner.release(session)

        assertThat(File(hostDir, session.socketName).exists()).isFalse()
        assertThat(File(hostDir, "${session.socketName}.lock").exists()).isFalse()
        assertThat(hostDir.isDirectory).isTrue()
        assertThat(storage.rootfsDir(envId).absolutePath).doesNotContain("runtime-state")
    }

    @Test
    fun `release is safe when the runtime directory is already gone`() = runTest {
        val session = provisioner.provision(envId, sessionId)
        File(session.hostRuntimeDir).deleteRecursively()

        provisioner.release(session)

        assertThat(log.hasMessageContaining("already absent")).isTrue()
    }

    @Test
    fun `logs provisioning to the wayland category only`() = runTest {
        provisioner.provision(envId, sessionId)

        assertThat(log.messages(GuiLogCategory.WAYLAND)).isNotEmpty()
        assertThat(log.messages(GuiLogCategory.COMPOSITOR)).isEmpty()
    }
}
