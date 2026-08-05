package io.dayd.bebop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import io.dayd.bebop.FileLogger as Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Trouve — ou fait établir — le lien Wi-Fi qui mène au Bebop 2.
 *
 * Deux voies, dans cet ordre :
 *  1. Le téléphone est déjà sur l'AP du drone : on réutilise ce réseau, sans
 *     rien demander à l'utilisateur.
 *  2. Sinon on demande au système de rejoindre `Bebop2-…` via
 *     [WifiNetworkSpecifier]. Android affiche sa propre popup de choix ; le
 *     réseau obtenu est réservé à l'app (il ne devient pas le réseau par
 *     défaut du téléphone, ce qui n'empêche rien puisqu'on bind nos sockets).
 *
 * La callback du specifier doit rester enregistrée pendant toute la session :
 * la désinscrire coupe la connexion Wi-Fi qu'elle a obtenue. D'où [release],
 * appelé à la déconnexion et pas avant.
 */
class DroneWifi(private val appContext: Context) {

    private val cm =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var heldCallback: ConnectivityManager.NetworkCallback? = null

    /** Le Wi-Fi du téléphone est-il allumé ? Sans lui, aucune des deux voies ne marche. */
    val enabled: Boolean get() = runCatching { wifi.isWifiEnabled }.getOrDefault(false)

    /**
     * Le réseau mène-t-il au drone ? On le déduit de l'adresse distribuée par
     * le DHCP du Bebop (192.168.42.x) plutôt que du SSID : lire le SSID
     * demanderait la permission de localisation depuis Android 10, et ouvrir
     * une socket de test vers le port 44444 risquerait de consommer la session
     * de discovery que le drone n'accorde qu'une fois.
     */
    private fun isDroneNet(net: Network): Boolean {
        val lp = cm.getLinkProperties(net) ?: return false
        return lp.linkAddresses.any {
            it.address.hostAddress?.startsWith(DRONE_SUBNET) == true
        }
    }

    /**
     * Réseau Wi-Fi déjà connecté qui mène au drone, ou null.
     *
     * `allNetworks` est déprécié mais reste le seul moyen simple de voir un
     * Wi-Fi qui n'est pas le réseau par défaut — et c'est exactement le cas de
     * l'AP du drone, qu'Android écarte au profit des données mobiles puisqu'il
     * n'a pas d'accès Internet. `activeNetwork` renverrait le cellulaire.
     */
    @Suppress("DEPRECATION")
    fun currentDroneNet(): Network? {
        val nets = runCatching { cm.allNetworks }.getOrDefault(emptyArray())
        return nets.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && isDroneNet(net)
        }
    }

    /**
     * Demande au système de rejoindre l'AP du drone. Renvoie le réseau obtenu,
     * ou null si l'utilisateur refuse / aucun AP `Bebop2-…` à portée / timeout.
     *
     * Bloque le temps que l'utilisateur réponde à la popup Android, d'où le
     * timeout large.
     */
    suspend fun joinDroneAp(timeoutMs: Long = 40_000): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "joinDroneAp: WifiNetworkSpecifier indisponible avant Android 10")
            return null
        }
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // L'AP du drone n'a pas d'accès Internet : sans ce retrait, Android
            // considère la demande insatisfaite et rappelle onUnavailable.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        return suspendCancellableCoroutine { cont ->
            var settled = false
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (settled) return
                    settled = true
                    Log.i(TAG, "joinDroneAp: AP rejoint — $network")
                    // On NE désinscrit pas : la connexion vit tant que la
                    // callback est enregistrée.
                    if (cont.isActive) cont.resume(network)
                }

                override fun onUnavailable() {
                    if (settled) return
                    settled = true
                    Log.w(TAG, "joinDroneAp: refusé ou aucun AP $SSID_PREFIX… à portée")
                    releaseCallback(this)
                    if (cont.isActive) cont.resume(null)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "joinDroneAp: AP perdu — $network")
                }
            }
            heldCallback = cb
            cont.invokeOnCancellation { releaseCallback(cb) }
            runCatching { cm.requestNetwork(req, cb, timeoutMs.toInt()) }
                .onFailure {
                    settled = true
                    Log.w(TAG, "joinDroneAp: requestNetwork refusé — ${it.message}")
                    if (cont.isActive) cont.resume(null)
                }
        }
    }

    private fun releaseCallback(cb: ConnectivityManager.NetworkCallback) {
        runCatching { cm.unregisterNetworkCallback(cb) }
        if (heldCallback === cb) heldCallback = null
    }

    /** Rend le réseau réservé. Coupe l'association Wi-Fi obtenue par [joinDroneAp]. */
    fun release() {
        heldCallback?.let { releaseCallback(it) }
    }

    companion object {
        private const val TAG = "Bebop"

        /** Tous les Bebop 2 diffusent `Bebop2-<6 derniers du serial>`. */
        const val SSID_PREFIX = "Bebop2-"

        /** Sous-réseau imposé par le DHCP du drone (drone = 192.168.42.1). */
        private const val DRONE_SUBNET = "192.168.42."
    }
}
