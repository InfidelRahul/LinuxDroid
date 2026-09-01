package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FramePumpTest {

    private val log = RecordingGuiLog()

    private fun geometry(w: Int = 1080, h: Int = 2400) = DisplayGeometry(w, h, 420, 60f)

    private fun activeLifecycle(): SurfaceLifecycle = SurfaceLifecycle { log }.apply {
        onSurfaceCreated(geometry())
        onAttached()
    }

    private fun pump(
        source: FrameSource,
        sink: FrameSink,
        lifecycle: SurfaceLifecycle,
    ) = FramePump(source, sink, lifecycle, { log })

    @Test
    fun `a frame is configured then presented`() = runTest {
        val lifecycle = activeLifecycle()
        lifecycle.onActivated()
        val source = FakeFrameSource()
        val sink = FakeFrameSink(lifecycle)

        val result = pump(source, sink, lifecycle).presentOnce()

        assertThat(result).isEqualTo(PresentResult.Presented)
        assertThat(sink.configured).hasSize(1)
        assertThat(sink.presentedFrames).containsExactly(0L)
    }

    @Test
    fun `the sink is configured once and reused for identical frames`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val source = FakeFrameSource()
        val sink = FakeFrameSink(lifecycle)
        val p = pump(source, sink, lifecycle)

        repeat(3) { p.presentOnce() }

        assertThat(sink.configured).hasSize(1)
        assertThat(sink.presentedFrames).containsExactly(0L, 1L, 2L).inOrder()
        assertThat(p.framesPresented).isEqualTo(3)
    }

    @Test
    fun `a stride change alone triggers reconfiguration`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val source = FakeFrameSource()
        val sink = FakeFrameSink(lifecycle)
        val p = pump(source, sink, lifecycle)

        p.presentOnce()
        // Same dimensions, different stride: still a different memory layout.
        source.descriptor = FrameDescriptor(4, 2, 32, FramePixelFormat.BGRA_8888)
        p.presentOnce()

        assertThat(sink.configured).hasSize(2)
        assertThat(sink.configured[1].strideBytes).isEqualTo(32)
    }

    @Test
    fun `a format change triggers reconfiguration`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val source = FakeFrameSource()
        val sink = FakeFrameSink(lifecycle)
        val p = pump(source, sink, lifecycle)

        p.presentOnce()
        source.descriptor = FrameDescriptor(4, 2, 16, FramePixelFormat.RGBA_8888)
        p.presentOnce()

        assertThat(sink.configured).hasSize(2)
        assertThat(sink.configured[1].format).isEqualTo(FramePixelFormat.RGBA_8888)
    }

    @Test
    fun `nothing is presented while the surface is not active`() = runTest {
        // Attached but never activated.
        val lifecycle = activeLifecycle()
        val source = FakeFrameSource()
        val sink = FakeFrameSink(lifecycle)

        val result = pump(source, sink, lifecycle).presentOnce()

        assertThat(result).isInstanceOf(PresentResult.Skipped::class.java)
        // The frame must not even be acquired: no work, no risk.
        assertThat(source.acquireCount).isEqualTo(0)
        assertThat(sink.presentedFrames).isEmpty()
    }

    @Test
    fun `no surface at all skips instead of failing`() = runTest {
        val lifecycle = SurfaceLifecycle { log }
        val result = pump(FakeFrameSource(), FakeFrameSink(lifecycle), lifecycle).presentOnce()
        assertThat(result).isInstanceOf(PresentResult.Skipped::class.java)
    }

    @Test
    fun `a surface destroyed mid-acquire never reaches the sink`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val sink = FakeFrameSink(lifecycle)
        val source = FakeFrameSource().apply {
            // The surface disappears between the check and the frame arriving.
            beforeFrame = { lifecycle.onSurfaceDestroyed() }
        }

        val result = pump(source, sink, lifecycle).presentOnce()

        assertThat(result).isInstanceOf(PresentResult.Skipped::class.java)
        assertThat(sink.presentedFrames).isEmpty()
    }

    @Test
    fun `a frame from a superseded surface generation is dropped`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val sink = FakeFrameSink(lifecycle)
        val source = FakeFrameSource().apply {
            beforeFrame = {
                // Surface replaced while the frame was in flight.
                lifecycle.onSurfaceDestroyed()
                lifecycle.onSurfaceCreated(DisplayGeometry(800, 600, 320, 60f))
                lifecycle.onAttached()
                lifecycle.onActivated()
            }
        }

        val result = pump(source, sink, lifecycle).presentOnce()

        assertThat(result).isInstanceOf(PresentResult.Skipped::class.java)
        assertThat((result as PresentResult.Skipped).reason).contains("replaced")
        assertThat(sink.presentedFrames).isEmpty()
    }

    @Test
    fun `an unavailable compositor output is a failure not a skip`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val source = FakeFrameSource(isAvailable = false)

        val result = pump(source, FakeFrameSink(lifecycle), lifecycle).presentOnce()

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.kind)
            .isEqualTo(PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE)
    }

    @Test
    fun `a configuration failure is surfaced and not retried as success`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val sink = FakeFrameSink(lifecycle).apply {
            configureFailure = PresentationFailure(
                PresentationFailureKind.BUFFER_ALLOCATION_FAILED,
                "no buffer",
            )
        }

        val result = pump(FakeFrameSource(), sink, lifecycle).presentOnce()

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.kind)
            .isEqualTo(PresentationFailureKind.BUFFER_ALLOCATION_FAILED)
        assertThat(sink.presentedFrames).isEmpty()
    }

    @Test
    fun `a sink presentation failure is reported and logged at error level`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val sink = FakeFrameSink(lifecycle).apply {
            presentResult = PresentResult.Failed(
                PresentationFailure(PresentationFailureKind.FRAME_SUBMISSION_FAILED, "post failed"),
            )
        }
        val p = pump(FakeFrameSource(), sink, lifecycle)

        val result = p.presentOnce()

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat(p.framesPresented).isEqualTo(0)
    }

    @Test
    fun `frames are not logged individually by default`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val p = pump(FakeFrameSource(), FakeFrameSink(lifecycle), lifecycle)

        repeat(5) { p.presentOnce() }

        // Per-frame logging must be off by default or the logs are unusable.
        assertThat(log.messages().none { it.contains("frame #") }).isTrue()
    }

    @Test
    fun `a resize reconfigures the sink and notifies the producer`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val source = FakeFrameSource()
        val sink = FakeFrameSink(lifecycle)
        val p = pump(source, sink, lifecycle)
        p.presentOnce()

        p.onGeometryChanged(geometry(2400, 1080))
        lifecycle.onActivated()
        p.presentOnce()

        assertThat(source.resizes).containsExactly(2400 to 1080)
        // Cached configuration is dropped, so the sink is reconfigured.
        assertThat(sink.configured).hasSize(2)
    }

    @Test
    fun `stop releases the sink and is idempotent`() = runTest {
        val lifecycle = activeLifecycle(); lifecycle.onActivated()
        val sink = FakeFrameSink(lifecycle)
        val p = pump(FakeFrameSource(), sink, lifecycle)
        p.presentOnce()

        p.stop()
        p.stop()

        assertThat(sink.released).isTrue()
        assertThat(p.isRunning).isFalse()
    }
}
