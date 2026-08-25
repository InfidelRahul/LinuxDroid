package com.linuxdroid.core.network

import com.linuxdroid.core.model.NetworkConfig
import kotlinx.coroutines.flow.Flow

/**
 * NetworkManager monitors and manages Linux session network access.
 *
 * Rootless networking: Linux uses the host Android network stack.
 * No special kernel capabilities required.
 *
 * Implementation: Phase 16 of the development roadmap.
 */
interface NetworkManager {
    val isConnected: Flow<Boolean>
    suspend fun applyConfig(config: NetworkConfig)
    suspend fun checkDns(): Boolean
}
