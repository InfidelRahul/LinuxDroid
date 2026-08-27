package com.linuxdroid.core.display

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.DisplayConfig
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DisplayManagerTest {

    @Test
    fun `applyConfig updates current display configuration`() = runTest {
        val manager = DefaultDisplayManager()
        val config = DisplayConfig(widthPx = 2560, heightPx = 1600, dpi = 400)

        manager.applyConfig(config)
        assertThat(manager.getCurrentConfig().widthPx).isEqualTo(2560)
        assertThat(manager.getCurrentConfig().heightPx).isEqualTo(1600)
        assertThat(manager.getCurrentConfig().dpi).isEqualTo(400)
    }

    @Test
    fun `onConfigurationChanged updates width height and DPI`() = runTest {
        val manager = DefaultDisplayManager()
        manager.onConfigurationChanged(1280, 720, 240)

        assertThat(manager.getCurrentConfig().widthPx).isEqualTo(1280)
        assertThat(manager.getCurrentConfig().heightPx).isEqualTo(720)
        assertThat(manager.getCurrentConfig().dpi).isEqualTo(240)
    }
}
