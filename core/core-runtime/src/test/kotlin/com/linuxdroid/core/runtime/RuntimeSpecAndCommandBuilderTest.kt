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
    }

    @Test
    fun `RuntimeValidator rejects blank or non-existent rootfs directory`() {
        val validator = RuntimeValidator()
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("invalid-env"),
            rootfsPath = "/non/existent/path/for/rootfs/test",
            architecture = Architecture.ARM64,
            command = listOf("/bin/sh"),
        )

        var failed = false
        try {
            validator.validate(spec)
        } catch (e: FilesystemError) {
            failed = true
        }
        assertThat(failed).isTrue()
    }
}
