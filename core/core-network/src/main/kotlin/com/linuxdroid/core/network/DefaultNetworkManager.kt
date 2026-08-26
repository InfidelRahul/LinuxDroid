package com.linuxdroid.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress

class DefaultNetworkManager(
    private val context: Context,
) : NetworkManager {

    private val log = LinuxDroidLogger(LogSubsystem.NETWORK)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val _isConnected = MutableStateFlow(checkInitialConnectivity())
    override val isConnected: Flow<Boolean> = _isConnected.asStateFlow()

    private var currentConfig = NetworkConfig(enabled = true)

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isConnected.value = true
                    log.info("Host network became available")
                }

                override fun onLost(network: Network) {
                    _isConnected.value = false
                    log.info("Host network connection lost")
                }
            })
        } catch (e: Exception) {
            log.warn("Could not register NetworkCallback: ${e.message}")
        }
    }

    private fun checkInitialConnectivity(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override suspend fun applyConfig(config: NetworkConfig) {
        this.currentConfig = config
        log.info("Applied NetworkConfig: enabled=${config.enabled}, dns=${config.dnsServers}")
    }

    override suspend fun checkDns(): Boolean = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName("one.one.one.one")
            address != null
        } catch (e: Exception) {
            log.warn("DNS check failed: ${e.message}")
            false
        }
    }
}

