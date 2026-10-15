package ru.nepalimsya.sukify.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние сети: есть ли интернет и не мобильный ли он (для «качать только по Wi-Fi»). */
data class NetworkState(val online: Boolean, val unmetered: Boolean)

/**
 * Следит за сетью системы. По нему загрузки решают, ждать ли Wi-Fi, плеер —
 * можно ли брать трек из сети, а интерфейс — показывать ли «Нет сети».
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(read(connectivity.activeNetwork))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    val online: Boolean get() = _state.value.online

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _state.value = fromCaps(caps)
            }

            override fun onLost(network: Network) {
                _state.value = NetworkState(online = false, unmetered = false)
            }
        })
    }

    private fun read(network: Network?): NetworkState {
        val caps = network?.let { connectivity.getNetworkCapabilities(it) } ?: return NetworkState(false, false)
        return fromCaps(caps)
    }

    private fun fromCaps(caps: NetworkCapabilities) = NetworkState(
        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
