package com.linuxdroid.native_bridge
import android.view.Surface
object NativeBridge {
    fun onSurfaceCreated(surface: Surface, width: Int, height: Int) = nativeOnSurfaceCreated(surface, width, height)
    fun onSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int) = nativeOnSurfaceChanged(surface, width, height, format)
    fun onSurfaceDestroyed() = nativeOnSurfaceDestroyed()
    fun isSurfaceReady(): Boolean = nativeIsSurfaceReady()
    fun configureOutput(width: Int, height: Int): Boolean = nativeConfigureOutput(width, height)
    fun presentFrame(pixels: ByteArray, byteCount: Int, width: Int, height: Int, stride: Int, sourceFormat: Int): Int =
        nativePresentFrame(pixels, byteCount, width, height, stride, sourceFormat)
    @JvmStatic fun nativeOnSurfaceCreated(surface: Surface, width: Int, height: Int) {}
    @JvmStatic fun nativeOnSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int) {}
    @JvmStatic fun nativeOnSurfaceDestroyed() {}
    @JvmStatic fun nativeIsSurfaceReady(): Boolean = true
    @JvmStatic fun nativeConfigureOutput(width: Int, height: Int): Boolean = true
    @JvmStatic fun nativePresentFrame(pixels: ByteArray, byteCount: Int, width: Int, height: Int, stride: Int, sourceFormat: Int): Int = 0
    @JvmStatic fun nativeGetGpuVendor(): String = ""
    @JvmStatic fun nativeGetGpuRenderer(): String = ""
    @JvmStatic fun nativeGetGpuVersion(): String = ""
    @JvmStatic fun nativeIsVulkanSupported(): Boolean = false
    @JvmStatic fun nativeIsHardwareAccelerated(): Boolean = false
}
