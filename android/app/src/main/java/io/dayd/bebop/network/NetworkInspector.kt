package io.dayd.bebop.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class InterfaceInfo(
    val name: String,
    val displayName: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isPointToPoint: Boolean,
    val mac: String?,
    val ipv4: List<String>,
    val ipv4Prefix: List<Int>,
    val gateway: String?,
)

object NetworkInspector {

    suspend fun listInterfaces(
        gatewaysOverride: Map<String, String> = emptyMap(),
    ): List<InterfaceInfo> = withContext(Dispatchers.IO) {
        val gateways = gatewaysOverride.ifEmpty { readGateways() }
        NetworkInterface.getNetworkInterfaces().toList().map { nif ->
            val v4 = nif.interfaceAddresses
                .filter { it.address is Inet4Address }
            InterfaceInfo(
                name = nif.name,
                displayName = nif.displayName ?: nif.name,
                isUp = runCatching { nif.isUp }.getOrDefault(false),
                isLoopback = runCatching { nif.isLoopback }.getOrDefault(false),
                isPointToPoint = runCatching { nif.isPointToPoint }.getOrDefault(false),
                mac = nif.hardwareAddress?.joinToString(":") { "%02x".format(it) },
                ipv4 = v4.map { it.address.hostAddress.orEmpty() },
                ipv4Prefix = v4.map { it.networkPrefixLength.toInt() },
                gateway = gateways[nif.name],
            )
        }
    }

    /**
     * Renvoie les IPs à essayer pour atteindre un drone Parrot :
     * - 192.168.42.1 (drone direct, sans contrôleur)
     * - Gateway de chaque interface non-loopback (le Skycontroller via USB tethering)
     * - Si l'interface a une IP en /24 et qu'elle se termine par .2 ou .3, on tente aussi .1
     */
    suspend fun candidateHosts(
        gatewaysOverride: Map<String, String> = emptyMap(),
    ): List<String> = withContext(Dispatchers.IO) {
        val set = linkedSetOf("192.168.42.1")
        for (info in listInterfaces(gatewaysOverride)) {
            if (info.isLoopback || !info.isUp) continue
            info.gateway?.takeIf { it.isNotBlank() }?.let { set += it }
            info.ipv4.forEach { ip ->
                val parts = ip.split('.')
                if (parts.size == 4 && parts.last() != "1") {
                    set += parts.subList(0, 3).joinToString(".") + ".1"
                }
            }
        }
        set.toList()
    }

    suspend fun ping(host: String, port: Int = 44444, timeoutMs: Int = 1500): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
                    true
                }
            }.getOrDefault(false)
        }

    /**
     * Sur Android 14+, /proc/net/route est bloqué par SELinux pour les apps untrusted.
     * On tolère silencieusement l'échec — les gateways seront résolues via
     * ConnectivityManager dans NetworkInspectorAndroid (qui a accès au Context).
     */
    private fun readGateways(): Map<String, String> {
        return try {
            val file = File("/proc/net/route")
            if (!file.exists()) return emptyMap()
            val result = mutableMapOf<String, String>()
            file.useLines { lines ->
                lines.drop(1).forEach { line ->
                    val cols = line.split('\t')
                    if (cols.size >= 4) {
                        val iface = cols[0]
                        val destHex = cols[1]
                        val gwHex = cols[2]
                        if (destHex == "00000000" && gwHex.length == 8) {
                            result[iface] = hexLeToIp(gwHex)
                        }
                    }
                }
            }
            result
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun hexLeToIp(hex: String): String {
        val bytes = (0..3).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
        return bytes.reversed().joinToString(".")
    }
}
