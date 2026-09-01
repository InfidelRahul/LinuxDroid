package com.linuxdroid.core.gui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Reads compositor frames from a shared-memory frame buffer file.
 *
 * Weston has no Android-surface backend, and its only supported way to get
 * pixels out of an output is `weston_output_capture_v1`, which captures into a
 * CPU-readable `wl_shm` buffer. So the compositor runs on the headless backend
 * and a small capture helper inside the rootfs repeatedly captures the output
 * into the file this class maps. Both sides live in the same PRoot mount
 * namespace, so an ordinary mapped file in the Wayland runtime directory is
 * the shared memory.
 *
 * ### Layout
 *
 * A fixed 32-byte little-endian header followed by the pixel data:
 *
 * ```
 * 0  u32 magic     'LDFB'
 * 4  u32 version
 * 8  u32 sequence  incremented after each completed frame
 * 12 u32 width
 * 16 u32 height
 * 20 u32 stride    bytes per row, NOT assumed to be width * bpp
 * 24 u32 format    DRM fourcc
 * 28 u32 status    0 = writing, 1 = ready
 * 32 .. pixels
 * ```
 *
 * ### Tearing
 *
 * The writer sets `status = writing`, writes the pixels, then sets
 * `sequence` and `status = ready`. The reader only accepts a frame whose
 * status is ready and whose sequence differs from the last one consumed, and
 * re-reads the sequence after copying — if it moved, the frame was being
 * overwritten and is dropped rather than shown torn.
 */
