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

    private val isLoaded: Boolean = try {
        System.loadLibrary("linuxdroid_bridge")
        true
    } catch (_: Throwable) {
        false
    }

    // ─── System & Process ──────────────────────────────────────────────────────────

    fun getBridgeVersion(): Int = if (isLoaded) try { nativeGetBridgeVersion() } catch (_: UnsatisfiedLinkError) { 1 } else 1
    fun isExecutable(path: String): Boolean = if (isLoaded) {
        try { nativeIsExecutable(path) } catch (_: UnsatisfiedLinkError) { java.io.File(path).canExecute() }
    } else {
        java.io.File(path).canExecute()
    }
    fun setExecutable(path: String): Int = if (isLoaded) {
        try { nativeSetExecutable(path) } catch (_: UnsatisfiedLinkError) { if (java.io.File(path).setExecutable(true, false)) 0 else -1 }
    } else {
        if (java.io.File(path).setExecutable(true, false)) 0 else -1
    }
    fun getAbi(): String = if (isLoaded) try { nativeGetAbi() } catch (_: UnsatisfiedLinkError) { System.getProperty("os.arch") ?: "unknown" } else (System.getProperty("os.arch") ?: "unknown")
    fun sendSignal(pid: Int, signal: Int): Int = if (isLoaded) try { nativeSendSignal(pid, signal) } catch (_: UnsatisfiedLinkError) { -1 } else -1
    fun getAvailableMemoryBytes(): Long = if (isLoaded) try { nativeGetAvailableMemoryBytes() } catch (_: UnsatisfiedLinkError) { Runtime.getRuntime().freeMemory() } else Runtime.getRuntime().freeMemory()

    // ─── PTY Subprocess ────────────────────────────────────────────────────────────

    fun createPtyProcess(
        cmd: Array<String>,
        cwd: String,
        env: Array<String>?,
        rows: Int,
        cols: Int,
        outPidAndFd: IntArray
    ): Int = nativeCreatePtyProcess(cmd, cwd, env, rows, cols, outPidAndFd)

    fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int =
        nativeSetPtyWindowSize(fd, rows, cols)

    fun writeFd(fd: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Int =
        nativeWriteFd(fd, data, offset, length)

    fun readFd(fd: Int, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int =
        nativeReadFd(fd, buffer, offset, length)

    fun closeFd(fd: Int) = nativeCloseFd(fd)

    fun waitpid(pid: Int, block: Boolean = false): Int = nativeWaitpid(pid, block)

    // ─── Display & Surface ─────────────────────────────────────────────────────────

    fun onSurfaceCreated(surface: Surface, width: Int, height: Int) =
        nativeOnSurfaceCreated(surface, width, height)

    fun onSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int) =
        nativeOnSurfaceChanged(surface, width, height, format)

    fun onSurfaceDestroyed() = nativeOnSurfaceDestroyed()

    /** True when an ANativeWindow is currently held. */
    fun isSurfaceReady(): Boolean = nativeIsSurfaceReady()

    /** Configures the output buffer geometry. Returns false if there is no window. */
    fun configureOutput(width: Int, height: Int): Boolean = nativeConfigureOutput(width, height)

    /**
     * Copies one frame into the next window buffer and posts it.
     *
     * [stride] is the source row stride in bytes and is used as given.
     * [sourceFormat] must be a `DisplayBridge::SourceFormat` ordinal.
     * Returns a `DisplayBridge::PresentStatus` code: 0 on success, negative on
     * failure.
     */
    fun presentFrame(
        pixels: ByteArray,
        byteCount: Int,
        width: Int,
        height: Int,
        stride: Int,
        sourceFormat: Int,
    ): Int = nativePresentFrame(pixels, byteCount, width, height, stride, sourceFormat)

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
    @JvmStatic external fun nativeCreatePtyProcess(cmd: Array<String>, cwd: String, env: Array<String>?, rows: Int, cols: Int, outPidAndFd: IntArray): Int
    @JvmStatic external fun nativeSetPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
    @JvmStatic external fun nativeWriteFd(fd: Int, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeReadFd(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeCloseFd(fd: Int)
    @JvmStatic external fun nativeWaitpid(pid: Int, block: Boolean): Int

    @JvmStatic external fun nativeOnSurfaceCreated(surface: Surface, width: Int, height: Int)
    @JvmStatic external fun nativeOnSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int)
    @JvmStatic external fun nativeOnSurfaceDestroyed()
    @JvmStatic external fun nativeIsSurfaceReady(): Boolean
    @JvmStatic external fun nativeConfigureOutput(width: Int, height: Int): Boolean
    @JvmStatic external fun nativePresentFrame(
        pixels: ByteArray,
        byteCount: Int,
        width: Int,
        height: Int,
        stride: Int,
        sourceFormat: Int,
    ): Int

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
