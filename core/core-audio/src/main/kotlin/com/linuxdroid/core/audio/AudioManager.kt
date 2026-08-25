package com.linuxdroid.core.audio

/**
 * AudioManager bridges Linux audio subsystem to Android audio.
 *
 * Architecture: Linux app → PulseAudio/PipeWire → native bridge → Android AAudio/OpenSL ES
 *
 * Implementation: Phase 15 of the development roadmap.
 */
interface AudioManager {
    suspend fun start()
    suspend fun stop()
    fun isActive(): Boolean
}
