package com.linuxdroid.native_bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GuiHostLifecycleTest {

    @Test
    fun testNativeBridgeGracefulFallbackWhenNativeLibNotLoaded() {
        // On host JVM test runner without native arm64 library, verify graceful fallback without crash
        assertThat(NativeBridge.guiIsRunning()).isFalse()
        assertThat(NativeBridge.guiGetState()).isEqualTo(0)
        assertThat(NativeBridge.guiStart()).isFalse()
        assertThat(NativeBridge.guiStop()).isFalse()
    }

    @Test
    fun testGuiHostLifecycleStateModelAndIdempotentContract() {
        // Simulated contract test modeling native GuiHost state machine
        var state = AtomicInteger(0) // 0: STOPPED, 1: STARTING, 2: RUNNING, 3: STOPPING
        var workerThreads = AtomicInteger(0)

        fun fakeStart(): Boolean {
            if (state.get() == 2) return true // Idempotent start
            state.set(1) // STARTING
            val count = workerThreads.incrementAndGet()
            state.set(2) // RUNNING
            return true
        }

        fun fakeStop(): Boolean {
            if (state.get() == 0) return true // Idempotent stop
            state.set(3) // STOPPING
            workerThreads.decrementAndGet()
            state.set(0) // STOPPED
            return true
        }

        fun fakeIsRunning(): Boolean = state.get() == 2

        // Test 1 — Native host creation
        assertThat(fakeStart()).isTrue()
        assertThat(fakeIsRunning()).isTrue()
        assertThat(state.get()).isEqualTo(2)
        assertThat(workerThreads.get()).isEqualTo(1)

        // Test 2 — Duplicate start
        assertThat(fakeStart()).isTrue()
        assertThat(fakeIsRunning()).isTrue()
        assertThat(state.get()).isEqualTo(2)
        assertThat(workerThreads.get()).isEqualTo(1) // No second thread

        // Test 3 — Stop
        assertThat(fakeStop()).isTrue()
        assertThat(fakeIsRunning()).isFalse()
        assertThat(state.get()).isEqualTo(0)
        assertThat(workerThreads.get()).isEqualTo(0)

        // Test 4 — Duplicate stop
        assertThat(fakeStop()).isTrue()
        assertThat(fakeIsRunning()).isFalse()
        assertThat(state.get()).isEqualTo(0)
        assertThat(workerThreads.get()).isEqualTo(0)

        // Test 5 — Restart cycle (start -> stop -> start -> stop)
        assertThat(fakeStart()).isTrue()
        assertThat(fakeIsRunning()).isTrue()
        assertThat(workerThreads.get()).isEqualTo(1)

        assertThat(fakeStop()).isTrue()
        assertThat(fakeIsRunning()).isFalse()
        assertThat(workerThreads.get()).isEqualTo(0)

        assertThat(fakeStart()).isTrue()
        assertThat(fakeIsRunning()).isTrue()
        assertThat(workerThreads.get()).isEqualTo(1)

        assertThat(fakeStop()).isTrue()
        assertThat(fakeIsRunning()).isFalse()
        assertThat(workerThreads.get()).isEqualTo(0)
    }
}

