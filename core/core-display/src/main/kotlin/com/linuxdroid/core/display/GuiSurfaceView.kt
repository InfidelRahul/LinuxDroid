package com.linuxdroid.core.display

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge

/**
 * GuiSurfaceView provides the hardware-backed presentation surface for the Wayland/Weston
 * desktop and routes Android touch, mouse, and keyboard input events to NativeBridge.
 */
class GuiSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val log = LinuxDroidLogger(LogSubsystem.INPUT)

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        log.info("onWindowFocusChanged: hasWindowFocus=$hasWindowFocus")
        if (hasWindowFocus) {
            requestFocus()
        } else {
            // Cancel active touches and reset state on window focus loss to prevent stuck input
            NativeBridge.sendTouchEvent(MotionEvent.ACTION_CANCEL, 0, 0f, 0f, 0f)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        log.info("Surface created: ${width}x${height}")
        requestFocus()
        NativeBridge.onSurfaceCreated(holder.surface, width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        log.info("Surface changed: ${width}x${height} format=$format")
        NativeBridge.onSurfaceChanged(holder.surface, width, height, format)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        log.info("Surface destroyed")
        NativeBridge.sendTouchEvent(MotionEvent.ACTION_CANCEL, 0, 0f, 0f, 0f)
        NativeBridge.onSurfaceDestroyed()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex

        if (action == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val pressure = event.getPressure(actionIndex)
                NativeBridge.sendTouchEvent(action, pointerId, x, y, pressure)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val pressure = event.getPressure(i)
                    NativeBridge.sendTouchEvent(action, pointerId, x, y, pressure)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val pressure = event.getPressure(actionIndex)
                NativeBridge.sendTouchEvent(action, pointerId, x, y, pressure)
            }
            MotionEvent.ACTION_CANCEL -> {
                NativeBridge.sendTouchEvent(action, 0, 0f, 0f, 0f)
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_SCROLL,
                MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val x = event.x
                    val y = event.y
                    val scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    val scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val buttonState = event.buttonState
                    NativeBridge.sendMouseEvent(event.actionMasked, buttonState, x, y, scrollX, scrollY)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) {
            // Prevent duplicate repeat dispatch: libweston handles repeats via wl_keyboard repeat_info
            return true
        }
        NativeBridge.sendKeyEvent(keyCode, true, event.metaState, event.unicodeChar)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        NativeBridge.sendKeyEvent(keyCode, false, event.metaState, event.unicodeChar)
        return true
    }
}
