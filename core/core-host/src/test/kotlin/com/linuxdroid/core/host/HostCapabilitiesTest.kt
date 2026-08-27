package com.linuxdroid.core.host

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.RuntimeProfile
import com.linuxdroid.core.model.RuntimeProfileType
import org.junit.Test
import java.io.File

class HostCapabilitiesTest {

    @Test
    fun `minimal profile does not require desktop GPU or audio subsystems`() {
        val minimal = RuntimeProfile.minimal()
        assertThat(minimal.type).isEqualTo(RuntimeProfileType.MINIMAL)
        assertThat(minimal.requiresDisplay).isFalse()
        assertThat(minimal.requiresGpu).isFalse()
        assertThat(minimal.requiresAudio).isFalse()
        assertThat(minimal.requiresInput).isFalse()
    }

    @Test
    fun `desktop profile requires display GPU audio input and network`() {
        val desktop = RuntimeProfile.desktop()
        assertThat(desktop.type).isEqualTo(RuntimeProfileType.DESKTOP)
        assertThat(desktop.requiresDisplay).isTrue()
        assertThat(desktop.requiresGpu).isTrue()
        assertThat(desktop.requiresAudio).isTrue()
        assertThat(desktop.requiresInput).isTrue()
        assertThat(desktop.requiresNetwork).isTrue()
    }

    @Test
    fun `AndroidHostStorage reports authorization and path correctly`() {
        val tempDir = File("/tmp/test-shared-dir")
        val storage = AndroidHostStorage(tempDir)
        assertThat(storage.getSharedDirectoryPath()).isEqualTo(tempDir.absolutePath)
    }
}
