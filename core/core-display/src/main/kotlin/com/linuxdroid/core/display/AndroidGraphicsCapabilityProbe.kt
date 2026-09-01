package com.linuxdroid.core.display

import com.linuxdroid.core.gui.CapabilityProbeResult
import com.linuxdroid.core.gui.GraphicsCapabilities
import com.linuxdroid.core.gui.GraphicsCapability
import com.linuxdroid.core.gui.GraphicsCapabilityProbe
import com.linuxdroid.core.gui.ProbeOutcome
import com.linuxdroid.core.host.HostGpu
import com.linuxdroid.core.host.HostGraphics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Probes real graphics capabilities through the existing host boundary
 * ([HostGpu] → `gpu_detector`, [HostGraphics] → `display_bridge`).
 *
 * Every result records the evidence it was derived from. When a probe cannot be
 * executed the outcome is [ProbeOutcome.NOT_PROBED] — support is never inferred.
 */
class AndroidGraphicsCapabilityProbe(
    private val hostGpu: HostGpu,
    private val hostGraphics: HostGraphics,
) : GraphicsCapabilityProbe {

    override suspend fun probe(): GraphicsCapabilities = withContext(Dispatchers.IO) {
        val results = buildList {
            val gpu = runCatching { hostGpu.detectCapabilities() }
            val gpuInfo = gpu.getOrNull()
            val gpuError = gpu.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }

            val glesVersion = gpuInfo?.version.orEmpty()
            val hwAccel = gpuInfo?.hardwareAccelerated == true

            add(
                when {
                    gpuInfo == null -> notProbed(GraphicsCapability.OPENGL_ES, gpuError)
                    glesVersion.isBlank() -> unavailable(
                        GraphicsCapability.OPENGL_ES,
                        "GPU detection returned an empty GLES version",
                    )
                    else -> CapabilityProbeResult(
                        capability = GraphicsCapability.OPENGL_ES,
                        outcome = ProbeOutcome.AVAILABLE,
                        evidence = glesVersion,
                        hardwareAccelerated = hwAccel,
                        details = mapOf(
                            "vendor" to gpuInfo.vendor,
                            "renderer" to gpuInfo.renderer,
                        ),
                    )
                },
            )

            // EGL backs the GLES context created by the detector; a successful
            // GLES probe is direct evidence that EGL initialization worked.
            add(
                when {
                    gpuInfo == null -> notProbed(GraphicsCapability.EGL, gpuError)
                    glesVersion.isBlank() -> unavailable(
                        GraphicsCapability.EGL,
                        "no EGL/GLES context could be created",
                    )
                    else -> CapabilityProbeResult(
                        capability = GraphicsCapability.EGL,
                        outcome = ProbeOutcome.AVAILABLE,
                        evidence = "EGL context created during GLES detection",
                        hardwareAccelerated = hwAccel,
                    )
                },
            )

            add(
                when {
                    gpuInfo == null -> notProbed(GraphicsCapability.VULKAN, gpuError)
                    gpuInfo.vulkanSupported -> CapabilityProbeResult(
                        capability = GraphicsCapability.VULKAN,
                        outcome = ProbeOutcome.AVAILABLE,
                        evidence = "vkEnumeratePhysicalDevices reported a device",
                        hardwareAccelerated = true,
                    )
                    else -> unavailable(GraphicsCapability.VULKAN, "no Vulkan device reported")
                },
            )

            val surfaceReady = runCatching { hostGraphics.isSurfaceReady() }
            add(
                when {
                    surfaceReady.isFailure -> notProbed(
                        GraphicsCapability.ANDROID_SURFACE,
                        surfaceReady.exceptionOrNull()?.message,
                    )
                    surfaceReady.getOrDefault(false) -> CapabilityProbeResult(
                        capability = GraphicsCapability.ANDROID_SURFACE,
                        outcome = ProbeOutcome.AVAILABLE,
                        evidence = "ANativeWindow attached: " +
                            "${hostGraphics.getDisplayWidth()}x${hostGraphics.getDisplayHeight()}",
                        hardwareAccelerated = hwAccel,
                    )
                    else -> unavailable(
                        GraphicsCapability.ANDROID_SURFACE,
                        "no Surface attached to the host graphics boundary",
                    )
                },
            )

            // AHardwareBuffer import into the compositor is not implemented yet;
            // report it honestly rather than guessing.
            add(notProbed(GraphicsCapability.HARDWARE_BUFFER, "no hardware-buffer import probe implemented"))

            // Shared-memory buffers only need POSIX shm inside the rootfs, which
            // the compositor itself validates; the pixman software path is
            // always compiled into Weston, so software rendering is available
            // whenever an output surface exists.
            val surfaceAvailable = surfaceReady.getOrDefault(false)
            add(
                if (surfaceAvailable) {
                    CapabilityProbeResult(
                        capability = GraphicsCapability.SHARED_MEMORY_BUFFER,
                        outcome = ProbeOutcome.AVAILABLE,
                        evidence = "output surface present; wl_shm buffers can be blitted to it",
                    )
                } else {
                    unavailable(
                        GraphicsCapability.SHARED_MEMORY_BUFFER,
                        "no output surface to present shared-memory buffers into",
                    )
                },
            )
            add(
                if (surfaceAvailable) {
                    CapabilityProbeResult(
                        capability = GraphicsCapability.SOFTWARE_RENDERING,
                        outcome = ProbeOutcome.AVAILABLE,
                        evidence = "pixman software renderer usable with the attached output surface",
                    )
                } else {
                    unavailable(
                        GraphicsCapability.SOFTWARE_RENDERING,
                        "no output surface for software rendering",
                    )
                },
            )
        }
        GraphicsCapabilities(results = results)
    }

    private fun unavailable(capability: GraphicsCapability, evidence: String) =
        CapabilityProbeResult(capability, ProbeOutcome.UNAVAILABLE, evidence)

    private fun notProbed(capability: GraphicsCapability, error: String?) =
        CapabilityProbeResult(
            capability = capability,
            outcome = ProbeOutcome.NOT_PROBED,
            evidence = error ?: "probe could not be executed",
        )
}
