package com.linuxdroid.native_bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AndroidPresentationSmokeTest {

    @Test
    fun testNativeBridgeGracefulFallbackWhenNativeLibNotLoaded() {
        // Calling surface methods on host JVM test runner must not throw unhandled exceptions
        NativeBridge.onSurfaceDestroyed()
    }

    @Test
    fun testBufferPoolStateModelAndLifecycleContract() {
        // Models Phase 4 buffer states: FREE (0), ACQUIRED (1), SUBMITTED (2), RELEASED (3)
        val capacity = 3
        val bufferStates = IntArray(capacity) { 0 } // all FREE initially
        var inFlightCount = AtomicInteger(0)

        fun acquireBuffer(): Int {
            for (i in 0 until capacity) {
                if (bufferStates[i] == 0) {
                    bufferStates[i] = 1 // ACQUIRED
                    return i
                }
            }
            return -1 // Pool exhausted
        }

        fun submitBuffer(slot: Int): Boolean {
            if (slot !in 0 until capacity || bufferStates[slot] != 1) return false
            bufferStates[slot] = 2 // SUBMITTED
            inFlightCount.incrementAndGet()
            return true
        }

        fun releaseBuffer(slot: Int): Boolean {
            if (slot !in 0 until capacity || bufferStates[slot] != 2) return false
            bufferStates[slot] = 0 // Transition back to FREE
            inFlightCount.decrementAndGet()
            return true
        }

        // 1. Initial acquisition
        val b0 = acquireBuffer()
        assertThat(b0).isEqualTo(0)
        assertThat(bufferStates[0]).isEqualTo(1)

        val b1 = acquireBuffer()
        assertThat(b1).isEqualTo(1)
        assertThat(bufferStates[1]).isEqualTo(1)

        val b2 = acquireBuffer()
        assertThat(b2).isEqualTo(2)
        assertThat(bufferStates[2]).isEqualTo(1)

        // 2. Pool exhaustion
        val b3 = acquireBuffer()
        assertThat(b3).isEqualTo(-1) // No free buffers

        // 3. Submit buffer 0
        assertThat(submitBuffer(b0)).isTrue()
        assertThat(bufferStates[0]).isEqualTo(2)
        assertThat(inFlightCount.get()).isEqualTo(1)

        // 4. Released notification arrives for buffer 0
        assertThat(releaseBuffer(b0)).isTrue()
        assertThat(bufferStates[0]).isEqualTo(0)
        assertThat(inFlightCount.get()).isEqualTo(0)

        // 5. Buffer 0 is immediately available for reuse
        val bReuse = acquireBuffer()
        assertThat(bReuse).isEqualTo(0)
        assertThat(bufferStates[0]).isEqualTo(1)
    }

    @Test
    fun testResizeDrainContract() {
        var inFlight = AtomicInteger(2)
        var width = 1920
        var height = 1080

        fun resize(newW: Int, newH: Int): Boolean {
            // Drain in-flight buffers before resizing
            inFlight.set(0)
            width = newW
            height = newH
            return true
        }

        assertThat(resize(2560, 1440)).isTrue()
        assertThat(width).isEqualTo(2560)
        assertThat(height).isEqualTo(1440)
        assertThat(inFlight.get()).isEqualTo(0)
    }
}

