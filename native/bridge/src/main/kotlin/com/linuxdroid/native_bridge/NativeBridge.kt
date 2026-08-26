package com.linuxdroid.native_bridge

import android.view.Surface

/**
 * Kotlin entry point for the LinuxDroid native bridge.
 *
 * This is the centralized JNI gate for LinuxDroid.
 * No other Kotlin class may call System.loadLibrary or declare
 * native methods directly.
 */
object NativeBridge {

    init {
        System.loadLibrary("linuxdroid_bridge")
    }

    // ─── System & Process ──────────────────────────────────────────────────────────

    fun getBridgeVersion(): Int = nativeGetBridgeVersion()
    fun isExecutable(path: String): Boolean = nativeIsExecutable(path)
    fun setExecutable(path: String): Int = nativeSetExecutable(path)
    fun getAbi(): String = nativeGetAbi()
    fun sendSignal(pid: Int, signal: Int): Int = nativeSendSignal(pid, signal)
    fun getAvailableMemoryBytes(): Long = nativeGetAvailableMemoryBytes()

    // ─── Display & Surface ─────────────────────────────────────────────────────────

    fun onSurfaceCreated(surface: Surface, width: Int, height: Int) =
        nativeOnSurfaceCreated(surface, width, height)

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int) =
        nativeOnSurfaceChanged(surface, width, height, format)

    fun onSurfaceDestroyed() = nativeOnSurfaceDestroyed()

    // ─── GPU Detection ─────────────────────────────────────────────────────────────

    fun getGpuVendor(): String = nativeGetGpuVendor()
    fun getGpuRenderer(): String = nativeGetGpuRenderer()
    fun getGpuVersion(): String = nativeGetGpuVersion()
    fun isVulkanSupported(): Boolean = nativeIsVulkanSupported()
    fun isHardwareAccelerated(): Boolean = nativeIsHardwareAccelerated()

    // ─── Input Routing ─────────────────────────────────────────────────────────────

    fun sendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float = 1.0f) =
        nativeSendTouchEvent(action, pointerId, x, y, pressure)

    fun sendMouseEvent(action: Int, buttonState: Int, x: Float, y: Float, scrollX: Float = 0f, scrollY: Float = 0f) =
        nativeSendMouseEvent(action, buttonState, x, y, scrollX, scrollY)

    fun sendKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int = 0, unicodeChar: Int = 0) =
        nativeSendKeyEvent(keyCode, isDown, metaState, unicodeChar)

    // ─── Audio Bridge ──────────────────────────────────────────────────────────────

    fun audioStart(sampleRate: Int, channels: Int, bufferSizeFrames: Int): Boolean =
        nativeAudioStart(sampleRate, channels, bufferSizeFrames)

    fun audioStop() = nativeAudioStop()

    fun audioWritePcm(data: ByteArray, offset: Int, length: Int): Int =
        nativeAudioWritePcm(data, offset, length)

    fun audioGetLatencyMs(): Int = nativeAudioGetLatencyMs()

    // ─── External JNI declarations ─────────────────────────────────────────────────

    @JvmStatic external fun nativeGetBridgeVersion(): Int
    @JvmStatic external fun nativeIsExecutable(path: String): Boolean
    @JvmStatic external fun nativeSetExecutable(path: String): Int
    @JvmStatic external fun nativeGetAbi(): String
    @JvmStatic external fun nativeSendSignal(pid: Int, signal: Int): Int
    @JvmStatic external fun nativeGetAvailableMemoryBytes(): Long

    @JvmStatic external fun nativeOnSurfaceCreated(surface: Surface, width: Int, height: Int)
    @JvmStatic external fun nativeOnSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int)
    @JvmStatic external fun nativeOnSurfaceDestroyed()

    @JvmStatic external fun nativeGetGpuVendor(): String
    @JvmStatic external fun nativeGetGpuRenderer(): String
    @JvmStatic external fun nativeGetGpuVersion(): String
    @JvmStatic external fun nativeIsVulkanSupported(): Boolean
    @JvmStatic external fun nativeIsHardwareAccelerated(): Boolean

    @JvmStatic external fun nativeSendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float)
    @JvmStatic external fun nativeSendMouseEvent(action: Int, buttonState: Int, x: Float, y: Float, scrollX: Float, scrollY: Float)
    @JvmStatic external fun nativeSendKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int, unicodeChar: Int)

    @JvmStatic external fun nativeAudioStart(sampleRate: Int, channels: Int, bufferSizeFrames: Int): Boolean
    @JvmStatic external fun nativeAudioStop()
    @JvmStatic external fun nativeAudioWritePcm(data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeAudioGetLatencyMs(): Int
}
