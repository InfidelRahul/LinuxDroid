package com.linuxdroid.core.gui

/**
 * Platform-independent contracts for getting compositor pixels onto the host
 * presentation target.
 *
 * ```
 * Weston output -> FrameSource -> FramePresenter -> FrameSink -> host surface
 * ```
 *
 * Nothing here may reference Android. `Surface`, `ANativeWindow` and every
 * other Android type stop at the Android implementation of [FrameSink], in the
 * same way [DisplayTransport] already keeps geometry Android-free.
 */

/**
 * Byte order of a 32-bit pixel, named by the order bytes appear in memory.
 *
 * Named explicitly because the producer and the consumer disagree: Weston's
 * shm capture reports DRM fourcc codes, whose `XRGB8888`/`ARGB8888` are
 * little-endian words and therefore **B,G,R,A in memory**, while Android's
 * `WINDOW_FORMAT_RGBA_8888` is **R,G,B,A in memory**. Treating one as the
 * other silently swaps the red and blue channels, so the two are distinct
 * values here and the conversion is explicit.
 */
enum class FramePixelFormat(val bytesPerPixel: Int) {
    /** R,G,B,A in memory. Matches Android `WINDOW_FORMAT_RGBA_8888`. */
    RGBA_8888(4),

    /** B,G,R,A in memory. Matches DRM `ARGB8888`. */
    BGRA_8888(4),

    /** R,G,B,X in memory; alpha ignored. */
    RGBX_8888(4),

    /** B,G,R,X in memory; alpha ignored. Matches DRM `XRGB8888`. */
    BGRX_8888(4),
    ;

    /** True when this format carries meaningful alpha. */
    val hasAlpha: Boolean get() = this == RGBA_8888 || this == BGRA_8888

    companion object {
        /**
         * Maps a DRM fourcc code to a format, or null when unsupported.
         *
         * Only the linear 32-bit packed formats Weston's shm capture actually
         * emits are accepted; anything else must fail loudly rather than be
         * guessed at.
         */
        fun fromDrmFourcc(fourcc: Int): FramePixelFormat? = when (fourcc) {
            DRM_FORMAT_XRGB8888 -> BGRX_8888
            DRM_FORMAT_ARGB8888 -> BGRA_8888
            DRM_FORMAT_XBGR8888 -> RGBX_8888
            DRM_FORMAT_ABGR8888 -> RGBA_8888
            else -> null
        }

        // fourcc_code('X','R','2','4') and friends.
        const val DRM_FORMAT_XRGB8888 = 0x34325258
        const val DRM_FORMAT_ARGB8888 = 0x34325241
        const val DRM_FORMAT_XBGR8888 = 0x34324258
        const val DRM_FORMAT_ABGR8888 = 0x34324241
    }
}

/**
 * Describes the memory layout of one compositor frame.
 *
 * [strideBytes] is carried explicitly and is **not** assumed to equal
 * `width * bytesPerPixel`: both Weston's shm buffers and Android's
 * `ANativeWindow` buffers routinely pad rows.
 */
data class FrameDescriptor(
    val widthPx: Int,
    val heightPx: Int,
    val strideBytes: Int,
    val format: FramePixelFormat,
) {
    init {
        require(widthPx > 0 && heightPx > 0) {
            "Frame dimensions must be positive, got ${widthPx}x$heightPx"
        }
        require(strideBytes >= widthPx * format.bytesPerPixel) {
            "Stride $strideBytes is too small for $widthPx px of ${format.name}"
        }
    }

    /** Minimum bytes required to hold the frame, including row padding. */
    val sizeBytes: Int get() = strideBytes * heightPx

    /** True when rows are tightly packed, so the frame can be copied in one go. */
    val isTightlyPacked: Boolean get() = strideBytes == widthPx * format.bytesPerPixel
}

/**
 * Lifecycle of the host presentation target.
 *
 * A non-null surface is deliberately *not* treated as usable: a surface is
 * only presentable in [ACTIVE]. The compositor session is independent of this
 * lifecycle — the surface may be destroyed and recreated without the Linux
 * environment or the compositor being torn down.
 */
enum class SurfaceLifecycleState {
    /** No surface has ever been provided, or the last one was destroyed. */
    NONE,

    /** The host reported a surface, but it is not yet wired to the sink. */
    CREATED,

    /** The sink holds the surface; geometry not yet confirmed. */
    ATTACHED,

    /** Fully usable: frames may be presented. */
    ACTIVE,

    /** Teardown in progress; presentation must stop. */
    DETACHING,

    /** The surface is gone. Presenting is an error until a new one arrives. */
    DESTROYED,
    ;

    /** Frames may only be submitted in this state. */
    val canPresent: Boolean get() = this == ACTIVE

