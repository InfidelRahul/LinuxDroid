package com.linuxdroid.core.display

import android.view.Surface
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.DisplayConfig
import com.linuxdroid.native_bridge.NativeBridge
import java.util.concurrent.atomic.AtomicBoolean

class DefaultDisplayManager : DisplayManager {

    private val log = LinuxDroidLogger(LogSubsystem.DISPLAY)
    private var config = DisplayConfig(widthPx = 1920, heightPx = 1080, dpi = 320)
    private val isSurfaceActive = AtomicBoolean(false)

    fun onSurfaceCreated(surface: Surface, width: Int, height: Int) {
        val w = if (width > 0) width else config.widthPx
        val h = if (height > 0) height else config.heightPx
        config = config.copy(widthPx = w, heightPx = h)
        isSurfaceActive.set(true)
        log.info("Surface created: ${w}x${h}")
        NativeBridge.onSurfaceCreated(surface, w, h)
    }

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int) {
        config = config.copy(widthPx = width, heightPx = height)
        isSurfaceActive.set(true)
        log.info("Surface changed: ${width}x${height}, format: $format")
        NativeBridge.onSurfaceChanged(surface, width, height, format)
    }

    fun onSurfaceDestroyed() {
        isSurfaceActive.set(false)
        log.info("Surface destroyed")
        NativeBridge.onSurfaceDestroyed()
    }

    override suspend fun applyConfig(config: DisplayConfig) {
        this.config = config
        log.info("Applied display config: ${config.widthPx}x${config.heightPx} @ ${config.dpi} DPI")
    }

    override suspend fun onConfigurationChanged(widthPx: Int, heightPx: Int, dpi: Int) {
        this.config = config.copy(widthPx = widthPx, heightPx = heightPx, dpi = dpi)
        log.info("Display configuration changed: ${widthPx}x${heightPx} @ ${dpi} DPI")
    }

    override fun getCurrentConfig(): DisplayConfig {
        return config
    }

    fun isReady(): Boolean = isSurfaceActive.get()
}

