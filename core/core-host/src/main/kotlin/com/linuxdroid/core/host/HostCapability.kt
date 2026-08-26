package com.linuxdroid.core.host

/**
 * Enumeration of host hardware/platform capabilities that LinuxDroid can expose
 * to the Linux userspace through high-performance native bridges and adapters.
 */
enum class HostCapabilityType {
    GRAPHICS,
    GPU,
    AUDIO,
    INPUT,
    STORAGE,
    NETWORK,
    CAMERA,
    SENSORS,
}

data class CapabilityStatus(
    val type: HostCapabilityType,
    val isAvailable: Boolean,
    val isHardwareAccelerated: Boolean = false,
    val description: String = "",
    val details: Map<String, String> = emptyMap(),
)

/**
 * Root capability provider interface.
 */
interface HostCapabilityProvider {
    val capabilities: List<CapabilityStatus>
    fun isSupported(type: HostCapabilityType): Boolean
    fun getStatus(type: HostCapabilityType): CapabilityStatus
}

