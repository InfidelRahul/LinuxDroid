package com.linuxdroid.core.gpu

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge

class DefaultGpuManager : GpuManager {

    private val log = LinuxDroidLogger(LogSubsystem.GPU)
    private var cachedGpuInfo: GpuInfo? = null

    override val gpuInfo: GpuInfo?
        get() = cachedGpuInfo

    override suspend fun detect() {
        log.info("Probing native GPU capabilities")
        try {
            val vendor = NativeBridge.getGpuVendor()
            val renderer = NativeBridge.getGpuRenderer()
            val version = NativeBridge.getGpuVersion()
            val vulkan = NativeBridge.isVulkanSupported()
            val hwAccel = NativeBridge.isHardwareAccelerated()

            cachedGpuInfo = GpuInfo(
                vendor = vendor.ifBlank { "Android GLES" },
                renderer = renderer.ifBlank { "Mobile GPU" },
                vulkanSupported = vulkan,
                openGlEsVersion = version.ifBlank { "OpenGL ES 3.2" },
                hardwareAcceleration = hwAccel,
            )
            log.info("GPU detection completed: $cachedGpuInfo")
        } catch (e: Exception) {
            log.warn("GPU capability probe encountered an exception, applying safe fallback", e)
            cachedGpuInfo = GpuInfo(
                vendor = "Software",
                renderer = "llvmpipe / swiftshader fallback",
                vulkanSupported = false,
                openGlEsVersion = "OpenGL ES 2.0 (Software)",
                hardwareAcceleration = false,
            )
        }
    }

    override fun isHardwareAccelerationAvailable(): Boolean {
        return cachedGpuInfo?.hardwareAcceleration ?: false
    }
}

