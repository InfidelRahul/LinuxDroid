package com.linuxdroid.app.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.linuxdroid.core.host.HostGraphics

/**
 * The Android output surface the Wayland compositor presents into.
 *
 * This is the Android end of the display boundary: it forwards Surface
 * lifecycle to [HostGraphics], which owns the `ANativeWindow` in
 * `:native:bridge`. No Android graphics object leaves this class, and no GUI or
 * compositor logic lives here.
 */
class CompositorOutputSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Set before the view is attached. */
    var hostGraphics: HostGraphics? = null

    /** Invoked after the surface becomes available or is resized. */
    var onOutputAvailable: ((widthPx: Int, heightPx: Int) -> Unit)? = null

    /** Invoked once the surface is gone and the compositor must stop presenting. */
    var onOutputLost: (() -> Unit)? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        val graphics = hostGraphics ?: return
        graphics.onSurfaceCreated(holder.surface, frame.width(), frame.height())
        graphics.setDisplayMetrics(
            widthPx = frame.width(),
            heightPx = frame.height(),
            dpi = resources.displayMetrics.densityDpi,
            refreshRate = display?.refreshRate ?: DEFAULT_REFRESH_RATE,
        )
        onOutputAvailable?.invoke(frame.width(), frame.height())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val graphics = hostGraphics ?: return
        graphics.onSurfaceChanged(holder.surface, width, height, format)
        graphics.setDisplayMetrics(
            widthPx = width,
            heightPx = height,
            dpi = resources.displayMetrics.densityDpi,
            refreshRate = display?.refreshRate ?: DEFAULT_REFRESH_RATE,
        )
        onOutputAvailable?.invoke(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hostGraphics?.onSurfaceDestroyed(holder.surface)
        onOutputLost?.invoke()
    }

    private companion object {
        const val DEFAULT_REFRESH_RATE = 60.0f
    }
}
