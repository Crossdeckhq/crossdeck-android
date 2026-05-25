package com.crossdeck

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Watches network reachability via [ConnectivityManager.NetworkCallback]
 * and fires a callback on `offline → online` transitions. Drives the
 * event-queue's "flush when connectivity returns" behaviour.
 *
 * Mirrors Swift's [Reachability] (NWPathMonitor-based) — same
 * contract, different platform plumbing.
 *
 * Bank-grade contract:
 *   * The callback fires only on the OFFLINE→ONLINE edge, never on
 *     ONLINE→ONLINE (which would spam flushes on every WiFi roam).
 *   * Uses [ConnectivityManager.registerDefaultNetworkCallback]
 *     (API 24+) for the cleanest semantics. Below 24, falls back
 *     to [ConnectivityManager.registerNetworkCallback] with a
 *     broad [NetworkRequest].
 */
internal class Reachability(
    private val context: Context,
    private val onReachable: () -> Unit,
) {
    private val lock = Any()
    private var registered: Boolean = false
    private var lastReachable: Boolean = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        synchronized(lock) {
            if (registered) return
            registered = true
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        // Seed initial state — assume online if any network is
        // active. This prevents firing the first callback right
        // after register when the app is already on WiFi.
        synchronized(lock) {
            lastReachable = hasInternet(cm)
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkChange(cm)
            }

            override fun onLost(network: Network) {
                handleNetworkChange(cm)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                handleNetworkChange(cm)
            }
        }
        callback = cb

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, cb)
            }
        } catch (_: SecurityException) {
            // Some configurations deny ACCESS_NETWORK_STATE — skip
            // silently rather than crash the SDK init.
            callback = null
            synchronized(lock) { registered = false }
        }
    }

    fun stop() {
        val cb = synchronized(lock) {
            if (!registered) return
            registered = false
            val c = callback
            callback = null
            c
        } ?: return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
            // Best-effort.
        }
    }

    private fun handleNetworkChange(cm: ConnectivityManager) {
        val nowReachable = hasInternet(cm)
        val edge = synchronized(lock) {
            val edge = nowReachable && !lastReachable
            lastReachable = nowReachable
            edge
        }
        if (edge) {
            try {
                onReachable()
            } catch (_: Throwable) {
                // Listener errors must never bubble to the OS.
            }
        }
    }

    private fun hasInternet(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
