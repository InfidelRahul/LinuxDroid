package com.linuxdroid.core.host

import android.view.Surface

/**
 * HostGraphics abstraction for attaching native Android Surface buffers
 * to the Wayland compositor display bridge.
 */
interface HostGraphics {
    fun onSurfaceCreated(surface: Surface, width: Int, height: Int)
    fun onSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int)
    fun onSurfaceDestroyed(surface: Surface)
    fun setDisplayMetrics(widthPx: Int, heightPx: Int, dpi: Int, refreshRate: Float)
    fun isSurfaceReady(): Boolean
    fun getDisplayWidth(): Int
    fun getDisplayHeight(): Int
    fun getDisplayDpi(): Int
}

/**
 * HostGpu abstraction for hardware acceleration detection and configuration.
 */
interface HostGpu {
    fun detectCapabilities(): HostGpuInfo
    fun isHardwareAccelerationSupported(): Boolean
    fun isVulkanSupported(): Boolean
    fun getGlesVersion(): String
}

data class HostGpuInfo(
    val vendor: String,
    val renderer: String,
    val version: String,
    val vulkanSupported: Boolean,
    val hardwareAccelerated: Boolean,
    val extensions: List<String> = emptyList(),
)

/**
 * HostAudio abstraction for low-latency native PCM audio stream playback.
 */
interface HostAudio {
    fun start(sampleRate: Int = 44100, channels: Int = 2, bufferSizeFrames: Int = 1024): Boolean
    fun stop()
    fun writePcmData(audioData: ByteArray, offset: Int, size: Int): Int
    fun isActive(): Boolean
    fun getLatencyMs(): Int
}

/**
 * HostInput abstraction for routing touch, mouse, and keyboard input events.
 */
interface HostInput {
    fun sendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float = 1.0f)
    fun sendMouseEvent(action: Int, buttonState: Int, x: Float, y: Float, scrollX: Float = 0f, scrollY: Float = 0f)
    fun sendKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int = 0, unicodeChar: Int = 0)
    fun setInputBounds(widthPx: Int, heightPx: Int)
}

/**
 * HostStorage abstraction for shared directory access.
 */
interface HostStorage {
    fun getSharedDirectoryPath(): String
    fun isAuthorized(): Boolean
    fun verifyAccess(): Boolean
}

/**
 * HostNetwork abstraction for monitoring network connectivity and DNS.
 */
interface HostNetwork {
    fun isConnected(): Boolean
    fun getDnsServers(): List<String>
    fun getNetworkTypeName(): String
}

/**
 * HostCamera stub interface for future camera device passthrough.
 */
interface HostCamera {
    fun isAvailable(): Boolean = false
    fun getCameraCount(): Int = 0
}

/**
 * HostSensors stub interface for future sensor data streaming.
 */
interface HostSensors {
    fun isAvailable(): Boolean = false
    fun getAvailableSensors(): List<String> = emptyList()
}

