package com.linuxdroid.core.gui

/**
 * Selects the least-privileged viable compositor backend from *probed*
 * capabilities.
 *
 * Order of preference:
 * 1. [CompositorBackend.ANDROID_SURFACE] — a real Android output surface is
 *    attached and a rendering path (GLES/EGL or hardware buffers) was probed.
 * 2. [CompositorBackend.SOFTWARE] — an output surface exists but only
 *    shared-memory/software rendering was probed.
 * 3. [CompositorBackend.HEADLESS] — no presentation path; usable only for
 *    diagnostics, never selected implicitly for a user session.
 *
 * DRM/KMS is never considered: it requires privileges LinuxDroid does not have.
 */
class DefaultCompositorBackendSelector(
    /** Allow selecting a headless backend when no output surface exists. */
    private val allowHeadlessFallback: Boolean = false,
) : CompositorBackendSelector {

    override fun select(capabilities: GraphicsCapabilities): BackendSelection? {
        val rejected = LinkedHashMap<CompositorBackend, String>()

        val surface = capabilities.isAvailable(GraphicsCapability.ANDROID_SURFACE)
        val accelerated = capabilities.isAvailable(GraphicsCapability.OPENGL_ES) &&
            capabilities.isAvailable(GraphicsCapability.EGL)
        val shm = capabilities.isAvailable(GraphicsCapability.SHARED_MEMORY_BUFFER) ||
            capabilities.isAvailable(GraphicsCapability.SOFTWARE_RENDERING)

        if (surface && accelerated) {
            return BackendSelection(
                backend = CompositorBackend.ANDROID_SURFACE,
                rationale = "android surface attached with probed EGL+OpenGL ES: " +
                    capabilities.summary(),
                hardwareAccelerated = capabilities.hasHardwareAcceleration,
                rejected = rejected,
            )
        }
        rejected[CompositorBackend.ANDROID_SURFACE] = when {
            !surface -> "no Android output surface attached"
            else -> "EGL/OpenGL ES not available (egl=" +
                "${capabilities.isAvailable(GraphicsCapability.EGL)}, " +
                "gles=${capabilities.isAvailable(GraphicsCapability.OPENGL_ES)})"
        }

        if (surface && shm) {
            return BackendSelection(
                backend = CompositorBackend.SOFTWARE,
                rationale = "android surface attached, falling back to software rendering: " +
                    capabilities.summary(),
                hardwareAccelerated = false,
                rejected = rejected,
            )
        }
        rejected[CompositorBackend.SOFTWARE] = if (!surface) {
            "no Android output surface attached"
        } else {
            "no shared-memory or software rendering capability probed"
        }

        if (allowHeadlessFallback) {
            return BackendSelection(
                backend = CompositorBackend.HEADLESS,
                rationale = "no presentation path available; headless explicitly allowed: " +
                    capabilities.summary(),
                hardwareAccelerated = false,
                rejected = rejected,
            )
        }
        rejected[CompositorBackend.HEADLESS] = "headless fallback not enabled for user sessions"

        return null
    }
}
