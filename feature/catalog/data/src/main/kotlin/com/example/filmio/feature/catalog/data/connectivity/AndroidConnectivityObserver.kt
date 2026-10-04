package com.example.filmio.feature.catalog.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class AndroidConnectivityObserver(context: Context) : ConnectivityObserver {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override val isConnected: Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        val initiallyConnected = try {
            manager.activeNetwork?.let { network ->
                manager.getNetworkCapabilities(network)?.hasValidatedInternet()
            } ?: false
        } catch (_: SecurityException) {
            close()
            return@callbackFlow
        }

        send(initiallyConnected)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(networkCapabilities.hasValidatedInternet())
            }
        }

        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // Android reports callback limits as RuntimeException; only registration is guarded.
            close()
            return@callbackFlow
        }

        awaitClose {
            manager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()
}

private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
