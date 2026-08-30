package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class RuntimeSpecAndCommandBuilderTest {

    @Test
    fun `ProotCommandBuilder produces deterministic argument list from RuntimeSpec`() {
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("test-env"),
            rootfsPath = "/data/data/com.linuxdroid/files/environments/test-env/rootfs",
            architecture = Architecture.ARM64,
            workingDirectory = "/home/user",
            command = listOf("/bin/sh", "-c", "echo hello"),
            bindings = listOf(
                RuntimeBinding("/dev", "/dev"),
                RuntimeBinding("/proc", "/proc"),
                RuntimeBinding("/sys", "/sys"),
                RuntimeBinding("/tmp/host-tmp", "/tmp"),
            ),
            sharedStorageEnabled = false,
        )

        val builder = ProotCommandBuilder()
        val cmd = builder.build(spec, File("/data/data/com.linuxdroid/lib/libproot.so"))

        assertThat(cmd).containsExactly(
            "/data/data/com.linuxdroid/lib/libproot.so",
            "-0",
            "--kill-on-exit",
            "--link2symlink",
            "-r",
            "/data/data/com.linuxdroid/files/environments/test-env/rootfs",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "/tmp/host-tmp:/tmp",
            "-w",
            "/home/user",
            "/bin/sh",
            "-c",
            "echo hello"
        ).inOrder()
    }

    @Test
    fun `ProotCommandBuilder does not discover Android storage`() {
        // G02: the command builder must be a pure RuntimeSpec -> argv translator.
        // It must NOT perform Android shared-storage discovery; the shared
        // storage binding can only appear in the argv if it is explicitly part
        // of spec.bindings.
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("storage-env"),
            rootfsPath = "/data/user/0/com.linuxdroid/app/files/environments/storage-env/rootfs",
            architecture = Architecture.ARM64,
            workingDirectory = "/",
            command = listOf("/bin/sh"),
            bindings = listOf(
                RuntimeBinding("/tmp/host-tmp", "/tmp"),
            ),
            sharedStorageEnabled = true,
        )

        val builder = ProotCommandBuilder()
        val cmd = builder.build(spec, File("/data/user/0/com.linuxdroid/app/runtime/proot"))

        // The Android shared directory is never written by the builder.
        assertThat(cmd.asSequence().any { it.contains("/storage/emulated") }).isFalse()
        assertThat(cmd.asSequence().any { it.contains("/home/user/Android") }).isFalse()
        // The only binding rendered is the explicit /tmp binding.
        assertThat(cmd).contains("/tmp/host-tmp:/tmp")
        assertThat(cmd).containsExactly(
            "/data/user/0/com.linuxdroid/app/runtime/proot",
            "-0",
            "--kill-on-exit",
            "--link2symlink",
            "-r",
            "/data/user/0/com.linuxdroid/app/files/environments/storage-env/rootfs",
            "-b",
            "/tmp/host-tmp:/tmp",
            "-w",
            "/",
            "/bin/sh",
        ).inOrder()
    }

    @Test
    fun `RuntimeSpec fromEnvironment sets up expected default bindings and environment`() {
        val env = Environment(
            metadata = EnvironmentMetadata(
                id = EnvironmentId("debian-arm64"),
                name = "Debian ARM64",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            rootfsPath = "/test/rootfs",
            metadataPath = "/test/metadata",
        )

        val spec = RuntimeSpec.fromEnvironment(
            environment = env,
            command = listOf("/bin/bash"),
            tmpDirPath = "/test/tmp",
        )

        assertThat(spec.environmentId).isEqualTo(EnvironmentId("debian-arm64"))
        assertThat(spec.rootfsPath).isEqualTo("/test/rootfs")
        assertThat(spec.command).containsExactly("/bin/bash")
        assertThat(spec.environmentVariables["PATH"]).contains("/usr/bin")
        assertThat(spec.environmentVariables["TERM"]).isEqualTo("xterm-256color")
        assertThat(spec.environmentVariables["HOME"]).isEqualTo("/home/user")
        assertThat(spec.environmentVariables["USER"]).isEqualTo("user")
        assertThat(spec.environmentVariables["LOGNAME"]).isEqualTo("user")
        assertThat(spec.environmentVariables["SHELL"]).isEqualTo("/bin/bash")
        assertThat(spec.environmentVariables["LANG"]).isEqualTo("C.UTF-8")
        assertThat(spec.environmentVariables["TMPDIR"]).isEqualTo("/tmp")
        assertThat(spec.environmentVariables["PWD"]).isEqualTo("/home/user")
    }

    @Test
    fun `RuntimeSpec fromEnvironment respects custom user and shell configuration`() {
        val env = Environment(
            metadata = EnvironmentMetadata(
                id = EnvironmentId("ubuntu-custom"),
                name = "Ubuntu Custom",
                distribution = Distribution.UBUNTU,
                architecture = Architecture.ARM64,
            ),
            rootfsPath = "/test/rootfs",
            metadataPath = "/test/metadata",
            configuration = EnvironmentConfiguration(
                linuxUser = "developer",
                homeDir = "/home/developer",
                shell = "/usr/bin/zsh",
            ),
        )

        val spec = RuntimeSpec.fromEnvironment(
            environment = env,
            command = listOf("/usr/bin/zsh", "-l"),
            workingDirectory = "/home/developer/projects",
        )

        assertThat(spec.user).isEqualTo("developer")
        assertThat(spec.workingDirectory).isEqualTo("/home/developer/projects")
        assertThat(spec.command).containsExactly("/usr/bin/zsh", "-l").inOrder()
        assertThat(spec.environmentVariables["USER"]).isEqualTo("developer")
        assertThat(spec.environmentVariables["LOGNAME"]).isEqualTo("developer")
        assertThat(spec.environmentVariables["HOME"]).isEqualTo("/home/developer")
        assertThat(spec.environmentVariables["SHELL"]).isEqualTo("/usr/bin/zsh")
        assertThat(spec.environmentVariables["PWD"]).isEqualTo("/home/developer/projects")
    }

    @Test
    fun `ProotCommandBuilder sets rootfs with -r before bindings and only binds declared paths`() {
        val builder = ProotCommandBuilder()
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("debian-test"),
            rootfsPath = "/data/user/0/com.linuxdroid/files/environments/debian/rootfs",
            architecture = Architecture.ARM64,
            command = listOf("/bin/bash", "-l"),
            bindings = listOf(
                RuntimeBinding("/dev", "/dev"),
                RuntimeBinding("/proc", "/proc"),
                RuntimeBinding("/sys", "/sys"),
                RuntimeBinding("/data/user/0/com.linuxdroid/cache/tmp", "/tmp"),
            ),
            workingDirectory = "/home/user",
        )

        val cmd = builder.build(spec, File("/data/user/0/com.linuxdroid/files/proot"))

        assertThat(cmd).containsExactly(
            "/data/user/0/com.linuxdroid/files/proot",
            "-0",
            "--kill-on-exit",
            "--link2symlink",
            "-r",
            "/data/user/0/com.linuxdroid/files/environments/debian/rootfs",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "/data/user/0/com.linuxdroid/cache/tmp:/tmp",
            "-w",
            "/home/user",
            "/bin/bash",
            "-l",
        ).inOrder()

        // Verify host Android root paths (/apex, /vendor, /product, /data, /mnt, /system) are not bound
        val boundPaths = spec.bindings.map { it.guestPath }
        assertThat(boundPaths).doesNotContain("/apex")
        assertThat(boundPaths).doesNotContain("/vendor")
        assertThat(boundPaths).doesNotContain("/product")
        assertThat(boundPaths).doesNotContain("/data")
        assertThat(boundPaths).doesNotContain("/system")
        assertThat(boundPaths).doesNotContain("/mnt")
    }
}