class SharedMemoryFrameSource(
    private val bufferFile: File,
    private val guiLog: () -> GuiLog?,
) : FrameSource {

    private var channel: FileChannel? = null
    private var mapped: ByteBuffer? = null
    private var reusableFrame: ByteArray = ByteArray(0)

    @Volatile
    private var lastSequence: Long = -1

    @Volatile
    private var descriptor: FrameDescriptor? = null

    @Volatile
    private var closed = false

    override val isAvailable: Boolean
        get() = !closed && bufferFile.isFile && bufferFile.length() >= HEADER_BYTES

    override val currentDescriptor: FrameDescriptor? get() = descriptor

    /**
     * Maps the buffer file. Safe to call repeatedly; only maps once.
     *
     * Returns the failure rather than throwing so the pump can report it as a
     * presentation failure.
     */
    private fun ensureMapped(): PresentationFailure? {
        if (mapped != null) return null
        if (!bufferFile.isFile) {
            return PresentationFailure(
                PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE,
                "compositor frame buffer does not exist",
                "path=${bufferFile.path}",
            )
        }
        val length = bufferFile.length()
        if (length < HEADER_BYTES) {
            return PresentationFailure(
                PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE,
                "compositor frame buffer is truncated",
                "size=$length min=$HEADER_BYTES",
            )
        }
        return try {
            val raf = RandomAccessFile(bufferFile, "r")
            val ch = raf.channel
            mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, length)
                .order(ByteOrder.LITTLE_ENDIAN)
            channel = ch
            guiLog()?.info(
                GuiLogCategory.GRAPHICS,
                "compositor frame buffer mapped: ${bufferFile.path} ($length bytes)",
            )
            null
        } catch (e: Exception) {
            PresentationFailure(
                PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE,
                "compositor frame buffer could not be mapped",
                "reason=${e.message}",
            )
        }
    }

    override suspend fun acquire(
        consume: suspend (CompositorFrame) -> PresentResult,
    ): PresentResult = withContext(Dispatchers.IO) {
        if (closed) {
            return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE,
                    "frame source is closed",
                ),
            )
        }
        ensureMapped()?.let { return@withContext PresentResult.Failed(it) }
        val buffer = mapped ?: return@withContext PresentResult.Failed(
            PresentationFailure(
                PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE,
                "frame buffer is not mapped",
            ),
        )

        val magic = buffer.getInt(OFFSET_MAGIC)
        if (magic != MAGIC) {
            return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE,
                    "frame buffer header is invalid",
                    "magic=0x${magic.toUInt().toString(16)}",
                ),
            )
        }
        if (buffer.getInt(OFFSET_STATUS) != STATUS_READY) {
            return@withContext PresentResult.Skipped("no completed frame yet")
        }

        val sequence = buffer.getInt(OFFSET_SEQUENCE).toUInt().toLong()
        if (sequence == lastSequence) {
            return@withContext PresentResult.Skipped("no new frame since #$sequence")
        }

        val width = buffer.getInt(OFFSET_WIDTH)
        val height = buffer.getInt(OFFSET_HEIGHT)
        val stride = buffer.getInt(OFFSET_STRIDE)
        val fourcc = buffer.getInt(OFFSET_FORMAT)

        if (width <= 0 || height <= 0) {
            return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.INVALID_GEOMETRY,
                    "compositor reported non-positive frame dimensions",
                    "${width}x$height",
                ),
            )
        }
        val format = FramePixelFormat.fromDrmFourcc(fourcc)
            ?: return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.UNSUPPORTED_FORMAT,
                    "compositor produced an unsupported pixel format",
                    "drm_fourcc=0x${fourcc.toUInt().toString(16)}",
                ),
            )
        if (stride < width * format.bytesPerPixel) {
            return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.UNSUPPORTED_FORMAT,
                    "compositor reported a stride smaller than one row",
                    "stride=$stride width=$width format=$format",
                ),
            )
        }

        val frameBytes = stride * height
        if (HEADER_BYTES + frameBytes > buffer.capacity()) {
            return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE,
                    "frame buffer is smaller than the advertised frame",
                    "need=${HEADER_BYTES + frameBytes} have=${buffer.capacity()}",
                ),
            )
        }

        val frameDescriptor = FrameDescriptor(width, height, stride, format)
        if (descriptor != frameDescriptor) {
            descriptor = frameDescriptor
            guiLog()?.info(
                GuiLogCategory.GRAPHICS,
                "compositor output format: ${width}x$height stride=$stride format=$format",
            )
        }

        if (reusableFrame.size < frameBytes) reusableFrame = ByteArray(frameBytes)
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(HEADER_BYTES)
        view.get(reusableFrame, 0, frameBytes)

        // Re-check: if the writer advanced while we copied, the copy is torn.
        if (buffer.getInt(OFFSET_STATUS) != STATUS_READY ||
            buffer.getInt(OFFSET_SEQUENCE).toUInt().toLong() != sequence
        ) {
            return@withContext PresentResult.Skipped("frame #$sequence was overwritten mid-copy")
        }

        lastSequence = sequence
        consume(CompositorFrame(frameDescriptor, reusableFrame, sequence))
    }

    override suspend fun onOutputResized(widthPx: Int, heightPx: Int) {
        // The capture helper reallocates and the header carries the new size,
        // so the mapping is dropped and re-established on the next frame.
        withContext(Dispatchers.IO) { unmap() }
        descriptor = null
        lastSequence = -1
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "frame source remapping for new output size ${widthPx}x$heightPx",
        )
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        closed = true
        unmap()
        guiLog()?.info(GuiLogCategory.GRAPHICS, "compositor frame source closed")
    }

    private fun unmap() {
        runCatching { channel?.close() }
        channel = null
        mapped = null
    }

    companion object {
        /** 'LDFB' little-endian. */
        const val MAGIC = 0x4246444C
        const val VERSION = 1
        const val HEADER_BYTES = 32

        const val OFFSET_MAGIC = 0
        const val OFFSET_VERSION = 4
        const val OFFSET_SEQUENCE = 8
        const val OFFSET_WIDTH = 12
        const val OFFSET_HEIGHT = 16
        const val OFFSET_STRIDE = 20
        const val OFFSET_FORMAT = 24
        const val OFFSET_STATUS = 28

        const val STATUS_WRITING = 0
        const val STATUS_READY = 1

        /** File name of the frame buffer inside the Wayland runtime directory. */
        const val BUFFER_FILE_NAME = "compositor-output.fb"
    }
}
