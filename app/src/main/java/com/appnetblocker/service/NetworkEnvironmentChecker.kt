package com.appnetblocker.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

interface EnvironmentChecker {
    fun isAnotherVpnActive(ownVpnRunning: Boolean): Boolean
    fun observeOtherVpnActive(ownVpnRunning: () -> Boolean): Flow<Boolean>
    fun isIgnoringBatteryOptimizations(): Boolean
}

class AndroidEnvironmentChecker(
    private val context: Context
) : EnvironmentChecker {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val powerManager: PowerManager?
        get() = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun isAnotherVpnActive(ownVpnRunning: Boolean): Boolean {
        if (ownVpnRunning) return false
        val cm = connectivityManager ?: return false

        return try {
            val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                return true
            }

            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            networks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun observeOtherVpnActive(ownVpnRunning: () -> Boolean): Flow<Boolean> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(false)
            close()
            return@callbackFlow
        }

        trySend(isAnotherVpnActive(ownVpnRunning()))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isAnotherVpnActive(ownVpnRunning()))
            }

            override fun onLost(network: Network) {
                trySend(isAnotherVpnActive(ownVpnRunning()))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(isAnotherVpnActive(ownVpnRunning()))
            }
        }

        // Removing NET_CAPABILITY_NOT_VPN is required so Android reports VPN networks.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val registered = try {
            cm.registerNetworkCallback(request, callback)
            true
        } catch (_: Exception) {
            false
        }

        awaitClose {
            if (registered) {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                    // Ignore already-unregistered callback.
                }
            }
        }
    }.distinctUntilChanged()

    override fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Exception) {
            false
        }
    }
}
