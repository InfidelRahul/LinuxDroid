package com.linuxdroid.core.gui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives frames from the compositor [FrameSource] to the host [FrameSink].
 *
 * Responsibilities are deliberately narrow: acquire a frame, present it,
 * handle the failure. It owns no surface, no compositor and no process — the
 * surface lifecycle belongs to [SurfaceLifecycle] and the compositor to the
 * existing GUI runtime.
 *
 * Synchronization: [presentOnce] holds [frameLock] for the whole
 * acquire -> present -> release cycle, and reconfiguration takes the same
 * lock. A frame therefore can never be posted while the sink is being
 * reconfigured or released, which is what would otherwise race a surface
 * destruction against an in-flight buffer.
 */
class FramePump(
    private val source: FrameSource,
    private val sink: FrameSink,
    private val surfaceLifecycle: SurfaceLifecycle,
    private val guiLog: () -> GuiLog?,
    /** Target frame interval. 16ms ~ 60fps. */
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
    /** Consecutive failures tolerated before the pump gives up. */
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
    /** Frame-level logging is off by default; these logs are per-frame and noisy. */
    private val traceFrames: Boolean = false,
) {

    private val frameLock = Mutex()
    private val frameCounter = AtomicLong(0)

    @Volatile
    private var pumpJob: Job? = null

    @Volatile
    private var configuredFor: FrameDescriptor? = null

    @Volatile
    private var lastFailure: PresentationFailure? = null

    /** The failure that stopped the pump, or null while healthy. */
    val failure: PresentationFailure? get() = lastFailure

    /** True while the pump loop is running. */
    val isRunning: Boolean get() = pumpJob?.isActive == true

    /** Frames successfully presented since start. */
    val framesPresented: Long get() = frameCounter.get()

    /** Starts the pump loop in [scope]. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        lastFailure = null
        pumpJob = scope.launch {
            guiLog()?.info(GuiLogCategory.GRAPHICS, "frame pump started (interval=${frameIntervalMs}ms)")
            var consecutiveFailures = 0
            while (isActive) {
                when (val result = presentOnce()) {
                    is PresentResult.Presented -> consecutiveFailures = 0
                    is PresentResult.Skipped -> {
                        consecutiveFailures = 0
                        if (traceFrames) {
                            guiLog()?.info(GuiLogCategory.GRAPHICS, "frame skipped: ${result.reason}")
                        }
                    }
                    is PresentResult.Failed -> {
                        consecutiveFailures += 1
                        lastFailure = result.failure
                        guiLog()?.error(
                            GuiLogCategory.GRAPHICS,
                            "frame presentation failed " +
                                "($consecutiveFailures/$maxConsecutiveFailures): " +
                                result.failure.describe(),
                        )
                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            guiLog()?.error(
                                GuiLogCategory.GRAPHICS,
                                "frame pump stopping after $consecutiveFailures consecutive failures",
                            )
                            break
                        }
                    }
                }
                delay(frameIntervalMs)
            }
            guiLog()?.info(
                GuiLogCategory.GRAPHICS,
                "frame pump stopped after ${frameCounter.get()} frames",
            )
        }
    }

    /**
     * Runs one acquire -> present -> release cycle.
     *
     * Exposed so tests can drive presentation deterministically instead of
     * racing the loop.
     */
    suspend fun presentOnce(): PresentResult = frameLock.withLock {
        if (!surfaceLifecycle.canPresent) {
            return@withLock PresentResult.Skipped("surface not active (${surfaceLifecycle.state.value})")
        }
        if (!source.isAvailable) {
            return@withLock PresentResult.Failed(
                PresentationFailure(
                    PresentationFailureKind.COMPOSITOR_OUTPUT_UNAVAILABLE,
                    "compositor frame source is unavailable",
                ),
            )
        }

        val generationAtStart = surfaceLifecycle.generation

        source.acquire { frame ->
            // Re-check under the lock: the surface may have gone away or been
            // replaced between the availability check and the frame arriving.
            if (!surfaceLifecycle.canPresent) {
                return@acquire PresentResult.Skipped("surface lost during acquire")
            }
            if (surfaceLifecycle.generation != generationAtStart) {
                return@acquire PresentResult.Skipped("surface replaced during acquire")
            }

            val reconfigureFailure = ensureConfigured(frame.descriptor)
            if (reconfigureFailure != null) {
                return@acquire PresentResult.Failed(reconfigureFailure)
            }

            val result = presentBlocking(frame)
            if (result is PresentResult.Presented) {
                val count = frameCounter.incrementAndGet()
                if (traceFrames) {
                    guiLog()?.info(
                        GuiLogCategory.GRAPHICS,
                        "frame #$count presented ${frame.descriptor.widthPx}x" +
                            "${frame.descriptor.heightPx} stride=${frame.descriptor.strideBytes}",
                    )
                }
            }
            result
        }
    }

    /**
     * Reconfigures the sink when the frame layout changes.
     *
     * Compared by full descriptor, so a stride or format change is caught and
     * not just a resize.
     */
    private suspend fun ensureConfigured(descriptor: FrameDescriptor): PresentationFailure? {
        if (configuredFor == descriptor) return null
        val geometry = surfaceLifecycle.geometry
            ?: return PresentationFailure(
                PresentationFailureKind.SURFACE_UNAVAILABLE,
                "no geometry available for the current surface",
            )
        val failure = sink.configure(geometry, descriptor)
        if (failure != null) {
            configuredFor = null
            return failure
        }
        configuredFor = descriptor
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "frame sink configured: ${descriptor.widthPx}x${descriptor.heightPx} " +
                "stride=${descriptor.strideBytes} format=${descriptor.format}",
        )
        return null
    }

    private suspend fun presentBlocking(frame: CompositorFrame): PresentResult = sink.present(frame)

    /**
     * Notifies the pump that the output geometry changed.
     *
     * Takes [frameLock] so it cannot interleave with an in-flight frame, and
     * drops the cached configuration so the next frame reconfigures the sink.
     */
    suspend fun onGeometryChanged(geometry: DisplayGeometry) = frameLock.withLock {
        configuredFor = null
        source.onOutputResized(geometry.widthPx, geometry.heightPx)
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "frame pump reconfigured for ${geometry.widthPx}x${geometry.heightPx}",
        )
    }

    /** Stops the loop and releases the sink. Idempotent. */
    suspend fun stop() {
        pumpJob?.cancel()
        pumpJob = null
        frameLock.withLock {
            configuredFor = null
            sink.release()
        }
    }

    companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 16L
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 30
    }
}
