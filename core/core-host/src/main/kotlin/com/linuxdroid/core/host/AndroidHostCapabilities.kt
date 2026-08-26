package com.linuxdroid.core.host

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.Surface
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class AndroidHostGraphics : HostGraphics {
    private val log = LinuxDroidLogger(LogSubsystem.DISPLAY)
    private var currentSurface: Surface? = null
    private var width: Int = 1920
    private var height: Int = 1080
    private var dpi: Int = 320
    private var refreshRate: Float = 60.0f
    private val ready = AtomicBoolean(false)

    override fun onSurfaceCreated(surface: Surface, width: Int, height: Int) {
        this.currentSurface = surface
        this.width = width
        this.height = height
        ready.set(true)
        log.info("Surface created: ${width}x${height}")
        NativeBridge.nativeOnSurfaceCreated(surface, width, height)
    }

    override fun onSurfaceChanged(surface: Surface, width: Int, height: Int, format: Int) {
        this.currentSurface = surface
        this.width = width
        this.height = height
        ready.set(true)
        log.info("Surface changed: ${width}x${height}, format: $format")
        NativeBridge.nativeOnSurfaceChanged(surface, width, height, format)
    }

    override fun onSurfaceDestroyed(surface: Surface) {
        ready.set(false)
        this.currentSurface = null
        log.info("Surface destroyed")
        NativeBridge.nativeOnSurfaceDestroyed()
    }

    override fun setDisplayMetrics(widthPx: Int, heightPx: Int, dpi: Int, refreshRate: Float) {
        this.width = widthPx
        this.height = heightPx
        this.dpi = dpi
        this.refreshRate = refreshRate
    }

    override fun isSurfaceReady(): Boolean = ready.get()
    override fun getDisplayWidth(): Int = width
    override fun getDisplayHeight(): Int = height
    override fun getDisplayDpi(): Int = dpi
}

class AndroidHostGpu : HostGpu {
    private val log = LinuxDroidLogger(LogSubsystem.GPU)

    override fun detectCapabilities(): HostGpuInfo {
        log.info("Detecting GPU capabilities via native bridge")
        val vendor = NativeBridge.nativeGetGpuVendor()
        val renderer = NativeBridge.nativeGetGpuRenderer()
        val version = NativeBridge.nativeGetGpuVersion()
        val vulkan = NativeBridge.nativeIsVulkanSupported()
        val hwAccel = NativeBridge.nativeIsHardwareAccelerated()

        return HostGpuInfo(
            vendor = vendor.ifBlank { "Android OpenGL ES" },
            renderer = renderer.ifBlank { "Mobile GPU Renderer" },
            version = version.ifBlank { "OpenGL ES 3.2" },
            vulkanSupported = vulkan,
            hardwareAccelerated = hwAccel,
        )
    }

    override fun isHardwareAccelerationSupported(): Boolean = NativeBridge.nativeIsHardwareAccelerated()
    override fun isVulkanSupported(): Boolean = NativeBridge.nativeIsVulkanSupported()
    override fun getGlesVersion(): String = NativeBridge.nativeGetGpuVersion()
}

class AndroidHostAudio : HostAudio {
    private val log = LinuxDroidLogger(LogSubsystem.AUDIO)
    private var audioTrack: AudioTrack? = null
    private val active = AtomicBoolean(false)

    override fun start(sampleRate: Int, channels: Int, bufferSizeFrames: Int): Boolean {
        try {
            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBuf, bufferSizeFrames * channels * 2)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            active.set(true)
            log.info("HostAudio started (sampleRate: $sampleRate, channels: $channels, buffer: $bufferSize)")
            return true
        } catch (e: Exception) {
            log.error("Failed to start HostAudio", e)
            active.set(false)
            return false
        }
    }

    override fun stop() {
        active.set(false)
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            log.warn("Error stopping audio track: ${e.message}")
        }
        audioTrack = null
        log.info("HostAudio stopped")
    }

    override fun writePcmData(audioData: ByteArray, offset: Int, size: Int): Int {
        val track = audioTrack ?: return -1
        return if (active.get()) {
            track.write(audioData, offset, size)
        } else -1
    }

    override fun isActive(): Boolean = active.get()
    override fun getLatencyMs(): Int = 20
}

class AndroidHostInput : HostInput {
    private val log = LinuxDroidLogger(LogSubsystem.INPUT)
    private var width: Int = 1920
    private var height: Int = 1080

    override fun sendTouchEvent(action: Int, pointerId: Int, x: Float, y: Float, pressure: Float) {
        val clampedX = x.coerceIn(0f, width.toFloat())
        val clampedY = y.coerceIn(0f, height.toFloat())
        NativeBridge.nativeSendTouchEvent(action, pointerId, clampedX, clampedY, pressure)
    }

    override fun sendMouseEvent(action: Int, buttonState: Int, x: Float, y: Float, scrollX: Float, scrollY: Float) {
        val clampedX = x.coerceIn(0f, width.toFloat())
        val clampedY = y.coerceIn(0f, height.toFloat())
        NativeBridge.nativeSendMouseEvent(action, buttonState, clampedX, clampedY, scrollX, scrollY)
    }

    override fun sendKeyEvent(keyCode: Int, isDown: Boolean, metaState: Int, unicodeChar: Int) {
        NativeBridge.nativeSendKeyEvent(keyCode, isDown, metaState, unicodeChar)
    }

    override fun setInputBounds(widthPx: Int, heightPx: Int) {
        this.width = widthPx
        this.height = heightPx
    }
}

class AndroidHostNetwork(private val context: Context) : HostNetwork {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isConnected(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun getDnsServers(): List<String> {
        return listOf("8.8.8.8", "8.8.4.4", "1.1.1.1")
    }

    override fun getNetworkTypeName(): String {
        val network = connectivityManager?.activeNetwork ?: return "NONE"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }
}

class AndroidHostStorage(private val sharedDir: File) : HostStorage {
    override fun getSharedDirectoryPath(): String = sharedDir.absolutePath
    override fun isAuthorized(): Boolean = sharedDir.exists() && sharedDir.canRead() && sharedDir.canWrite()
    override fun verifyAccess(): Boolean {
        if (!sharedDir.exists()) sharedDir.mkdirs()
        return sharedDir.canRead() && sharedDir.canWrite()
    }
}

