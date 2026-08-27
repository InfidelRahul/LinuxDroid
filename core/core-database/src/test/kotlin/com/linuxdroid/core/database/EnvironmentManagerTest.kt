package com.linuxdroid.core.database

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnvironmentManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dao = mockk<EnvironmentDao>(relaxed = true)
    private lateinit var storage: EnvironmentStorage
    private lateinit var manager: EnvironmentManager

    @Before
    fun setup() {
        storage = EnvironmentStorage(tempFolder.newFolder("environments"))
        manager = DefaultEnvironmentManager(dao, storage)
    }

    @Test
    fun `createEnvironment inserts entity and initializes directories`() = runTest {
        val env = manager.createEnvironment(
            name = "Test Debian",
            distribution = Distribution.DEBIAN,
            architecture = Architecture.ARM64,
        )

        assertThat(env.name).isEqualTo("Test Debian")
        assertThat(env.distribution).isEqualTo(Distribution.DEBIAN)
        assertThat(env.state).isEqualTo(EnvironmentState.CREATED)

        coVerify { dao.insert(any()) }
        assertThat(File(env.metadataPath).exists()).isTrue()
    }

    @Test
    fun `installEnvironment updates state to INSTALLING then READY on success`() = runTest {
        val envId = EnvironmentId("install-test")
        val sampleEnv = manager.createEnvironment("Install Test")
        val entity = EnvironmentMapper.toEntity(sampleEnv)

        coEvery { dao.getById(sampleEnv.id.value) } returns entity

        // Create minimal rootfs structure so verifyRootfs succeeds
        val rootfs = storage.rootfsDir(sampleEnv.id)
        File(rootfs, "bin").mkdirs()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "usr").mkdirs()

        var installerCalled = false
        val installedEnv = manager.installEnvironment(sampleEnv.id) {
            installerCalled = true
        }

        assertThat(installerCalled).isTrue()
        assertThat(installedEnv.state).isEqualTo(EnvironmentState.READY)
        coVerify { dao.updateState(sampleEnv.id.value, EnvironmentState.INSTALLING.name, any(), null) }
        coVerify { dao.updateState(sampleEnv.id.value, EnvironmentState.READY.name, any(), null) }
    }

    @Test
    fun `deleteEnvironment deletes database record and storage directories`() = runTest {
        val env = manager.createEnvironment("Delete Test")
        assertThat(storage.environmentExists(env.id)).isTrue()

        manager.deleteEnvironment(env.id)

        coVerify { dao.deleteById(env.id.value) }
        assertThat(storage.environmentExists(env.id)).isFalse()
    }
}
