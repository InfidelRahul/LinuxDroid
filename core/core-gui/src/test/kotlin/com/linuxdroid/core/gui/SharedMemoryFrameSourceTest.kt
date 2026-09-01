package com.linuxdroid.core.gui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader side of the frame-buffer protocol shared with `linuxdroid-capture.c`.
 *
 * The header layout asserted here must stay in sync with the C writer; the
 * offsets are duplicated deliberately so a change on either side breaks a test.
 */
class SharedMemoryFrameSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val log = RecordingGuiLog()

    /** Writes a frame file exactly as the C capture helper does. */
    private fun writeFrame(
        file: File,
        width: Int = 4,
        height: Int = 2,
        stride: Int = 4 * 4,
        fourcc: Int = FramePixelFormat.DRM_FORMAT_XRGB8888,
        sequence: Int = 1,
        status: Int = SharedMemoryFrameSource.STATUS_READY,
        magic: Int = SharedMemoryFrameSource.MAGIC,
        pixelBytes: Int = stride * height,
        fill: Byte = 0x40,
    ) {
        val buf = ByteBuffer
            .allocate(SharedMemoryFrameSource.HEADER_BYTES + pixelBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(SharedMemoryFrameSource.OFFSET_MAGIC, magic)
        buf.putInt(SharedMemoryFrameSource.OFFSET_VERSION, SharedMemoryFrameSource.VERSION)
        buf.putInt(SharedMemoryFrameSource.OFFSET_SEQUENCE, sequence)
        buf.putInt(SharedMemoryFrameSource.OFFSET_WIDTH, width)
        buf.putInt(SharedMemoryFrameSource.OFFSET_HEIGHT, height)
        buf.putInt(SharedMemoryFrameSource.OFFSET_STRIDE, stride)
        buf.putInt(SharedMemoryFrameSource.OFFSET_FORMAT, fourcc)
        buf.putInt(SharedMemoryFrameSource.OFFSET_STATUS, status)
        val arr = buf.array()
        for (i in SharedMemoryFrameSource.HEADER_BYTES until arr.size) arr[i] = fill
        file.writeBytes(arr)
    }

    private fun source(file: File) = SharedMemoryFrameSource(file) { log }

    @Test
    fun `reads a completed frame and reports the real layout`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, width = 4, height = 2, stride = 24)

        var seen: FrameDescriptor? = null
        val result = source(f).acquire { frame ->
            seen = frame.descriptor
            PresentResult.Presented
        }

        assertThat(result).isEqualTo(PresentResult.Presented)
        assertThat(seen?.widthPx).isEqualTo(4)
        assertThat(seen?.heightPx).isEqualTo(2)
        // The padded stride from the header must survive, not be recomputed.
        assertThat(seen?.strideBytes).isEqualTo(24)
        assertThat(seen?.format).isEqualTo(FramePixelFormat.BGRX_8888)
    }

    @Test
    fun `a frame still being written is skipped`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, status = SharedMemoryFrameSource.STATUS_WRITING)

        val result = source(f).acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Skipped::class.java)
    }

    @Test
    fun `the same frame is not delivered twice`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, sequence = 5)
        val s = source(f)

        assertThat(s.acquire { PresentResult.Presented }).isEqualTo(PresentResult.Presented)
        val second = s.acquire { PresentResult.Presented }

        assertThat(second).isInstanceOf(PresentResult.Skipped::class.java)
        assertThat((second as PresentResult.Skipped).reason).contains("no new frame")
    }

    @Test
    fun `a missing buffer file is a compositor output failure`() = runTest {
        val result = source(File(temp.root, "absent.fb")).acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.kind)
            .isEqualTo(PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE)
    }

    @Test
    fun `a truncated file is rejected rather than read past the end`() = runTest {
        val f = temp.newFile("frame.fb")
        f.writeBytes(ByteArray(8))

        val result = source(f).acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.detail).contains("size=8")
    }

    @Test
    fun `a bad magic is rejected so foreign files are never interpreted`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, magic = 0xDEADBEEF.toInt())

        val result = source(f).acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.message).contains("header is invalid")
    }

    @Test
    fun `an unsupported pixel format fails loudly`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, fourcc = 0x32315559) // 'YU12'

        val result = source(f).acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.kind)
            .isEqualTo(PresentationFailureKind.UNSUPPORTED_FORMAT)
    }

    @Test
    fun `non-positive dimensions are an invalid geometry failure`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, width = 0, stride = 16, pixelBytes = 32)

        val result = source(f).acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.kind)
            .isEqualTo(PresentationFailureKind.INVALID_GEOMETRY)
    }

    @Test
    fun `a stride smaller than one row is rejected`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, width = 8, height = 2, stride = 8, pixelBytes = 64)

        val result = source(f).acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.kind)
            .isEqualTo(PresentationFailureKind.UNSUPPORTED_FORMAT)
    }

    @Test
    fun `a file smaller than the advertised frame is rejected not overrun`() = runTest {
        val f = temp.newFile("frame.fb")
        // Header claims 100x100 but the file holds only a few bytes of pixels.
        writeFrame(f, width = 100, height = 100, stride = 400, pixelBytes = 64)

        val result = source(f).acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat((result as PresentResult.Failed).failure.message)
            .contains("smaller than the advertised frame")
    }

    @Test
    fun `pixel bytes are delivered intact`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, width = 2, height = 2, stride = 8, fill = 0x7B)

        var bytes: ByteArray? = null
        source(f).acquire { frame ->
            bytes = frame.pixels.copyOf(frame.descriptor.sizeBytes)
            PresentResult.Presented
        }

        assertThat(bytes!!.size).isEqualTo(16)
        assertThat(bytes!!.all { it == 0x7B.toByte() }).isTrue()
    }

    @Test
    fun `a closed source refuses to deliver frames`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f)
        val s = source(f)
        s.close()

        val result = s.acquire { PresentResult.Presented }

        assertThat(result).isInstanceOf(PresentResult.Failed::class.java)
        assertThat(s.isAvailable).isFalse()
    }

    @Test
    fun `a resize remaps so the next frame is read at the new size`() = runTest {
        val f = temp.newFile("frame.fb")
        writeFrame(f, width = 4, height = 2, stride = 16, sequence = 1)
        val s = source(f)
        s.acquire { PresentResult.Presented }

        // The capture helper reallocates the file at the new size.
        writeFrame(f, width = 8, height = 4, stride = 32, sequence = 1)
        s.onOutputResized(8, 4)

        var seen: FrameDescriptor? = null
        s.acquire { frame -> seen = frame.descriptor; PresentResult.Presented }

        assertThat(seen?.widthPx).isEqualTo(8)
        assertThat(seen?.strideBytes).isEqualTo(32)
    }
}
