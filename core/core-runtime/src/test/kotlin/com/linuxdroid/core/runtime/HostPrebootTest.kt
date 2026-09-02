package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.*
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostPrebootTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val commandBuilder = ProotCommandBuilder()
    private val preboot = HostPreboot(commandBuilder)

    private fun createValidRootfs(): File {
        val rootfs = tempFolder.newFolder("rootfs")
        listOf("etc", "usr", "tmp", "dev", "proc", "sys", "sbin", "bin").forEach {
            File(rootfs, it).mkdirs()
        }
        val initFile = File(rootfs, "sbin/linuxdroid-init")
        initFile.writeText(GuestInit.SCRIPT_CONTENT)
        initFile.setReadable(true)
        initFile.setExecutable(true)
        return rootfs
    }

    private fun createExecutableFile(name: String): File {
        val file = tempFolder.newFile(name)
        file.setReadable(true)
        file.setExecutable(true)
        return file
    }

    @Test
    fun `HostPreboot throws HOST_PREBOOT_ROOTFS_INVALID when rootfs directory does not exist`() {
        val proot = createExecutableFile("proot")
        val rootfs = File(tempFolder.root, "non-existent-rootfs")
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("test-env"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
        )

        val err = assertThrows(PrebootError::class.java) {
            preboot.prepare(spec, proot, null, rootfs, tempFolder.newFolder("tmp"))
        }

        assertThat(err.code).isEqualTo(PrebootErrorCode.HOST_PREBOOT_ROOTFS_INVALID)
        assertThat(err.message).contains("HOST_PREBOOT_ROOTFS_INVALID")
    }

    @Test
    fun `HostPreboot throws HOST_PREBOOT_ROOTFS_INVALID when rootfs is missing essential Linux directories`() {
        val proot = createExecutableFile("proot")
        val rootfs = tempFolder.newFolder("incomplete-rootfs") // missing etc, usr, etc.
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("test-env"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
        )

        val err = assertThrows(PrebootError::class.java) {
            preboot.prepare(spec, proot, null, rootfs, tempFolder.newFolder("tmp"))
        }

        assertThat(err.code).isEqualTo(PrebootErrorCode.HOST_PREBOOT_ROOTFS_INVALID)
        assertThat(err.detail).contains("missing")
    }

    @Test
    fun `HostPreboot throws HOST_PREBOOT_PROOT_MISSING when proot binary is missing or non-executable`() {
        val rootfs = createValidRootfs()
        val nonExistentProot = File(tempFolder.root, "missing-proot")
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("test-env"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
        )

        val err = assertThrows(PrebootError::class.java) {
            preboot.prepare(spec, nonExistentProot, null, rootfs, tempFolder.newFolder("tmp"))
        }

        assertThat(err.code).isEqualTo(PrebootErrorCode.HOST_PREBOOT_PROOT_MISSING)
    }

    @Test
    fun `HostPreboot throws HOST_PREBOOT_INIT_MISSING when linuxdroid-init is missing from guest sbin`() {
        val rootfs = createValidRootfs()
        val initFile = File(rootfs, "sbin/linuxdroid-init")
        initFile.delete() // remove init

        val proot = createExecutableFile("proot")
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("test-env"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
        )

        val err = assertThrows(PrebootError::class.java) {
            preboot.prepare(spec, proot, null, rootfs, tempFolder.newFolder("tmp"))
        }

        assertThat(err.code).isEqualTo(PrebootErrorCode.HOST_PREBOOT_INIT_MISSING)
        assertThat(err.detail).contains("/sbin/linuxdroid-init")
    }

    @Test
    fun `HostPreboot throws HOST_PREBOOT_INIT_NOT_EXECUTABLE when init lacks execute permission`() {
        val rootfs = createValidRootfs()
        val initFile = File(rootfs, "sbin/linuxdroid-init")
        initFile.setExecutable(false)

        val proot = createExecutableFile("proot")
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("test-env"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
        )

        // Note: in local environments where root can execute anything, test if setExecutable(false) takes effect
        if (!initFile.canExecute()) {
            val err = assertThrows(PrebootError::class.java) {
                preboot.prepare(spec, proot, null, rootfs, tempFolder.newFolder("tmp"))
            }
            assertThat(err.code).isEqualTo(PrebootErrorCode.HOST_PREBOOT_INIT_NOT_EXECUTABLE)
        }
    }

    @Test
    fun `HostPreboot produces valid launch plan and handoff to sbin linuxdroid-init preserving structured arguments`() {
        val rootfs = createValidRootfs()
        val proot = createExecutableFile("proot")
        val tmpDir = tempFolder.newFolder("tmp")
        val logFile = File(tempFolder.newFolder("logs"), "proot.log")

        val rawCommand = listOf("apt", "install", "nano with space", "param=1")
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("valid-env"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            workingDirectory = "/root",
            command = rawCommand,
            environmentVariables = mapOf(
                "EXTRA_VAR" to "test-val",
                "LD_PRELOAD" to "/bad/android/lib.so", // must be stripped
                "LD_LIBRARY_PATH" to "/system/lib64", // must be stripped
                "ANDROID_ROOT" to "/system", // must be stripped
            ),
            logFilePath = logFile.absolutePath,
        )

        val plan = preboot.prepare(spec, proot, null, rootfs, tmpDir, logFile)

        // 1. Host environment verification
        assertThat(plan.environment).containsKey("PROOT_TMP_DIR")
        assertThat(plan.environment["PROOT_TMP_DIR"]).isEqualTo(tmpDir.absolutePath)
        assertThat(plan.environment["PROOT_LOG_FILE"]).isEqualTo(logFile.absolutePath)
        assertThat(plan.environment["EXTRA_VAR"]).isEqualTo("test-val")
        // Stripped host-leaking variables
        assertThat(plan.environment).doesNotContainKey("LD_PRELOAD")
        assertThat(plan.environment).doesNotContainKey("LD_LIBRARY_PATH")
        assertThat(plan.environment).doesNotContainKey("ANDROID_ROOT")

        // 2. Command handover verification
        val cmd = plan.commandLine
        assertThat(cmd.first()).isEqualTo(proot.absolutePath)
        assertThat(cmd).contains("-r")
        assertThat(cmd[cmd.indexOf("-r") + 1]).isEqualTo(rootfs.absolutePath)
        assertThat(cmd).contains("-w")
        assertThat(cmd[cmd.indexOf("-w") + 1]).isEqualTo("/root")

        // Entrypoint handoff must be /sbin/linuxdroid-init followed by the structured arguments
        val initIndex = cmd.indexOf("/sbin/linuxdroid-init")
        assertThat(initIndex).isGreaterThan(-1)
        val argsAfterInit = cmd.subList(initIndex + 1, cmd.size)
        assertThat(argsAfterInit).containsExactly("apt", "install", "nano with space", "param=1").inOrder()
    }
}

