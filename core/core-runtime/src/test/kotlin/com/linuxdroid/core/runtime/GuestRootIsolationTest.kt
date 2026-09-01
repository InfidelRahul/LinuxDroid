package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.*
import org.junit.Test
import java.io.File

import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Phase 1: Guest Root Filesystem & Environment Isolation Tests.
 *
 * Verifies that the guest environment operates with the rootfs as `/`,
 * isolates host Android environment variables, and provides coherent
 * Linux userspace paths.
 */
class GuestRootIsolationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val commandBuilder = ProotCommandBuilder()
    private val launcher = RuntimeLauncher(commandBuilder)

    @Test
    fun `guest rootfs is established as root slash using PRoot -r flag`() {
        val rootfsPath = "/data/user/0/com.linuxdroid.app/files/environments/env-1/rootfs"
        val spec = RuntimeSpec(
            environmentId = EnvironmentId("env-1"),
            rootfsPath = rootfsPath,
            architecture = Architecture.ARM64,
            workingDirectory = "/root",
            command = listOf("/bin/sh"),
            bindings = listOf(
                RuntimeBinding("/dev", "/dev"),
                RuntimeBinding("/proc", "/proc"),
                RuntimeBinding("/sys", "/sys"),
                RuntimeBinding("/tmp/host-tmp", "/tmp"),
            ),
        )

        val prootBin = File("/data/user/0/com.linuxdroid.app/runtime/arm64-v8a/proot")
        val cmd = commandBuilder.build(spec, prootBin)

        // 1. Rootfs argument must be passed to -r
        val rIndex = cmd.indexOf("-r")
        assertThat(rIndex).isGreaterThan(-1)
        assertThat(cmd[rIndex + 1]).isEqualTo(rootfsPath)

        // 2. Working directory must be a guest path passed to -w
        val wIndex = cmd.indexOf("-w")
        assertThat(wIndex).isGreaterThan(-1)
        assertThat(cmd[wIndex + 1]).isEqualTo("/root")

        // 3. Fake root ID flag -0 must be present
        assertThat(cmd).contains("-0")
        assertThat(cmd).contains("--kill-on-exit")
        assertThat(cmd).contains("--link2symlink")
    }

    @Test
    fun `host Android system directories are not exposed to guest filesystem`() {
        val rootfsPath = "/data/user/0/com.linuxdroid.app/files/environments/env-1/rootfs"
        val spec = RuntimeSpec.fromEnvironment(
            environment = Environment(
                metadata = EnvironmentMetadata(
                    id = EnvironmentId("debian-1"),
                    name = "Debian",
                    distribution = Distribution.DEBIAN,
                    architecture = Architecture.ARM64,
                ),
                rootfsPath = rootfsPath,
                metadataPath = "/meta",
            ),
            command = listOf("/bin/bash"),
        )

        val guestMountPoints = spec.bindings.map { it.guestPath }

        // Host Android paths must never be exposed as root or top-level mounts
        assertThat(guestMountPoints).doesNotContain("/system")
        assertThat(guestMountPoints).doesNotContain("/vendor")
        assertThat(guestMountPoints).doesNotContain("/apex")
        assertThat(guestMountPoints).doesNotContain("/data")
        assertThat(guestMountPoints).doesNotContain("/data/user/0/com.linuxdroid.app")
    }

    @Test
    fun `guest environment variables provide clean Linux userspace configuration`() {
        val rootfsPath = "/data/user/0/com.linuxdroid.app/files/environments/env-1/rootfs"
        val spec = RuntimeSpec.fromEnvironment(
            environment = Environment(
                metadata = EnvironmentMetadata(
                    id = EnvironmentId("debian-1"),
                    name = "Debian",
                    distribution = Distribution.DEBIAN,
                    architecture = Architecture.ARM64,
                ),
                rootfsPath = rootfsPath,
                metadataPath = "/meta",
                configuration = EnvironmentConfiguration(
                    linuxUser = "root",
                    homeDir = "/root",
                    shell = "/bin/bash",
                ),
            ),
            command = listOf("/bin/bash", "-l"),
            workingDirectory = "/root",
        )

        val env = spec.environmentVariables

        assertThat(env["PATH"]).isEqualTo("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        assertThat(env["HOME"]).isEqualTo("/root")
        assertThat(env["USER"]).isEqualTo("root")
        assertThat(env["LOGNAME"]).isEqualTo("root")
        assertThat(env["SHELL"]).isEqualTo("/bin/bash")
        assertThat(env["LANG"]).isEqualTo("C.UTF-8")
        assertThat(env["LC_ALL"]).isEqualTo("C.UTF-8")
        assertThat(env["TMPDIR"]).isEqualTo("/tmp")
        assertThat(env["TERM"]).isEqualTo("xterm-256color")
        assertThat(env["PWD"]).isEqualTo("/root")

        // No Android system paths in PATH
        assertThat(env["PATH"]).doesNotContain("/system/bin")
        assertThat(env["PATH"]).doesNotContain("/vendor/bin")
        assertThat(env["PATH"]).doesNotContain("/apex")
    }

    @Test
    fun `isolated environment builder strips all host Bionic and Android variables`() {
        val rootfs = tempFolder.newFolder("rootfs")
        val tmpDir = tempFolder.newFolder("tmp")
        val loader = tempFolder.newFile("loader")
        val logFile = tempFolder.newFile("console.log")

        val spec = RuntimeSpec(
            environmentId = EnvironmentId("test-env"),
            rootfsPath = rootfs.absolutePath,
            architecture = Architecture.ARM64,
            workingDirectory = "/",
            command = listOf("/bin/sh"),
            environmentVariables = mapOf(
                "PATH" to "/usr/bin:/bin",
                "HOME" to "/root",
                "LD_PRELOAD" to "/system/lib64/libandroid.so", // Should be stripped
                "LD_LIBRARY_PATH" to "/system/lib64", // Should be stripped
                "CUSTOM_VAR" to "value123",
            ),
        )

        val isolatedEnv = launcher.buildIsolatedEnvironment(
            spec = spec,
            loader = loader,
            tmpDir = tmpDir,
            logFile = logFile,
        )

        // Verifies host linker overrides are stripped
        assertThat(isolatedEnv).doesNotContainKey("LD_PRELOAD")
        assertThat(isolatedEnv).doesNotContainKey("LD_LIBRARY_PATH")

        // Verifies PRoot engine controls are properly configured
        assertThat(isolatedEnv["PROOT_TMP_DIR"]).isEqualTo(tmpDir.absolutePath)
        assertThat(isolatedEnv["PROOT_LOADER"]).isEqualTo(loader.absolutePath)
        assertThat(isolatedEnv["PROOT_LOG_FILE"]).isEqualTo(logFile.absolutePath)
        assertThat(isolatedEnv["PROOT_VERBOSE"]).isEqualTo("9")
        assertThat(isolatedEnv["PROOT_NO_SECCOMP"]).isEqualTo("1")

        // Verifies guest variables are preserved
        assertThat(isolatedEnv["PATH"]).isEqualTo("/usr/bin:/bin")
        assertThat(isolatedEnv["HOME"]).isEqualTo("/root")
        assertThat(isolatedEnv["CUSTOM_VAR"]).isEqualTo("value123")
    }

    @Test
    fun `working directory defaults and normalizes to guest root-relative path`() {
        val rootfsPath = "/data/user/0/com.linuxdroid.app/files/environments/env-1/rootfs"

        // Default configured home should be used when working directory is blank
        val specBlank = RuntimeSpec.fromEnvironment(
            environment = Environment(
                metadata = EnvironmentMetadata(
                    id = EnvironmentId("debian-1"),
                    name = "Debian",
                    distribution = Distribution.DEBIAN,
                    architecture = Architecture.ARM64,
                ),
                rootfsPath = rootfsPath,
                metadataPath = "/meta",
            ),
            workingDirectory = "",
        )
        assertThat(specBlank.workingDirectory).isEqualTo("/home/user")

        // When configured as root user, should default to /root
        val specRoot = RuntimeSpec.fromEnvironment(
            environment = Environment(
                metadata = EnvironmentMetadata(
                    id = EnvironmentId("debian-root"),
                    name = "Debian Root",
                    distribution = Distribution.DEBIAN,
                    architecture = Architecture.ARM64,
                ),
                rootfsPath = rootfsPath,
                metadataPath = "/meta",
                configuration = EnvironmentConfiguration(
                    linuxUser = "root",
                    homeDir = "/root",
                ),
            ),
            workingDirectory = "",
        )
        assertThat(specRoot.workingDirectory).isEqualTo("/root")

        // Relative path should normalize to leading slash
        val specRelative = RuntimeSpec.fromEnvironment(
            environment = Environment(
                metadata = EnvironmentMetadata(
                    id = EnvironmentId("debian-1"),
                    name = "Debian",
                    distribution = Distribution.DEBIAN,
                    architecture = Architecture.ARM64,
                ),
                rootfsPath = rootfsPath,
                metadataPath = "/meta",
            ),
            workingDirectory = "var/log",
        )
        assertThat(specRelative.workingDirectory).isEqualTo("/var/log")
    }
}
