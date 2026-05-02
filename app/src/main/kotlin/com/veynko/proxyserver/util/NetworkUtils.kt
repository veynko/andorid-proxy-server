package com.veynko.proxyserver.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Returns the most useful local IPv4 address for the device, or null if none found.
     *
     * Tries to prefer site-local addresses (e.g. 192.168.x.x / 10.x.x.x) and skips loopback.
     */
    fun getLocalIpv4Address(): String? {
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            return null
        }

        val ipv4Addresses = interfaces
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .toList()

        return ipv4Addresses.firstOrNull { it.isSiteLocalAddress }?.hostAddress
            ?: ipv4Addresses.firstOrNull()?.hostAddress
    }
}
