package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Format, geometry and stride contracts.
 *
 * The channel-order mapping matters: DRM's XRGB8888/ARGB8888 are B,G,R,A in
 * memory while Android's RGBA_8888 is R,G,B,A, so a wrong mapping silently
 * swaps red and blue rather than failing.
 */
class FramePixelFormatTest {

    @Test
    fun `drm argb maps to bgra because drm names are little-endian words`() {
        assertThat(FramePixelFormat.fromDrmFourcc(FramePixelFormat.DRM_FORMAT_ARGB8888))
            .isEqualTo(FramePixelFormat.BGRA_8888)
        assertThat(FramePixelFormat.fromDrmFourcc(FramePixelFormat.DRM_FORMAT_XRGB8888))
            .isEqualTo(FramePixelFormat.BGRX_8888)
    }

    @Test
    fun `drm abgr maps to rgba which needs no channel swap on android`() {
        assertThat(FramePixelFormat.fromDrmFourcc(FramePixelFormat.DRM_FORMAT_ABGR8888))
            .isEqualTo(FramePixelFormat.RGBA_8888)
        assertThat(FramePixelFormat.fromDrmFourcc(FramePixelFormat.DRM_FORMAT_XBGR8888))
            .isEqualTo(FramePixelFormat.RGBX_8888)
    }

    @Test
    fun `unsupported fourcc is rejected rather than guessed`() {
        // 'YU12' — a planar YUV format the presentation path cannot handle.
        assertThat(FramePixelFormat.fromDrmFourcc(0x32315559)).isNull()
        assertThat(FramePixelFormat.fromDrmFourcc(0)).isNull()
    }

    @Test
    fun `only the alpha formats report alpha`() {
        assertThat(FramePixelFormat.RGBA_8888.hasAlpha).isTrue()
        assertThat(FramePixelFormat.BGRA_8888.hasAlpha).isTrue()
        assertThat(FramePixelFormat.RGBX_8888.hasAlpha).isFalse()
        assertThat(FramePixelFormat.BGRX_8888.hasAlpha).isFalse()
    }
}

class FrameDescriptorTest {

    @Test
    fun `stride larger than the row width is preserved not normalised`() {
        val d = FrameDescriptor(100, 50, 512, FramePixelFormat.BGRA_8888)
        assertThat(d.strideBytes).isEqualTo(512)
        assertThat(d.isTightlyPacked).isFalse()
        // Size must follow the real stride, not width * bpp.
        assertThat(d.sizeBytes).isEqualTo(512 * 50)
    }

    @Test
    fun `tightly packed frames are detected`() {
        val d = FrameDescriptor(100, 50, 400, FramePixelFormat.RGBA_8888)
        assertThat(d.isTightlyPacked).isTrue()
        assertThat(d.sizeBytes).isEqualTo(400 * 50)
    }

    @Test
    fun `stride smaller than one row is rejected`() {
        val e = runCatching { FrameDescriptor(100, 50, 399, FramePixelFormat.RGBA_8888) }
            .exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("Stride")
    }

    @Test
    fun `zero and negative dimensions are rejected`() {
        assertThat(
            runCatching { FrameDescriptor(0, 50, 400, FramePixelFormat.RGBA_8888) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { FrameDescriptor(100, 0, 400, FramePixelFormat.RGBA_8888) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { FrameDescriptor(-1, 50, 400, FramePixelFormat.RGBA_8888) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `frame buffer must be large enough for the declared layout`() {
        val d = FrameDescriptor(4, 4, 32, FramePixelFormat.BGRA_8888)
        val e = runCatching { CompositorFrame(d, ByteArray(d.sizeBytes - 1), 0) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        // A buffer larger than needed is fine: the source reuses one buffer.
        assertThat(CompositorFrame(d, ByteArray(d.sizeBytes + 64), 0).descriptor).isEqualTo(d)
    }
}

class PresentationFailureTest {

    @Test
    fun `describe includes kind message and detail`() {
        val text = PresentationFailure(
            PresentationFailureKind.SURFACE_DESTROYED,
            "surface went away",
            "generation=3",
        ).describe()
        assertThat(text).contains("SURFACE_DESTROYED")
        assertThat(text).contains("surface went away")
        assertThat(text).contains("generation=3")
    }
}
