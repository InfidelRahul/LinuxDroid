package com.linuxdroid.core.model

/**
 * Snapshot of system resource usage.
 */
data class ResourceStatus(
    /** CPU usage percentage (0-100). */
    val cpuPercent: Float,
    /** RAM used in MB. */
    val ramUsedMb: Long,
    /** RAM total in MB. */
    val ramTotalMb: Long,
    /** Storage used in MB (rootfs). */
    val storageUsedMb: Long,
    /** Storage total in MB. */
    val storageTotalMb: Long,
    /** Battery level (0-100). -1 if unknown. */
    val batteryLevel: Int,
    /** Whether device is charging. */
    val isCharging: Boolean,
    /** Thermal state: NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN */
    val thermalState: String,
    /** Number of managed Linux processes. */
    val procesCount: Int,
) {
    val ramPercent: Float get() = if (ramTotalMb > 0) ramUsedMb.toFloat() / ramTotalMb * 100f else 0f
    val storagePercent: Float get() = if (storageTotalMb > 0) storageUsedMb.toFloat() / storageTotalMb * 100f else 0f
}
