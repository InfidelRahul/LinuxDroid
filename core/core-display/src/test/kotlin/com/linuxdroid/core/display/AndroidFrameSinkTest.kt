package com.linuxdroid.core.display

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.gui.CompositorFrame
import com.linuxdroid.core.gui.DisplayGeometry
import com.linuxdroid.core.gui.FrameDescriptor
import com.linuxdroid.core.gui.FramePixelFormat
import com.linuxdroid.core.gui.PresentResult
import com.linuxdroid.core.gui.PresentationFailureKind
import com.linuxdroid.core.gui.SurfaceLifecycle
import com.linuxdroid.core.gui.SurfaceLifecycleState
import com.linuxdroid.core.host.HostGraphics
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Android frame sink behaviour, with the native bridge faked at the
 * [HostGraphics] boundary. The pixel conversion itself is verified natively.
 */
class AndroidFrameSinkTest {

    /** Records what the native layer was asked to do and returns scripted codes. */
    private class FakeHostGraphics(
        var surfaceReady: Boolean = true,
        var configureResult: Boolean = true,
        var presentStatus: Int = AndroidFrameSink.PRESENT_OK,
    ) : HostGraphics {
        var configuredTo: Pair<Int, Int>? = null
        val presentedStrides = mutableListOf<Int>()
        val presentedFormats = mutableListOf<Int>()
        var presentCount = 0
        var throwOnPresent: Throwable? = null

        override fun onSurfaceCreated(surface: android.view.Surface, width: Int, height: Int) = Unit
        override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int, format: Int) = Unit
        override fun onSurfaceDestroyed(surface: android.view.Surface) = Unit
        override fun setDisplayMetrics(widthPx: Int, heightPx: Int, dpi: Int, refreshRate: Float) = Unit
        override fun isSurfaceReady(): Boolean = surfaceReady
        override fun getDisplayWidth(): Int = 1080
        override fun getDisplayHeight(): Int = 2400
        override fun getDisplayDpi(): Int = 420

        override fun configureOutput(widthPx: Int, heightPx: Int): Boolean {
            configuredTo = widthPx to heightPx
            return configureResult
        }

