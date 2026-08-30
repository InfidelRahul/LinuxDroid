package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.FilesystemError
import com.linuxdroid.core.model.RuntimeError
import com.linuxdroid.core.model.RuntimeSpec
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeValidatorTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val validator = RuntimeValidator()

    @Test
    fun `validate passes for valid rootfs directory and command`() {
        val rootfs = tmpFolder.newFolder("rootfs")
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("valid-env"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
        )

        validator.validate(spec)
    }

    @Test
    fun `validate throws FilesystemError when rootfs does not exist`() {
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("missing-rootfs"),
            rootfsPath = "/nonexistent/rootfs/path",
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
        )

        assertThrows(FilesystemError::class.java) {
            validator.validate(spec)
        }
    }

    @Test
    fun `validate throws RuntimeError when custom proot path does not exist`() {
        val rootfs = tmpFolder.newFolder("rootfs-custom-proot")
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("custom-proot-test"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
            customProotPath = "/nonexistent/proot",
        )

        assertThrows(RuntimeError::class.java) {
            validator.validate(spec)
        }
    }

    @Test
    fun `resolveExecutableInRootfs finds binary in rootfs`() {
        val rootfs = tmpFolder.newFolder("rootfs-bin")
        val usrBin = File(rootfs, "usr/bin").apply { mkdirs() }
        val bashFile = File(usrBin, "bash").apply { createNewFile() }

        val foundDirect = validator.resolveExecutableInRootfs(rootfs, "/usr/bin/bash")
        assertThat(foundDirect?.absolutePath).isEqualTo(bashFile.absolutePath)

        val foundMergedUsr = validator.resolveExecutableInRootfs(rootfs, "/bin/bash")
        assertThat(foundMergedUsr?.absolutePath).isEqualTo(bashFile.absolutePath)

        val foundRelative = validator.resolveExecutableInRootfs(rootfs, "bash")
        assertThat(foundRelative?.absolutePath).isEqualTo(bashFile.absolutePath)
    }

    @Test
    fun `resolveShell selects existing shell or falls back to standard shell`() {
        val rootfs = tmpFolder.newFolder("rootfs-shell")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        File(bin, "sh").createNewFile()

        // /bin/bash does not exist in rootfs, but /bin/sh exists
        val shell = validator.resolveShell(rootfs, "/bin/bash")
        assertThat(shell).isEqualTo("/bin/sh")

        // Once /bin/bash is created, it is preferred
        File(bin, "bash").createNewFile()
        val resolvedBash = validator.resolveShell(rootfs, "/bin/bash")
        assertThat(resolvedBash).isEqualTo("/bin/bash")
    }
}

