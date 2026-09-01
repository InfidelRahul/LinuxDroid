package com.linuxdroid.core.display

import com.linuxdroid.core.gui.DisplayGeometry
import com.linuxdroid.core.gui.DisplayTransport
import com.linuxdroid.core.gui.GuiLog
import com.linuxdroid.core.gui.GuiLogCategory
import com.linuxdroid.core.gui.WaylandSessionInfo
import com.linuxdroid.core.host.HostGraphics
import com.linuxdroid.core.model.GuiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the compositor output to the Android display surface through the
 * existing [HostGraphics] boundary.
 *
 * Android framework objects (`Surface`, `ANativeWindow`) never leave
 * [HostGraphics]/`:native:bridge`; this class only passes geometry and
 * lifecycle across, so nothing Android-specific reaches the GUI abstractions.
 */
class AndroidDisplayTransport(
    private val hostGraphics: HostGraphics,
    private val guiLog: () -> GuiLog?,
) : DisplayTransport {

    private val attached = AtomicBoolean(false)

    @Volatile
    private var currentGeometry: DisplayGeometry? = null

    override val isAttached: Boolean get() = attached.get()
    override val geometry: DisplayGeometry? get() = currentGeometry

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
        currentGeometry = geometry
        attached.set(true)
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "android display output attached: ${geometry.widthPx}x${geometry.heightPx} " +
                "@ ${geometry.densityDpi}dpi ${geometry.refreshRateHz}Hz",
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
        currentGeometry = geometry
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "android display geometry changed: ${geometry.widthPx}x${geometry.heightPx}",
        )
    }

    override suspend fun detach(): Unit = withContext(Dispatchers.IO) {
        if (!attached.getAndSet(false)) return@withContext
        currentGeometry = null
        guiLog()?.info(GuiLogCategory.GRAPHICS, "android display output detached")
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
