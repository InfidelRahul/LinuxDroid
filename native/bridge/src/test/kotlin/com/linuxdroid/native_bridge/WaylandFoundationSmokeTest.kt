package com.linuxdroid.native_bridge

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

class WaylandFoundationSmokeTest {

    private val rootDir: File
        get() {
            var dir = File(".").canonicalFile
            while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile!!
            }
            return dir
        }

    @Test
    fun verifyRequiredArm64SharedLibrariesExistAndAreValidElf64() {
        val jniLibsDir = File(rootDir, "app/src/main/jniLibs/arm64-v8a")
        assertThat(jniLibsDir.exists()).isTrue()

        val westonLib = jniLibsDir.listFiles()?.firstOrNull { it.name.matches(Regex("libweston-\\d+\\.so")) }
        assertWithMessage("Expected libweston-*.so to be present in $jniLibsDir").that(westonLib).isNotNull()

        val requiredLibraries = listOf(
            westonLib!!.name,
            "libwayland-server.so",
            "libwayland-client.so",
            "libwayland-cursor.so",
            "libpixman-1.so",
            "libxkbcommon.so",
            "libdrm.so",
            "libffi.so"
        )

        for (libName in requiredLibraries) {
            val libFile = File(jniLibsDir, libName)
            assertWithMessage("Expected $libName to be present in $jniLibsDir").that(libFile.exists()).isTrue()
            assertWithMessage("Expected $libName to be non-empty").that(libFile.length()).isGreaterThan(4096L)

            // Validate ELF magic: \x7F 'E' 'L' 'F' and 64-bit (class 2)
            val header = ByteArray(5)
            libFile.inputStream().use { it.read(header) }
            assertThat(header[0].toInt()).isEqualTo(0x7F)
            assertThat(header[1].toInt().toChar()).isEqualTo('E')
            assertThat(header[2].toInt().toChar()).isEqualTo('L')
            assertThat(header[3].toInt().toChar()).isEqualTo('F')
            assertThat(header[4].toInt()).isEqualTo(2) // ELFCLASS64
        }
    }

    @Test
    fun verifyXkbConfigurationAssetsInstalled() {
        val xkbAssetsDir = File(rootDir, "app/src/main/assets/xkb")
        assertThat(xkbAssetsDir.exists()).isTrue()
        assertThat(File(xkbAssetsDir, "rules").exists()).isTrue()
        assertThat(File(xkbAssetsDir, "symbols").exists()).isTrue()
        assertThat(File(xkbAssetsDir, "types").exists()).isTrue()
        assertThat(File(xkbAssetsDir, "keycodes").exists()).isTrue()
    }

    @Test
    fun verifyDependencyManifestIntegrity() {
        val manifestFile = File(rootDir, "native/weston/dependencies.json")
        assertThat(manifestFile.exists()).isTrue()
        val content = manifestFile.readText()
        assertThat(content).contains("\"name\": \"weston\"")
        assertThat(content).contains("\"branch\": \"main\"")
        assertThat(content).contains("\"release\": \"1.26.0\"")
        assertThat(content).contains("\"release\": \"1.49\"")
        assertThat(content).contains("\"release\": \"0.46.4\"")
        assertThat(content).contains("\"release\": \"xkbcommon-1.9.2\"")
        assertThat(content).contains("\"release\": \"xkeyboard-config-2.48\"")
        assertThat(content).contains("\"target_abi\": \"arm64-v8a\"")
    }

    @Test
    fun verifyCMakeLinksWaylandFoundation() {
        val cmakeFile = File(rootDir, "native/bridge/src/main/cpp/CMakeLists.txt")
        assertThat(cmakeFile.exists()).isTrue()
        val content = cmakeFile.readText()
        assertThat(content).contains("wayland-server")
        assertThat(content).contains("weston")
        assertThat(content).contains("pixman-1")
        assertThat(content).contains("xkbcommon")
        assertThat(content).contains("wayland_foundation_test.cpp")
    }
}
