package com.smartwalkie.voicepingdemo.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * Lightweight, lifecycle-aware wrapper around [ConnectivityManager] for
 * tracking network availability and transport type during PTT sessions.
 *
 * Purpose
 * -------
 * The VoicePing SDK reconnects on its own with a 5-second backoff, but it
 * has no concept of "network just came back" — it only learns by trying to
 * write to a dead socket. By listening to ConnectivityManager directly we
 * can:
 *   1. Show the user an immediate offline / network-changed indicator.
 *   2. Tag KPI events with the current transport (Wi-Fi vs cellular)
 *      without using Telephony APIs / signal strength.
 *   3. Optionally poke the SDK to reconnect as soon as a network is back.
 *
 * API 21-safe. Uses NetworkCallback (added in API 21).
 */
class NetworkMonitor(context: Context, private val listener: Listener) {

    enum class Transport { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

    interface Listener {
        fun onNetworkAvailable(transport: Transport)
        fun onNetworkLost()
        fun onTransportChanged(transport: Transport)
    }

    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var lastTransport: Transport = Transport.NONE
    @Volatile private var registered: Boolean = false

    private val callback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            val t = currentTransport(network)
            lastTransport = t
            Log.d(TAG, "onAvailable: $t")
            listener.onNetworkAvailable(t)
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "onLost")
            lastTransport = Transport.NONE
            listener.onNetworkLost()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val t = transportFromCaps(caps)
            if (t != lastTransport) {
                Log.d(TAG, "onCapabilitiesChanged: $lastTransport -> $t")
                lastTransport = t
                listener.onTransportChanged(t)
            }
        }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: SecurityException) {
            // Some OEM firmwares throw without ACCESS_NETWORK_STATE; treat as unsupported.
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try { cm.unregisterNetworkCallback(callback) } catch (_: Throwable) {}
        registered = false
    }

    /** True when [start] succeeded and we have a network with INTERNET capability. */
    fun hasNetwork(): Boolean = lastTransport != Transport.NONE

    fun currentTransport(): Transport = lastTransport

    private fun currentTransport(network: Network): Transport {
        val caps = cm.getNetworkCapabilities(network) ?: return Transport.OTHER
        return transportFromCaps(caps)
    }

    private fun transportFromCaps(caps: NetworkCapabilities): Transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> Transport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
        else -> Transport.OTHER
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}
