package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.domain.model.NetworkStatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes device network availability and transport type in real time via ConnectivityManager.
 */
class ConnectivityMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _status = MutableStateFlow(readCurrentSnapshot())
    val status: StateFlow<NetworkStatusSnapshot> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _status.value = snapshotFromCapabilities(networkCapabilities)
        }
    }

    private var registered = false

    init {
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(callback)
            registered = true
        }
    }

    fun refresh(): NetworkStatusSnapshot {
        val snap = readCurrentSnapshot()
        _status.value = snap
        return snap
    }

    private fun readCurrentSnapshot(): NetworkStatusSnapshot {
        val cm = connectivityManager ?: return NetworkStatusSnapshot(
            isOnline = false,
            isValidated = false,
            transportLabel = "Offline"
        )
        val active = cm.activeNetwork ?: return NetworkStatusSnapshot(
            isOnline = false,
            isValidated = false,
            transportLabel = "Offline"
        )
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkStatusSnapshot(
            isOnline = false,
            isValidated = false,
            transportLabel = "Offline"
        )
        return snapshotFromCapabilities(caps)
    }

    private fun snapshotFromCapabilities(caps: NetworkCapabilities): NetworkStatusSnapshot {
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val transport = when {
            !hasInternet -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Online"
        }
        return NetworkStatusSnapshot(
            isOnline = hasInternet,
            isValidated = validated,
            transportLabel = transport,
            isMetered = metered
        )
    }

    fun shutdown() {
        if (registered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
            registered = false
        }
    }
}
