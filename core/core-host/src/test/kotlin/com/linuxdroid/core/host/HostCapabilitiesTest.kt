package com.linuxdroid.core.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class HostCapabilitiesTest {

    @Test
    fun `AndroidHostGraphics default metrics are initialized`() {
        val graphics = AndroidHostGraphics()
        graphics.setDisplayMetrics(1920, 1080, 320, 60.0f)
        assertThat(graphics.getDisplayWidth()).isEqualTo(1920)
        assertThat(graphics.getDisplayHeight()).isEqualTo(1080)
        assertThat(graphics.getDisplayDpi()).isEqualTo(320)
        assertThat(graphics.isSurfaceReady()).isFalse()
    }

    @Test
    fun `AndroidHostStorage reports authorization and path correctly`() {
        val testDir = File("/tmp/linuxdroid_test_shared")
        testDir.mkdirs()
        try {
            val storage = AndroidHostStorage(testDir)
            assertThat(storage.getSharedDirectoryPath()).isEqualTo(testDir.absolutePath)
            assertThat(storage.verifyAccess()).isTrue()
            assertThat(storage.isAuthorized()).isTrue()
        } finally {
            testDir.deleteRecursively()
        }
    }
}

