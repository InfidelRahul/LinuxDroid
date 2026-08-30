package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * JVM unit tests for the pure, Android-independent parts of
 * [RuntimeAssetsManager] (manifest parsing, version compatibility, checksums).
 *
 * The installer/resolver paths require an Android [android.content.Context]
 * and are verified on-device / via instrumentation, not here.
 */
class RuntimeAssetsManagerTest {

    private val sampleManifest = """
        LinuxDroid-PRoot v0.2.1
        commit:  abc123def456
        ABI:     arm64-v8a
        arch:    aarch64 (ARM64)
        cc:      Clang 18.0.2 (NDK r27b)
        ndk:     r27b
        android: 16+ (API 36)
        sha256:
          proot:    aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899
          loader:   fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210
        built:   2026-08-30T00:00:00Z
    """.trimIndent()

    @Test
    fun `parseManifest extracts version, commit, ABI, and checksums`() {
        val metadata = RuntimeAssetsManager.parseManifest(sampleManifest, "arm64-v8a")

        assertThat(metadata.version).isEqualTo("0.2.1")
        assertThat(metadata.commit).isEqualTo("abc123def456")
        assertThat(metadata.abi).isEqualTo("arm64-v8a")
        assertThat(metadata.prootSha256).isEqualTo("aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899")
        assertThat(metadata.loaderSha256).isEqualTo("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")
        assertThat(metadata.supportsAbi("arm64-v8a")).isTrue()
        assertThat(metadata.supportsAbi("x86_64")).isFalse()
    }

    @Test
    fun `parseManifest handles dev manifest without version to unknown`() {
        val metadata = RuntimeAssetsManager.parseManifest("commit: deadbeef\n", "x86_64")
        assertThat(metadata.version).isEqualTo("unknown")
        assertThat(metadata.commit).isEqualTo("deadbeef")
    }

    @Test
    fun `compareVersions orders versions correctly`() {
        assertThat(RuntimeAssetsManager.compareVersions("0.2.0", "0.1.0") > 0).isTrue()
        assertThat(RuntimeAssetsManager.compareVersions("0.1.0", "0.1.0")).isEqualTo(0)
        assertThat(RuntimeAssetsManager.compareVersions("0.1.0", "0.2.0") < 0).isTrue()
        assertThat(RuntimeAssetsManager.compareVersions("v0.1.0", "0.1.0")).isEqualTo(0)
        assertThat(RuntimeAssetsManager.compareVersions("1.0.0", "0.9.9") > 0).isTrue()
    }

    @Test
    fun `isVersionCompatible accepts newer and unknown but rejects older`() {
        // companion-free: no Android Context needed.
        assertThat(RuntimeAssetsManager.isVersionCompatible("0.2.0")).isTrue()
        assertThat(RuntimeAssetsManager.isVersionCompatible("0.1.0")).isTrue()
        assertThat(RuntimeAssetsManager.isVersionCompatible("unknown")).isTrue()
        assertThat(RuntimeAssetsManager.isVersionCompatible("")).isTrue()
        assertThat(RuntimeAssetsManager.isVersionCompatible("0.0.9")).isFalse()
    }

    @Test
    fun `sha256 computes expected hex digest`() {
        val file = File.createTempFile("runtime-assets-test", ".txt")
        try {
            file.writeText("hello")
            val digest = RuntimeAssetsManager.sha256(file)
            // SHA-256("hello")
            assertThat(digest).isEqualTo(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            )
        } finally {
            file.delete()
        }
    }
}
