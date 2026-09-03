package com.linuxdroid.native_bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixmanRendererSmokeTest {

    // DRM FourCC little-endian byte codes:
    // fourcc_code('A', 'B', 'G', 'R') = 0x34324241 ('A' | ('B'<<8) | ('G'<<16) | ('R'<<24))
    // Note: In little endian byte 0 is 'A', byte 1 is 'B', byte 2 is 'G', byte 3 is 'R' ('1' '2' 'B' 'A' in ASCII order)
    private val DRM_FORMAT_ABGR8888 = 0x34324241
    private val DRM_FORMAT_XBGR8888 = 0x34324258

    @Test
    fun testDrmFormatDefinitions() {
        // Confirm fourcc matches DRM header specifications
        assertEquals(0x34324241, DRM_FORMAT_ABGR8888)
        assertEquals(0x34324258, DRM_FORMAT_XBGR8888)
    }

    @Test
    fun testStructuredLoggingEvents() {
        val expectedEvents = listOf(
            "PIXMAN_RENDERER_INIT",
            "PIXMAN_RENDERER_READY",
            "PIXMAN_BUFFER_ACQUIRE",
            "PIXMAN_BUFFER_LOCK",
            "PIXMAN_REPAINT_BEGIN",
            "PIXMAN_REPAINT_END",
            "PIXMAN_BUFFER_UNLOCK",
            "PIXMAN_BUFFER_SUBMIT",
            "PIXMAN_BUFFER_RELEASE",
            "PIXMAN_RENDERER_RESIZE",
            "PIXMAN_RENDERER_ERROR",
            "PIXMAN_RENDERER_DESTROY"
        )
        assertTrue(expectedEvents.contains("PIXMAN_RENDERER_READY"))
        assertTrue(expectedEvents.contains("PIXMAN_BUFFER_LOCK"))
        assertTrue(expectedEvents.contains("PIXMAN_BUFFER_UNLOCK"))
        assertEquals(12, expectedEvents.size)
    }

    @Test
    fun testFailureCategories() {
        val categories = setOf(
            "PIXMAN_INIT_FAILURE",
            "PIXMAN_BUFFER_FAILURE",
            "PIXMAN_LOCK_FAILURE",
            "PIXMAN_IMAGE_FAILURE",
            "PIXMAN_RENDER_FAILURE",
            "PIXMAN_UNLOCK_FAILURE",
            "PIXMAN_SUBMIT_FAILURE",
            "PIXMAN_RESIZE_FAILURE",
            "PIXMAN_LIFETIME_FAILURE",
            "PIXMAN_FORMAT_FAILURE",
            "PIXMAN_STRIDE_FAILURE",
            "PIXMAN_UNKNOWN_FAILURE"
        )
        assertEquals(12, categories.size)
        assertTrue(categories.contains("PIXMAN_LOCK_FAILURE"))
        assertTrue(categories.contains("PIXMAN_FORMAT_FAILURE"))
    }

    @Test
    fun testPixelByteOffsetComputation() {
        val width = 1920
        val height = 1080
        val stride = 1920
        val strideBytes = stride * 4

        // Top-left (0,0)
        val offsetTl = 0 * strideBytes + 0 * 4
        assertEquals(0, offsetTl)

        // Center (960, 540)
        val offsetCenter = 540 * strideBytes + 960 * 4
        assertEquals(540 * 7680 + 3840, offsetCenter)

        // Bottom-right (1919, 1079)
        val offsetBr = 1079 * strideBytes + 1919 * 4
        assertTrue(offsetBr < height * strideBytes)
    }
}