    /** Transitions permitted by the surface lifecycle. */
    fun canTransitionTo(next: SurfaceLifecycleState): Boolean = when (this) {
        NONE -> next == CREATED
        CREATED -> next == ATTACHED || next == DESTROYED
        ATTACHED -> next == ACTIVE || next == DETACHING || next == DESTROYED
        ACTIVE -> next == DETACHING || next == DESTROYED || next == ATTACHED
        DETACHING -> next == DESTROYED
        // A new surface may arrive after the old one is gone.
        DESTROYED -> next == CREATED
    }
}

/** Why a frame could not be presented. Never collapsed into a success. */
enum class PresentationFailureKind {
    /** No surface has been provided by the host. */
    SURFACE_UNAVAILABLE,

    /** The surface went away, possibly mid-frame. */
    SURFACE_DESTROYED,

    /** Zero/negative or otherwise unusable dimensions. */
    INVALID_GEOMETRY,

    /** The producer's pixel format has no supported mapping. */
    UNSUPPORTED_FORMAT,

    /** The host could not hand out a buffer to draw into. */
    BUFFER_ALLOCATION_FAILED,

    /** Locking, copying or posting the buffer failed. */
    FRAME_SUBMISSION_FAILED,

    /** The compositor produced no frame, or capture failed. */
    COMPOSITOR_OUTPUT_UNAVAILABLE,

    /** The native bridge rejected the call or is not loaded. */
    NATIVE_BRIDGE_FAILURE,
}

/** A presentation failure, carrying enough detail to diagnose it. */
data class PresentationFailure(
    val kind: PresentationFailureKind,
    val message: String,
    val detail: String? = null,
) {
    fun describe(): String = buildString {
        append(kind.name).append(": ").append(message)
        detail?.let { append(" (").append(it).append(')') }
    }
}

/** Result of submitting one frame. */
sealed interface PresentResult {
    /** The frame reached the host presentation target. */
    data object Presented : PresentResult

    /**
     * The frame was intentionally dropped without being an error, e.g. the
     * surface is mid-resize. Not a failure, and not a fake success either.
     */
    data class Skipped(val reason: String) : PresentResult

    /** The frame could not be presented. */
    data class Failed(val failure: PresentationFailure) : PresentResult
}

/**
 * A frame produced by the compositor, valid only for the duration of the
 * [FrameSource.acquire] callback that supplied it.
 *
 * [pixels] is the raw frame bytes laid out per [descriptor]. Implementations
 * must not retain it after the callback returns; the buffer is recycled.
 */
class CompositorFrame(
    val descriptor: FrameDescriptor,
    val pixels: ByteArray,
    /** Monotonically increasing frame counter, for logging and drop detection. */
    val sequence: Long,
) {
    init {
        require(pixels.size >= descriptor.sizeBytes) {
            "Frame buffer holds ${pixels.size} bytes but the descriptor needs ${descriptor.sizeBytes}"
        }
    }
}

/**
 * Produces compositor frames. Implemented on the Linux side of the boundary;
 * knows how the chosen compositor exposes its output.
 */
interface FrameSource {
    /** True once the source can deliver frames. */
    val isAvailable: Boolean

    /** Layout the source currently produces, or null before the first frame. */
    val currentDescriptor: FrameDescriptor?

    /**
     * Acquires the next frame and hands it to [consume], then releases it.
     *
     * The acquire -> consume -> release cycle is closed here so the frame's
     * storage can never outlive its validity. Returns whatever [consume]
     * returned, or a failure if no frame could be acquired.
     */
    suspend fun acquire(consume: suspend (CompositorFrame) -> PresentResult): PresentResult

    /** Tells the producer the output size changed. */
    suspend fun onOutputResized(widthPx: Int, heightPx: Int)

    /** Releases producer resources. Idempotent. */
    suspend fun close()
}

/**
 * Consumes compositor frames and presents them on the host.
 *
 * The Android implementation lives in `:core:core-display` and reaches
 * `ANativeWindow` through the native bridge. No Android type appears here.
 */
interface FrameSink {
    /** Current surface lifecycle state. */
    val surfaceState: SurfaceLifecycleState

    /** Geometry the sink is configured for, or null when not configured. */
    val configuredGeometry: DisplayGeometry?

    /**
     * Prepares the sink to receive frames of [descriptor] at [geometry].
     *
     * Called on attach and again after a resize or format change. Returns the
     * failure instead of throwing so the caller can decide whether to retry.
     */
    suspend fun configure(geometry: DisplayGeometry, descriptor: FrameDescriptor): PresentationFailure?

    /** Presents one frame. Must not block on the compositor. */
    suspend fun present(frame: CompositorFrame): PresentResult

    /** Stops presentation and releases host resources. Idempotent. */
    suspend fun release()
}
