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
import org.junit.Assert.assertThrows

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
        val sampleEnv = manager.createEnvironment("Install Test")
        val entity = EnvironmentMapper.toEntity(sampleEnv)

        coEvery { dao.getById(sampleEnv.id.value) } returns entity

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
        val readyEnv = env.copy(state = EnvironmentState.READY)
        coEvery { dao.getById(env.id.value) } returns EnvironmentMapper.toEntity(readyEnv)
        assertThat(storage.environmentExists(env.id)).isTrue()

        manager.deleteEnvironment(env.id)

        coVerify { dao.deleteById(env.id.value) }
        assertThat(storage.environmentExists(env.id)).isFalse()
    }

    @Test
    fun `deleteEnvironment rejects deleting active environment`() = runTest {
        val env = manager.createEnvironment("Active Env")
        val activeEnv = env.copy(state = EnvironmentState.RUNNING)
        coEvery { dao.getById(env.id.value) } returns EnvironmentMapper.toEntity(activeEnv)

        assertThrows(RuntimeError::class.java) {
            kotlinx.coroutines.runBlocking {
                manager.deleteEnvironment(env.id)
            }
        }
    }

    @Test
    fun `cloneEnvironment copies rootfs via staging and marks READY`() = runTest {
        val source = manager.createEnvironment("Source Env")
        val sourceRootfs = storage.rootfsDir(source.id)
        File(sourceRootfs, "bin").mkdirs()
        File(sourceRootfs, "etc").mkdirs()
        File(sourceRootfs, "usr").mkdirs()

        coEvery { dao.getById(source.id.value) } returns EnvironmentMapper.toEntity(source.withState(EnvironmentState.READY))

        val cloned = manager.cloneEnvironment(source.id, "Cloned Env")

        assertThat(cloned.state).isEqualTo(EnvironmentState.READY)
        assertThat(storage.verifyRootfs(cloned.id)).isTrue()
        assertThat(storage.stagingRootfsDir(cloned.id).exists()).isFalse()
    }

    @Test
    fun `reconcileEnvironments cleans interrupted states on startup`() = runTest {
        val env1 = manager.createEnvironment("Interrupted Install")
        val env2 = manager.createEnvironment("Interrupted Delete")

        val entity1 = EnvironmentMapper.toEntity(env1.withState(EnvironmentState.INSTALLING))
        val entity2 = EnvironmentMapper.toEntity(env2.withState(EnvironmentState.DELETING))

        coEvery { dao.getAll() } returns listOf(entity1, entity2)

        val reconciled = manager.reconcileEnvironments()

        assertThat(reconciled.size).isEqualTo(1)
        assertThat(reconciled.first().state).isEqualTo(EnvironmentState.FAILED)
        coVerify { dao.deleteById(env2.id.value) }
    }
}
