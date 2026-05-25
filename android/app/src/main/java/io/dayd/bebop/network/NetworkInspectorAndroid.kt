package io.dayd.bebop.network

import android.content.Context
import android.net.ConnectivityManager

/**
 * Récupère la gateway de chaque interface via les APIs Android (ConnectivityManager).
 * Sur Android 14+, /proc/net/route est inaccessible aux apps : c'est la seule voie propre.
 *
 * Map: nom d'interface (ex. "wlan0", "rndis0") -> IP gateway (ex. "192.168.53.1").
 */
fun getGatewaysFromContext(context: Context): Map<String, String> {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return emptyMap()
    val result = mutableMapOf<String, String>()
    cm.allNetworks.forEach { network ->
        val lp = cm.getLinkProperties(network) ?: return@forEach
        val iface = lp.interfaceName ?: return@forEach
        lp.routes
            .firstOrNull { it.isDefaultRoute && it.gateway?.hostAddress?.contains('.') == true }
            ?.gateway
            ?.hostAddress
            ?.let { result[iface] = it }
    }
    return result
}
