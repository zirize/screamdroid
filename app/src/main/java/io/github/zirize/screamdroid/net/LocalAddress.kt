package io.github.zirize.screamdroid.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * This device's address on the LAN, for the person who has to type it into the sender.
 *
 * 🔑 **Read from the interface list, not from `WifiManager`.** The same answer without asking for
 *    `ACCESS_WIFI_STATE`, and it is also right when the phone is on Ethernet or tethered - cases
 *    where the Wi-Fi API returns nothing.
 */
object LocalAddress {

    /**
     * The interface that carries [ipv4].
     *
     * 🔑 Joining a multicast group needs one named explicitly: left to the system, the join can
     *    land on an interface the sender is not on, and then nothing arrives while everything
     *    looks healthy.
     */
    fun ipv4Interface(): NetworkInterface? =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.firstOrNull { nif ->
                    nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                        nif.inetAddresses.toList().any {
                            it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress
                        }
                }
        }.getOrNull()

    fun ipv4(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList().asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
}
