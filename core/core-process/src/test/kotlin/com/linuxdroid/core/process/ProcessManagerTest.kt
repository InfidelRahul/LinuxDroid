package com.linuxdroid.core.process

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.ProcessHandle
import com.linuxdroid.core.model.ProcessState
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ProcessManagerTest {

    private lateinit var manager: DefaultProcessManager

    @Before
    fun setup() {
        manager = DefaultProcessManager()
    }

    @Test
    fun `registerProcess tracks process and emits started event`() = runTest {
        val envId = EnvironmentId("test-env")
        val handle = ProcessHandle(
            handleId = "p-1",
            environmentId = envId,
            command = listOf("/bin/sh"),
            pid = 4321,
            guestPid = 1,
            processRole = "terminal",
            state = ProcessState.RUNNING,
        )

        manager.registerProcess(handle)

        val retrieved = manager.getProcess("p-1")
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.pid).isEqualTo(4321)
        assertThat(retrieved?.guestPid).isEqualTo(1)
        assertThat(retrieved?.processRole).isEqualTo("terminal")
    }

    @Test
    fun `getProcessesForEnvironment filters processes by environmentId`() = runTest {
        val env1 = EnvironmentId("env-1")
        val env2 = EnvironmentId("env-2")

        manager.registerProcess(
            ProcessHandle(
                handleId = "p-1",
                environmentId = env1,
                command = listOf("/bin/ls"),
                pid = 101,
            )
        )
        manager.registerProcess(
            ProcessHandle(
                handleId = "p-2",
                environmentId = env2,
                command = listOf("/bin/top"),
                pid = 102,
            )
        )

        manager.getProcessesForEnvironment(env1).test {
            val list = awaitItem()
            assertThat(list.size).isEqualTo(1)
            assertThat(list.first().handleId).isEqualTo("p-1")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
