package com.linuxdroid.app.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge

/**
 * [SurfaceView] that serves as the Android GUI surface/lifecycle anchor.
 *
 * This is the top of the frozen GUI stack. It owns the backing [android.view.Surface]
 * and forwards its lifecycle events to the native GUI host ([NativeBridge]):
 *
 *   Android Activity/UI -> GuiSurfaceView -> NativeBridge -> native GuiHost -> libweston
 *
 * Lifecycle mapping:
 *  - Host created   -> [onAttachedToWindow]
 *  - Host destroyed -> [onDetachedFromWindow] (releases any retained surface)
 *  - Surface create -> [surfaceCreated]
 *  - Surface resize -> [surfaceChanged]
 *  - Surface destroy-> [surfaceDestroyed]
 *
 * Surface recreation: Android delivers a brand-new [android.view.Surface] inside
 * [surfaceCreated]/[surfaceChanged] after [surfaceDestroyed]. [NativeBridge] always
 * reports the fresh surface, and the native [linuxdroid::GuiHost] releases any prior
 * window, so a destroyed surface is never reused. This view holds no surface reference
 * of its own; it only forwards the currently-valid holder surface to native code.
 *
 * Threading is deliberately simple: all callbacks run on the platform's UI/surface
 * thread. No rendering thread or frame scheduler is introduced in this milestone.
 */
class GuiSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val log = LinuxDroidLogger(LogSubsystem.GUI_HOST)
    private var surfaceValid = false

    init {
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NativeBridge.onGuiHostCreated()
        log.info("GUI host created (view attached to window)")
    }

    override fun onDetachedFromWindow() {
        // surfaceDestroyed normally fires first, but detach can race; ensure we
        // never leave a surface registered with native code after the host is gone.
        if (surfaceValid) {
            surfaceValid = false
            NativeBridge.onSurfaceDestroyed()
            log.info("Surface released during host teardown")
        }
        NativeBridge.onGuiHostDestroyed()
        log.info("GUI host destroyed (view detached from window)")
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        surfaceValid = true
        NativeBridge.onSurfaceCreated(holder.surface, frame.width(), frame.height())
        log.info("Surface created: ${frame.width()}x${frame.height()}")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceValid = true
        NativeBridge.onSurfaceChanged(holder.surface, width, height, format)
        log.info("Surface changed: ${width}x${height}, format: $format")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceValid = false
        NativeBridge.onSurfaceDestroyed()
        log.info("Surface destroyed")
    }
}

/**
 * Compose wrapper for the [GuiSurfaceView] GUI surface/lifecycle anchor.
 *
 * Use this where the GUI host's surface should appear (e.g. the desktop
 * workspace). The view is created/attached and its surface lifecycle is wired to
 * the native GUI host automatically.
 */
@Composable
fun LinuxDroidGuiSurface(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> GuiSurfaceView(ctx) },
        modifier = modifier,
    )
}
