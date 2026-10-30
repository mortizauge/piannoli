package dev.piannoli.scorza.util

import java.net.Inet6Address
import java.net.NetworkInterface

object NetworkInfo {
    fun localIpv4Addresses(hasVpn: Boolean): List<String> {
        val scored = mutableListOf<Triple<Int, String, String>>()
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val isTunnel = name.startsWith("tun") || name.startsWith("tap")
                if (isTunnel && !hasVpn) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress || addr is Inet6Address) continue
                    val host = addr.hostAddress ?: continue
                    scored.add(Triple(interfaceRank(name), name, host))
                }
            }
        } catch (_: Exception) { }
        return scored
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            .map { it.third }
            .distinct()
    }

    private fun interfaceRank(ifaceName: String): Int = when {
        ifaceName.startsWith("wlan") -> 0
        ifaceName.startsWith("eth") -> 1
        ifaceName.startsWith("tun") || ifaceName.startsWith("tap") -> 3
        else -> 2
    }
}
