package com.linuxdroid.core.audio

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAudioManager : AudioManager {

    private val log = LinuxDroidLogger(LogSubsystem.AUDIO)
    private val active = AtomicBoolean(false)

    override suspend fun start(sampleRate: Int, channels: Int): Boolean {
        log.info("Starting audio manager (rate=$sampleRate, channels=$channels)")
        val ok = NativeBridge.audioStart(sampleRate, channels, 1024)
        active.set(ok)
        return ok
    }

    override suspend fun stop() {
        active.set(false)
        NativeBridge.audioStop()
        log.info("Audio manager stopped")
    }

    override fun writeAudio(pcmData: ByteArray, offset: Int, size: Int): Int {
        if (!active.get()) return -1
        return NativeBridge.audioWritePcm(pcmData, offset, size)
    }

    override fun isActive(): Boolean = active.get()

    override fun getLatencyMs(): Int = NativeBridge.audioGetLatencyMs()
}

