package com.linuxdroid.core.gpu

/**
 * GpuManager detects GPU capabilities and manages hardware acceleration
 * for the Linux graphical session.
 *
 * Implementation: Phase 13 of the development roadmap.
 */
interface GpuManager {
    val gpuInfo: GpuInfo?
    suspend fun detect()
    fun isHardwareAccelerationAvailable(): Boolean
}

data class GpuInfo(
    val vendor: String,
    val renderer: String,
    val vulkanSupported: Boolean,
    val openGlEsVersion: String,
    val hardwareAcceleration: Boolean,
)
