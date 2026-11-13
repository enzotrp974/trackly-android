package app.trackly.assistant.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Le téléphone a-t-il Internet, en continu (bannière « Hors ligne » du chat, idée 8 de l'audit) ?
 * Les tests passent une [FixedConnectivity] qu'ils basculent à la main.
 */
interface Connectivity {
    val online: StateFlow<Boolean>

    companion object {
        /** Toujours en ligne (aperçus, tests qui ne s'en occupent pas). */
        val AlwaysOnline: Connectivity = FixedConnectivity(true)
    }
}

/** Connectivité réglée à la main : tests et aperçus. */
class FixedConnectivity(initial: Boolean) : Connectivity {
    private val state = MutableStateFlow(initial)
    override val online: StateFlow<Boolean> = state.asStateFlow()

    fun set(online: Boolean) {
        state.value = online
    }
}

/**
 * Android : réseau par défaut suivi par [ConnectivityManager.registerDefaultNetworkCallback] (aucune
 * requête réseau, permission `ACCESS_NETWORK_STATE`). « En ligne » = un réseau par défaut avec la
 * capacité Internet, comme `hasInternet()` avant chaque envoi.
 */
class AndroidConnectivity(context: Context) : Connectivity {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(manager?.let(::currentlyOnline) ?: true)
    override val online: StateFlow<Boolean> = state.asStateFlow()

    init {
        runCatching {
            manager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        state.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    }

                    override fun onLost(network: Network) {
                        state.value = false
                    }

                    override fun onUnavailable() {
                        state.value = false
                    }
                },
            )
        }
    }

    private fun currentlyOnline(manager: ConnectivityManager): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
