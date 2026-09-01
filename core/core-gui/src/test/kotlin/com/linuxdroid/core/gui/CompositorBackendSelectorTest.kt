package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultCompositorBackendSelectorTest {

    private val selector = DefaultCompositorBackendSelector()

    @Test
    fun `prefers the android surface backend when acceleration was probed`() {
        val selection = selector.select(Capabilities.accelerated)

        assertThat(selection).isNotNull()
        assertThat(selection!!.backend).isEqualTo(CompositorBackend.ANDROID_SURFACE)
        assertThat(selection.hardwareAccelerated).isTrue()
        assertThat(selection.rationale).contains("EGL")
    }

    @Test
    fun `falls back to software when only shm was probed`() {
        val selection = selector.select(Capabilities.softwareOnly)

        assertThat(selection!!.backend).isEqualTo(CompositorBackend.SOFTWARE)
        assertThat(selection.hardwareAccelerated).isFalse()
        assertThat(selection.rejected).containsKey(CompositorBackend.ANDROID_SURFACE)
    }

    @Test
    fun `returns null when no output surface exists`() {
        val selection = selector.select(Capabilities.none)

        assertThat(selection).isNull()
    }

    @Test
    fun `unprobed capabilities never yield a backend`() {
        assertThat(selector.select(GraphicsCapabilities.UNPROBED)).isNull()
    }

    @Test
    fun `headless is only selected when explicitly enabled`() {
        val diagnostics = DefaultCompositorBackendSelector(allowHeadlessFallback = true)

        val selection = diagnostics.select(Capabilities.none)

        assertThat(selection!!.backend).isEqualTo(CompositorBackend.HEADLESS)
        assertThat(selection.hardwareAccelerated).isFalse()
    }

    @Test
    fun `rejection reasons are recorded for diagnostics`() {
        val surfaceOnly = Capabilities.of(GraphicsCapability.ANDROID_SURFACE)

        val selection = selector.select(surfaceOnly)

        assertThat(selection).isNull()
    }

    @Test
    fun `drm is never a selectable backend`() {
        assertThat(CompositorBackend.entries.map { it.name }).doesNotContain("DRM")
        assertThat(CompositorBackend.entries.map { it.name }).doesNotContain("KMS")
    }
}

class WestonConfigTest {

    private val geometry = DisplayGeometry(1080, 2400, 420, 60f)

    @Test
    fun `presenting backends use pixman so frames are cpu-readable for capture`() {
        // The frame reaches Android through weston_output_capture_v1 into a
        // wl_shm buffer, so the finished frame must be in CPU memory. Pixman
        // composites straight there; GL would need a glReadPixels per frame.
        val ini = WestonConfig.render(CompositorBackend.ANDROID_SURFACE, geometry)

        assertThat(ini).contains("renderer=pixman")
        assertThat(ini).contains("mode=1080x2400")
    }

    @Test
    fun `uses pixman for the software backend`() {
        val ini = WestonConfig.render(CompositorBackend.SOFTWARE, geometry)

        assertThat(ini).contains("renderer=pixman")
    }

    @Test
    fun `never configures a drm backend`() {
        CompositorBackend.entries.forEach { backend ->
            assertThat(WestonConfig.backendModule(backend)).doesNotContain("drm")
        }
    }

    @Test
    fun `configures no desktop panel because the shell is a later phase`() {
        val ini = WestonConfig.render(CompositorBackend.SOFTWARE, geometry)

        assertThat(ini).contains("panel-position=none")
    }
}
