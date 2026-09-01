package com.linuxdroid.core.display

import com.linuxdroid.core.gui.DisplayGeometry
import com.linuxdroid.core.gui.DisplayTransport
import com.linuxdroid.core.gui.FramePump
import com.linuxdroid.core.gui.FrameSink
import com.linuxdroid.core.gui.FrameSource
import com.linuxdroid.core.gui.GuiLog
import com.linuxdroid.core.gui.GuiLogCategory
import com.linuxdroid.core.gui.SharedMemoryFrameSource
import com.linuxdroid.core.gui.SurfaceLifecycle
import com.linuxdroid.core.gui.WaylandSessionInfo
import com.linuxdroid.core.host.HostGraphics
import com.linuxdroid.core.model.GuiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the compositor output to the Android display surface through the
 * existing [HostGraphics] boundary.
 *
 * Owns the presentation path for a graphical session:
 *
 * ```
 * Weston headless output
 *   -> weston_output_capture_v1 (linuxdroid-capture, in the rootfs)
 *   -> shared frame buffer file
 *   -> SharedMemoryFrameSource   [FrameSource]
 *   -> FramePump
 *   -> AndroidFrameSink          [FrameSink]
 *   -> HostGraphics -> native bridge -> ANativeWindow -> Surface
 * ```
 *
 * Android framework objects (`Surface`, `ANativeWindow`) never leave
 * [HostGraphics]/`:native:bridge`; this class passes geometry, lifecycle and
 * platform-neutral frames, so nothing Android-specific reaches the GUI
 * abstractions.
 */
class AndroidDisplayTransport(
    private val hostGraphics: HostGraphics,
    private val guiLog: () -> GuiLog?,
    /** Shared with the Android view so both agree on the surface state. */
    val surfaceLifecycle: SurfaceLifecycle = SurfaceLifecycle(guiLog),
    /** Overridable so tests can supply a fake producer/consumer. */
    private val frameSourceFactory: (WaylandSessionInfo) -> FrameSource = { session ->
        SharedMemoryFrameSource(
            bufferFile = File(session.hostRuntimeDir, SharedMemoryFrameSource.BUFFER_FILE_NAME),
            guiLog = guiLog,
        )
    },
    private val frameSinkFactory: (SurfaceLifecycle) -> FrameSink = { lifecycle ->
        AndroidFrameSink(hostGraphics, lifecycle, guiLog)
    },
    /** Frame-level logging; off by default to avoid a log line per frame. */
    private val traceFrames: Boolean = false,
) : DisplayTransport {

    private val attached = AtomicBoolean(false)

    @Volatile
    private var currentGeometry: DisplayGeometry? = null

    @Volatile
    private var pump: FramePump? = null

    @Volatile
    private var source: FrameSource? = null

    @Volatile
    private var scope: CoroutineScope? = null

    override val isAttached: Boolean get() = attached.get()
    override val geometry: DisplayGeometry? get() = currentGeometry

    /** Frames presented in the current session; 0 when not presenting. */
    val framesPresented: Long get() = pump?.framesPresented ?: 0L

    override suspend fun attach(
        session: WaylandSessionInfo,
        geometry: DisplayGeometry,
    ): Unit = withContext(Dispatchers.IO) {
        if (!hostGraphics.isSurfaceReady()) {
            throw GuiError(
                "Android display surface unavailable: no Surface attached to the host graphics boundary " +
                    "(session=${session.socketName})",
            )
        }
        hostGraphics.setDisplayMetrics(
            widthPx = geometry.widthPx,
            heightPx = geometry.heightPx,
            dpi = geometry.densityDpi,
            refreshRate = geometry.refreshRateHz,
        )

        // The view may already have reported the surface; if not, record it now
        // so the lifecycle reflects the surface the host is actually holding.
        if (surfaceLifecycle.state.value == com.linuxdroid.core.gui.SurfaceLifecycleState.NONE ||
            surfaceLifecycle.state.value == com.linuxdroid.core.gui.SurfaceLifecycleState.DESTROYED
        ) {
            surfaceLifecycle.onSurfaceCreated(geometry)
        }
        surfaceLifecycle.onAttached()

        val frameSource = frameSourceFactory(session)
        val frameSink = frameSinkFactory(surfaceLifecycle)
        val framePump = FramePump(
            source = frameSource,
            sink = frameSink,
            surfaceLifecycle = surfaceLifecycle,
            guiLog = guiLog,
            traceFrames = traceFrames,
        )
        val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        source = frameSource
        pump = framePump
        scope = pumpScope

        // The sink activates the surface once it has been configured for a real
        // frame; until then presentation is correctly refused.
        framePump.start(pumpScope)

        currentGeometry = geometry
        attached.set(true)
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "android display output attached: ${geometry.widthPx}x${geometry.heightPx} " +
                "@ ${geometry.densityDpi}dpi ${geometry.refreshRateHz}Hz; " +
                "presenting from ${session.hostRuntimeDir}/${SharedMemoryFrameSource.BUFFER_FILE_NAME}",
        )
    }

    override suspend fun onGeometryChanged(geometry: DisplayGeometry): Unit = withContext(Dispatchers.IO) {
        if (!attached.get()) {
            throw GuiError("Cannot apply geometry change: display transport is not attached")
        }
        hostGraphics.setDisplayMetrics(
            widthPx = geometry.widthPx,
            heightPx = geometry.heightPx,
            dpi = geometry.densityDpi,
            refreshRate = geometry.refreshRateHz,
        )
        surfaceLifecycle.onGeometryChanged(geometry)
        currentGeometry = geometry
        pump?.onGeometryChanged(geometry)
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "android display geometry changed: ${geometry.widthPx}x${geometry.heightPx}",
        )
    }

    override suspend fun detach(): Unit = withContext(Dispatchers.IO) {
        if (!attached.getAndSet(false)) return@withContext
        val presented = pump?.framesPresented ?: 0L

        // Stop presenting before releasing the surface, so no frame is in
        // flight when the native window goes away.
        runCatching { pump?.stop() }
        runCatching { source?.close() }
        scope?.cancel()

        pump = null
        source = null
        scope = null
        currentGeometry = null
        surfaceLifecycle.onSurfaceDestroyed()
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "android display output detached after $presented frames",
        )
    }

    /**
     * Current output geometry derived from the host graphics boundary, or null
     * when no Surface is attached. Used as the GUI runtime's geometry provider.
     */
    fun currentOutputGeometry(refreshRateHz: Float = DEFAULT_REFRESH_RATE): DisplayGeometry? {
        if (!hostGraphics.isSurfaceReady()) return null
        val width = hostGraphics.getDisplayWidth()
        val height = hostGraphics.getDisplayHeight()
        if (width <= 0 || height <= 0) return null
        return DisplayGeometry(
            widthPx = width,
            heightPx = height,
            densityDpi = hostGraphics.getDisplayDpi().coerceAtLeast(1),
            refreshRateHz = refreshRateHz,
        )
    }

    companion object {
        const val DEFAULT_REFRESH_RATE = 60.0f
    }
}
