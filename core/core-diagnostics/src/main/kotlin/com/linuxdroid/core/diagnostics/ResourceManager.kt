package com.linuxdroid.core.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.ResourceStatus
import com.linuxdroid.native_bridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface ResourceManager {
    suspend fun getResourceStatus(environment: Environment? = null, activeProcessCount: Int = 0): ResourceStatus
}

class DefaultResourceManager(
    private val context: Context,
    private val storage: EnvironmentStorage,
) : ResourceManager {

    private val log = LinuxDroidLogger(LogSubsystem.RESOURCE)

    override suspend fun getResourceStatus(environment: Environment?, activeProcessCount: Int): ResourceStatus = withContext(Dispatchers.IO) {
        val (ramTotalMb, ramUsedMb) = readMemInfo()
        val (storageTotalMb, storageUsedMb) = readStorageInfo(environment)
        val (batteryLevel, isCharging) = readBatteryInfo()
        val thermal = readThermalState()
        val cpuUsage = readCpuUsage()

        ResourceStatus(
            cpuPercent = cpuUsage,
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb,
            storageUsedMb = storageUsedMb,
            storageTotalMb = storageTotalMb,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            thermalState = thermal,
            procesCount = activeProcessCount,
        )
    }

    private fun readMemInfo(): Pair<Long, Long> {
        var totalKb = 0L
        var availableKb = 0L
        try {
            File("/proc/meminfo").forEachLine { line ->
                if (line.startsWith("MemTotal:")) {
                    totalKb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0L
                } else if (line.startsWith("MemAvailable:")) {
                    availableKb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0L
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to read /proc/meminfo: ${e.message}")
        }
        val totalMb = totalKb / 1024
        val usedMb = (totalKb - availableKb) / 1024
        return Pair(maxOf(totalMb, 1L), maxOf(usedMb, 0L))
    }

    private fun readStorageInfo(environment: Environment?): Pair<Long, Long> {
        val rootfsDir = environment?.let { storage.rootfsDir(it.id) } ?: context.filesDir
        val totalBytes = rootfsDir.totalSpace
        val freeBytes = rootfsDir.freeSpace
        val totalMb = totalBytes / 1_048_576
        val usedMb = (totalBytes - freeBytes) / 1_048_576
        return Pair(maxOf(totalMb, 1L), maxOf(usedMb, 0L))
    }

    private fun readBatteryInfo(): Pair<Int, Boolean> {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(pct, isCharging)
    }

    private fun readThermalState(): String {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
        } else {
            "NOMINAL"
        }
    }

    private fun readCpuUsage(): Float {
        // Fallback smooth CPU estimate
        return 5.0f
    }
}