        override fun presentFrame(
            pixels: ByteArray,
            byteCount: Int,
            widthPx: Int,
            heightPx: Int,
            strideBytes: Int,
            sourceFormat: Int,
        ): Int {
            throwOnPresent?.let { throw it }
            presentCount++
            presentedStrides += strideBytes
            presentedFormats += sourceFormat
            return presentStatus
        }
    }

    private val geometry = DisplayGeometry(1080, 2400, 420, 60f)
    private val descriptor = FrameDescriptor(64, 32, 320, FramePixelFormat.BGRX_8888)

    private fun frame() = CompositorFrame(descriptor, ByteArray(descriptor.sizeBytes), 1)

    private fun activeLifecycle() = SurfaceLifecycle().also {
        it.onSurfaceCreated(geometry)
        it.onAttached()
    }

    @Test
    fun `configuring activates the surface and sizes the output to the frame`() = runTest {
        val host = FakeHostGraphics()
        val lifecycle = activeLifecycle()
        val sink = AndroidFrameSink(host, lifecycle) { null }

        val failure = sink.configure(geometry, descriptor)

        assertThat(failure).isNull()
        assertThat(host.configuredTo).isEqualTo(64 to 32)
        assertThat(lifecycle.state.value).isEqualTo(SurfaceLifecycleState.ACTIVE)
    }

    @Test
    fun `configuring without a surface fails`() = runTest {
        val host = FakeHostGraphics(surfaceReady = false)
        val failure = AndroidFrameSink(host, activeLifecycle()) { null }
            .configure(geometry, descriptor)

        assertThat(failure?.kind).isEqualTo(PresentationFailureKind.SURFACE_UNAVAILABLE)
    }

    @Test
    fun `a failed buffer configuration is reported`() = runTest {
        val host = FakeHostGraphics(configureResult = false)
        val failure = AndroidFrameSink(host, activeLifecycle()) { null }
            .configure(geometry, descriptor)

        assertThat(failure?.kind).isEqualTo(PresentationFailureKind.BUFFER_ALLOCATION_FAILED)
    }

    @Test
    fun `the real stride and format are handed to the native layer`() = runTest {
        val host = FakeHostGraphics()
        val lifecycle = activeLifecycle()
        val sink = AndroidFrameSink(host, lifecycle) { null }
        sink.configure(geometry, descriptor)

        assertThat(sink.present(frame())).isEqualTo(PresentResult.Presented)

        // 320, not 64 * 4 = 256.
        assertThat(host.presentedStrides).containsExactly(320)
        assertThat(host.presentedFormats).containsExactly(AndroidFrameSink.SOURCE_BGRX_8888)
    }

    @Test
    fun `every supported format maps to a distinct native ordinal`() {
        val mapped = FramePixelFormat.entries.map { AndroidFrameSink.nativeFormatOf(it) }
        assertThat(mapped).containsExactly(0, 1, 2, 3)
        assertThat(AndroidFrameSink.nativeFormatOf(FramePixelFormat.BGRA_8888))
            .isEqualTo(AndroidFrameSink.SOURCE_BGRA_8888)
    }

    @Test
    fun `presenting is refused while the surface is not active`() = runTest {
        val host = FakeHostGraphics()
        // Attached but never configured, so never activated.
        val sink = AndroidFrameSink(host, activeLifecycle()) { null }

        val result = sink.present(frame())

        assertThat(result).isInstanceOf(PresentResult.Skipped::class.java)
        assertThat(host.presentCount).isEqualTo(0)
    }

    @Test
    fun `a surface lost before the frame is a destroyed failure and updates the lifecycle`() = runTest {
        val host = FakeHostGraphics()
        val lifecycle = activeLifecycle()
        val sink = AndroidFrameSink(host, lifecycle) { null }
        sink.configure(geometry, descriptor)

        // The surface goes away underneath us.
        host.surfaceReady = false
        val result = sink.present(frame())

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.kind)
            .isEqualTo(PresentationFailureKind.SURFACE_DESTROYED)
        // The lifecycle must reflect reality, not a phantom active surface.
        assertThat(lifecycle.state.value).isEqualTo(SurfaceLifecycleState.DESTROYED)
        assertThat(host.presentCount).isEqualTo(0)
    }

    @Test
    fun `native status codes map to specific failures`() = runTest {
        val cases = mapOf(
            AndroidFrameSink.PRESENT_NO_WINDOW to PresentationFailureKind.SURFACE_DESTROYED,
            AndroidFrameSink.PRESENT_LOCK_FAILED to PresentationFailureKind.BUFFER_ALLOCATION_FAILED,
            AndroidFrameSink.PRESENT_BAD_GEOMETRY to PresentationFailureKind.INVALID_GEOMETRY,
            AndroidFrameSink.PRESENT_POST_FAILED to PresentationFailureKind.FRAME_SUBMISSION_FAILED,
            AndroidFrameSink.PRESENT_UNSUPPORTED_FORMAT to PresentationFailureKind.UNSUPPORTED_FORMAT,
            -99 to PresentationFailureKind.NATIVE_BRIDGE_FAILURE,
        )
        for ((status, expected) in cases) {
            val host = FakeHostGraphics(presentStatus = status)
            val lifecycle = activeLifecycle()
            val sink = AndroidFrameSink(host, lifecycle) { null }
            sink.configure(geometry, descriptor)

            val result = sink.present(frame())

            assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
            assertThat((result as PresentResult.Failed).failure.kind).isEqualTo(expected)
        }
    }

    @Test
    fun `a throwing native bridge is reported rather than crashing the pump`() = runTest {
        val host = FakeHostGraphics().apply {
            throwOnPresent = UnsatisfiedLinkError("liblinuxdroid_bridge not loaded")
        }
        val lifecycle = activeLifecycle()
        val sink = AndroidFrameSink(host, lifecycle) { null }
        sink.configure(geometry, descriptor)

        val result = sink.present(frame())

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.kind)
            .isEqualTo(PresentationFailureKind.NATIVE_BRIDGE_FAILURE)
    }

    @Test
    fun `release clears the configuration`() = runTest {
        val sink = AndroidFrameSink(FakeHostGraphics(), activeLifecycle()) { null }
        sink.configure(geometry, descriptor)
        assertThat(sink.configuredGeometry).isNotNull()

        sink.release()

        assertThat(sink.configuredGeometry).isNull()
    }
}
