package com.linuxdroid.core.display

import com.linuxdroid.core.gui.CompositorFrame
import com.linuxdroid.core.gui.DisplayGeometry
import com.linuxdroid.core.gui.FrameDescriptor
import com.linuxdroid.core.gui.FramePixelFormat
import com.linuxdroid.core.gui.FrameSink
import com.linuxdroid.core.gui.GuiLog
import com.linuxdroid.core.gui.GuiLogCategory
import com.linuxdroid.core.gui.PresentResult
import com.linuxdroid.core.gui.PresentationFailure
import com.linuxdroid.core.gui.PresentationFailureKind
import com.linuxdroid.core.gui.SurfaceLifecycle
import com.linuxdroid.core.gui.SurfaceLifecycleState
import com.linuxdroid.core.host.HostGraphics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Presents compositor frames into the Android Surface.
 *
 * This is the Android end of the frame path. It converts the platform-neutral
 * [CompositorFrame] into a native bridge call; `Surface` and `ANativeWindow`
 * stay behind [HostGraphics] and `:native:bridge`, so no Android graphics type
 * appears in the `:core:core-gui` contracts this implements.
 */
class AndroidFrameSink(
    private val hostGraphics: HostGraphics,
    private val surfaceLifecycle: SurfaceLifecycle,
    private val guiLog: () -> GuiLog?,
) : FrameSink {

    @Volatile
    private var geometry: DisplayGeometry? = null

    @Volatile
    private var descriptor: FrameDescriptor? = null

    override val surfaceState: SurfaceLifecycleState get() = surfaceLifecycle.state.value
    override val configuredGeometry: DisplayGeometry? get() = geometry

    override suspend fun configure(
        geometry: DisplayGeometry,
        descriptor: FrameDescriptor,
    ): PresentationFailure? = withContext(Dispatchers.IO) {
        if (!hostGraphics.isSurfaceReady()) {
            return@withContext PresentationFailure(
                PresentationFailureKind.SURFACE_UNAVAILABLE,
                "no Android surface is attached",
            )
        }
        if (nativeFormatOf(descriptor.format) == null) {
            return@withContext PresentationFailure(
                PresentationFailureKind.UNSUPPORTED_FORMAT,
                "pixel format cannot be presented on Android",
                "format=${descriptor.format}",
            )
        }
        // Configure the window for the frame's own size. If it differs from the
        // surface, the native layer clips rather than overrunning.
        val configured = runCatching {
            hostGraphics.configureOutput(descriptor.widthPx, descriptor.heightPx)
        }.getOrElse { e ->
            return@withContext PresentationFailure(
                PresentationFailureKind.NATIVE_BRIDGE_FAILURE,
                "native bridge rejected output configuration",
                "reason=${e.message}",
            )
        }
        if (!configured) {
            return@withContext PresentationFailure(
                PresentationFailureKind.BUFFER_ALLOCATION_FAILED,
                "output buffer geometry could not be configured",
                "${descriptor.widthPx}x${descriptor.heightPx}",
            )
        }

        this@AndroidFrameSink.geometry = geometry
        this@AndroidFrameSink.descriptor = descriptor
        surfaceLifecycle.onActivated()
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "android output configured: ${descriptor.widthPx}x${descriptor.heightPx} " +
                "stride=${descriptor.strideBytes} format=${descriptor.format} " +
                "surface=${geometry.widthPx}x${geometry.heightPx}",
        )
        null
    }

    override suspend fun present(frame: CompositorFrame): PresentResult = withContext(Dispatchers.IO) {
        if (!surfaceLifecycle.canPresent) {
            return@withContext PresentResult.Skipped("surface not active (${surfaceLifecycle.state.value})")
        }
        if (!hostGraphics.isSurfaceReady()) {
            // The surface went away underneath us; record it so the lifecycle
            // reflects reality instead of reporting a phantom active surface.
            surfaceLifecycle.onSurfaceDestroyed()
            return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.SURFACE_DESTROYED,
                    "surface was destroyed before the frame could be presented",
                ),
            )
        }
        val nativeFormat = nativeFormatOf(frame.descriptor.format)
            ?: return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.UNSUPPORTED_FORMAT,
                    "pixel format cannot be presented on Android",
                    "format=${frame.descriptor.format}",
                ),
            )

        val status = runCatching {
            hostGraphics.presentFrame(
                pixels = frame.pixels,
                byteCount = frame.descriptor.sizeBytes,
                widthPx = frame.descriptor.widthPx,
                heightPx = frame.descriptor.heightPx,
                strideBytes = frame.descriptor.strideBytes,
                sourceFormat = nativeFormat,
            )
        }.getOrElse { e ->
            return@withContext PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.NATIVE_BRIDGE_FAILURE,
                    "native bridge threw while presenting a frame",
                    "reason=${e.message}",
                ),
            )
        }

        when (status) {
            PRESENT_OK -> PresentResult.Presented
            PRESENT_NO_WINDOW -> {
                surfaceLifecycle.onSurfaceDestroyed()
                PresentResult.Failed(
                    PresentationFailure(
                        PresentationFailureKind.SURFACE_DESTROYED,
                        "native window disappeared during presentation",
                    ),
                )
            }
            PRESENT_LOCK_FAILED -> PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.BUFFER_ALLOCATION_FAILED,
                    "could not lock the output buffer",
                ),
            )
            PRESENT_BAD_GEOMETRY -> PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.INVALID_GEOMETRY,
                    "native layer rejected the frame geometry",
                    "${frame.descriptor.widthPx}x${frame.descriptor.heightPx} " +
                        "stride=${frame.descriptor.strideBytes}",
                ),
            )
            PRESENT_POST_FAILED -> PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.FRAME_SUBMISSION_FAILED,
                    "the output buffer could not be posted",
                ),
            )
            PRESENT_UNSUPPORTED_FORMAT -> PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.UNSUPPORTED_FORMAT,
                    "native layer rejected the pixel format",
                    "format=${frame.descriptor.format}",
                ),
            )
            else -> PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.NATIVE_BRIDGE_FAILURE,
                    "unknown native presentation status",
                    "status=$status",
                ),
            )
        }
    }

    override suspend fun release(): Unit = withContext(Dispatchers.IO) {
        geometry = null
        descriptor = null
        guiLog()?.info(GuiLogCategory.GRAPHICS, "android frame sink released")
    }

    companion object {
        // Must mirror DisplayBridge::PresentStatus.
        const val PRESENT_OK = 0
        const val PRESENT_NO_WINDOW = -1
        const val PRESENT_LOCK_FAILED = -2
        const val PRESENT_BAD_GEOMETRY = -3
        const val PRESENT_POST_FAILED = -4
        const val PRESENT_UNSUPPORTED_FORMAT = -5

        // Must mirror DisplayBridge::SourceFormat.
        const val SOURCE_RGBA_8888 = 0
        const val SOURCE_BGRA_8888 = 1
        const val SOURCE_RGBX_8888 = 2
        const val SOURCE_BGRX_8888 = 3

        /** Maps a platform-neutral format to the native ordinal. */
        fun nativeFormatOf(format: FramePixelFormat): Int? = when (format) {
            FramePixelFormat.RGBA_8888 -> SOURCE_RGBA_8888
            FramePixelFormat.BGRA_8888 -> SOURCE_BGRA_8888
            FramePixelFormat.RGBX_8888 -> SOURCE_RGBX_8888
            FramePixelFormat.BGRX_8888 -> SOURCE_BGRX_8888
        }
    }
}
