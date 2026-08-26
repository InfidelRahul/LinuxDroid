package com.linuxdroid.core.audio

/**
 * AudioManager bridges Linux audio subsystem to Android audio.
 *
 * Architecture: Linux app → PulseAudio/PipeWire → native bridge → Android AAudio/AudioTrack
 */
interface AudioManager {
    suspend fun start(sampleRate: Int = 44100, channels: Int = 2): Boolean
    suspend fun stop()
    fun writeAudio(pcmData: ByteArray, offset: Int, size: Int): Int
    fun isActive(): Boolean
    fun getLatencyMs(): Int
}
