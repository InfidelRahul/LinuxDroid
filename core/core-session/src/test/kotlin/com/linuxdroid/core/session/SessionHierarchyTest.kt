package com.linuxdroid.core.session

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.gui.*
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.PtySession
import com.linuxdroid.core.runtime.RuntimeManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionHierarchyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val runtimeManager = mockk<RuntimeManager>(relaxed = true)
    private lateinit var storage: EnvironmentStorage
    private lateinit var environment: Environment

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("environments")
        storage = EnvironmentStorage(baseDir)
        environment = Environment(
            metadata = EnvironmentMetadata(
                id = EnvironmentId("test-session-env"),
                name = "Test Session Env",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            rootfsPath = storage.rootfsDir(EnvironmentId("test-session-env")).absolutePath,
            metadataPath = storage.metadataDir(EnvironmentId("test-session-env")).absolutePath,
        )
    }

    @Test
    fun `RuntimeSession prepares and executes command via RuntimeManager`() = runTest {
        val sessionId = SessionId.generate()
        val session = RuntimeSession(sessionId, environment, runtimeManager)

        coEvery { runtimeManager.execute(any(), any()) } returns ProcessHandle(
            handleId = "proc-1",
            environmentId = environment.id,
            sessionId = sessionId,
            command = listOf("/bin/ls"),
            workingDirectory = "/",
            pid = 1234,
            state = ProcessState.RUNNING,
        )

        val handle = session.execute(listOf("/bin/ls"))
        assertThat(handle.pid).isEqualTo(1234)
        coVerify { runtimeManager.execute(any(), sessionId) }
    }

    @Test
    fun `TerminalSession opens interactive shell via RuntimeManager`() = runTest {
        val sessionId = SessionId.generate()
        val session = TerminalSession(sessionId, environment, runtimeManager)

        val pty = PtySession("test-pty", environment.id, 5555, 3)
        coEvery { runtimeManager.startInteractiveShell(any(), any(), any()) } returns pty

        val openedPty = session.open(rows = 30, cols = 100)
        assertThat(openedPty.pid).isEqualTo(5555)
        assertThat(session.ptySession).isEqualTo(pty)
    }

    @Test
    fun `DesktopSession reports RUNNING using the verified GUI runtime session`() = runTest {
        val sessionId = SessionId.generate()
        val waylandSession = fakeWaylandSession(environment.id, sessionId, socketName = "wayland-3")
        val guiRuntime = FakeGuiRuntime(
            readyStatus = GuiRuntimeStatus(
                state = GuiState.RUNNING,
                session = waylandSession,
                compositor = CompositorStatus(
                    id = CompositorId.WESTON,
                    state = GuiState.RUNNING,
                    pid = 4242,
                    waylandSocket = "wayland-3",
                ),
            ),
        )
        val session = DesktopSession(
            sessionId = sessionId,
            environment = environment,
            runtimeManager = runtimeManager,
            storage = storage,
            guiRuntimeFactory = { _, _, _ -> guiRuntime },
        )

        val activeSession = session.start()

        assertThat(activeSession.state).isEqualTo(SessionState.RUNNING)
        assertThat(activeSession.waylandSocket).isEqualTo("wayland-3")
        assertThat(activeSession.compositorPid).isEqualTo(4242)
        // XWayland is optional and must not be started during compositor bring-up.
        assertThat(activeSession.display).isNull()
        assertThat(guiRuntime.initialized).isTrue()
        assertThat(guiRuntime.started).isTrue()
    }

    @Test
    fun `DesktopSession shuts the GUI runtime down when startup fails`() = runTest {
        val sessionId = SessionId.generate()
        val guiRuntime = FakeGuiRuntime(
            startError = GuiError("[COMPOSITOR_READINESS_TIMEOUT] compositor did not become ready"),
        )
        val session = DesktopSession(
            sessionId = sessionId,
            environment = environment,
            runtimeManager = runtimeManager,
            storage = storage,
            guiRuntimeFactory = { _, _, _ -> guiRuntime },
        )

        val error = runCatching { session.start() }.exceptionOrNull()

        assertThat(error).isInstanceOf(GuiError::class.java)
        assertThat(guiRuntime.shutdownCount).isEqualTo(1)
    }

    @Test
    fun `DesktopSession stop shuts down the GUI runtime and the linux runtime`() = runTest {
        val sessionId = SessionId.generate()
        val waylandSession = fakeWaylandSession(environment.id, sessionId)
        val guiRuntime = FakeGuiRuntime(
            readyStatus = GuiRuntimeStatus(state = GuiState.RUNNING, session = waylandSession),
        )
        val session = DesktopSession(
            sessionId = sessionId,
            environment = environment,
            runtimeManager = runtimeManager,
            storage = storage,
            guiRuntimeFactory = { _, _, _ -> guiRuntime },
        )
        session.start()

        session.stop()

        assertThat(guiRuntime.shutdownCount).isEqualTo(1)
        coVerify { runtimeManager.stop(environment.id) }
    }
}
