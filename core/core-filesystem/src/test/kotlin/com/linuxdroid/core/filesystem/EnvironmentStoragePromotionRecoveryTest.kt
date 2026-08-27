package com.linuxdroid.core.filesystem

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.EnvironmentId
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnvironmentStoragePromotionRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: EnvironmentStorage
    private val envId = EnvironmentId("recovery-test-env")

    @Before
    fun setup() {
        storage = EnvironmentStorage(tempFolder.newFolder("environments"))
    }

    private fun createMinimalRootfs(dir: File) {
        File(dir, "bin").mkdirs()
        File(dir, "etc").mkdirs()
        File(dir, "usr").mkdirs()
        File(dir, "etc/os-release").writeText("NAME=LinuxDroid")
    }

    @Test
    fun `promoteStagedRootfs successfully promotes staging and removes backup`() = runTest {
        storage.initializeEnvironmentDirs(envId)
        val staging = storage.stagingRootfsDir(envId)
        createMinimalRootfs(staging)

        val promoted = storage.promoteStagedRootfs(envId)

        assertThat(promoted).isTrue()
        assertThat(storage.verifyRootfs(envId)).isTrue()
        assertThat(staging.exists()).isFalse()
        assertThat(storage.backupRootfsDir(envId).exists()).isFalse()
    }

    @Test
    fun `recoverInterruptedPromotion restores backup when active rootfs is missing or invalid`() = runTest {
        storage.initializeEnvironmentDirs(envId)
        val backup = storage.backupRootfsDir(envId)
        createMinimalRootfs(backup)

        // Active rootfs is missing
        val target = storage.rootfsDir(envId)
        if (target.exists()) target.deleteRecursively()

        val recovered = storage.recoverInterruptedPromotion(envId)

        assertThat(recovered).isTrue()
        assertThat(storage.verifyRootfs(envId)).isTrue()
        assertThat(File(target, "etc/os-release").readText()).isEqualTo("NAME=LinuxDroid")
    }

    @Test
    fun `discardStaging preserves backup if active rootfs is missing`() = runTest {
        storage.initializeEnvironmentDirs(envId)
        val backup = storage.backupRootfsDir(envId)
        createMinimalRootfs(backup)
        val staging = storage.stagingRootfsDir(envId)
        staging.mkdirs()

        // Active rootfs does not exist
        assertThat(storage.rootfsDir(envId).exists()).isFalse()

        storage.discardStaging(envId)

        // Staging deleted, but backup preserved for crash recovery
        assertThat(staging.exists()).isFalse()
        assertThat(backup.exists()).isTrue()
    }
}
