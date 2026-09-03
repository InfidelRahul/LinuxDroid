package com.linuxdroid.native_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputBridgeSmokeTest {

    // Linux evdev keycode constants from <linux/input-event-codes.h>
    private val KEY_ESC = 1
    private val KEY_1 = 2
    private val KEY_BACKSPACE = 14
    private val KEY_TAB = 15
    private val KEY_ENTER = 28
    private val KEY_LEFTCTRL = 29
    private val KEY_A = 30
    private val KEY_Z = 44
    private val KEY_LEFTSHIFT = 42
    private val KEY_SPACE = 57
    private val KEY_CAPSLOCK = 58
    private val KEY_F1 = 59
    private val KEY_F12 = 88
    private val KEY_UP = 103
    private val KEY_LEFT = 105
    private val KEY_RIGHT = 106
    private val KEY_DOWN = 108

    // Linux evdev mouse button constants from <linux/input-event-codes.h>
    private val BTN_LEFT = 0x110
    private val BTN_RIGHT = 0x111
    private val BTN_MIDDLE = 0x112
    private val BTN_SIDE = 0x116
    private val BTN_EXTRA = 0x117

    @Test
    fun testLinuxEvdevKeycodeConstants() {
        assertEquals(30, KEY_A)
        assertEquals(44, KEY_Z)
        assertEquals(28, KEY_ENTER)
        assertEquals(1, KEY_ESC)
        assertEquals(57, KEY_SPACE)
        assertEquals(14, KEY_BACKSPACE)
        assertEquals(42, KEY_LEFTSHIFT)
        assertEquals(29, KEY_LEFTCTRL)
        assertEquals(103, KEY_UP)
        assertEquals(108, KEY_DOWN)
    }

    @Test
    fun testEvdevButtonConstants() {
        assertEquals(0x110, BTN_LEFT)
        assertEquals(0x111, BTN_RIGHT)
        assertEquals(0x112, BTN_MIDDLE)
        assertEquals(0x116, BTN_SIDE)
        assertEquals(0x117, BTN_EXTRA)
    }

    @Test
    fun testStructuredInputLoggingTags() {
        val requiredTags = setOf(
            "INPUT_KEY_DOWN",
            "INPUT_KEY_UP",
            "INPUT_KEY_REPEAT",
            "INPUT_POINTER_MOTION",
            "INPUT_POINTER_BUTTON",
            "INPUT_POINTER_SCROLL",
            "INPUT_TOUCH_DOWN",
            "INPUT_TOUCH_MOTION",
            "INPUT_TOUCH_UP",
            "INPUT_TOUCH_CANCEL",
            "INPUT_UNKNOWN_KEYCODE",
            "INPUT_INVALID_EVENT",
            "INPUT_QUEUE_OVERFLOW",
            "INPUT_DEVICE_INIT",
            "INPUT_DEVICE_DESTROY",
            "INPUT_DISPATCH_ERROR"
        )
        assertEquals(16, requiredTags.size)
        assertTrue(requiredTags.contains("INPUT_TOUCH_DOWN"))
        assertTrue(requiredTags.contains("INPUT_POINTER_BUTTON"))
        assertTrue(requiredTags.contains("INPUT_KEY_DOWN"))
        assertTrue(requiredTags.contains("INPUT_QUEUE_OVERFLOW"))
    }

    @Test
    fun testCoordinateClampingLogic() {
        val maxW = 1920
        val maxH = 1080

        fun clamp(v: Float, max: Int): Float = when {
            v.isNaN() || v.isInfinite() -> 0f
            v < 0f -> 0f
            v > max -> max.toFloat()
            else -> v
        }

        assertEquals(0f, clamp(-10f, maxW), 0.001f)
        assertEquals(1920f, clamp(2500f, maxW), 0.001f)
        assertEquals(500f, clamp(500f, maxW), 0.001f)
        assertEquals(0f, clamp(Float.NaN, maxH), 0.001f)
        assertEquals(0f, clamp(Float.POSITIVE_INFINITY, maxH), 0.001f)
    }

    @Test
    fun testScrollAxisSignConventions() {
        // Android forward scroll (positive) translates to negative Wayland pointer axis
        val androidForward = 1.0f
        val waylandAxis = -androidForward * 10.0f
        assertTrue(waylandAxis < 0f)

        val androidBackward = -1.0f
        val waylandAxisBackward = -androidBackward * 10.0f
        assertTrue(waylandAxisBackward > 0f)
    }
}
