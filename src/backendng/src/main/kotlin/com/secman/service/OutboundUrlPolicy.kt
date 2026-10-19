package com.secman.service

import java.net.InetAddress
import java.net.URI

/** Shared HTTPS/host/public-address boundary for configured evidence sources. */
object OutboundUrlPolicy {
    fun validate(uri: URI, allowedHosts: Set<String>, resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName): URI {
        require(uri.isAbsolute && uri.scheme.equals("https", true)) { "Source URL must use https" }
        require(uri.userInfo == null && (uri.port == -1 || uri.port == 443)) { "Source URL has unsafe authority" }
        val host = requireNotNull(uri.host) { "Source URL has no host" }
        require(host.lowercase() in allowedHosts.map { it.lowercase() }.toSet()) { "Source host is not in allowed-hosts" }
        require(uri.fragment == null) { "Source URL has a fragment" }
        val addresses = resolve(host)
        require(addresses.isNotEmpty() && addresses.all(::isPublic)) { "Source host resolves to a non-public address" }
        return uri
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 16) {
            if ((b[0] and 254) == 252 || b.take(12) == List(10) { 0 } + listOf(255, 255)) return false
            return true
        }
        return b[0] != 0 && b[0] != 127 && b[0] != 10 && b[0] < 224 &&
            !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 169 && b[1] == 254) &&
            !(b[0] == 172 && b[1] in 16..31) && !(b[0] == 192 && b[1] == 168) &&
            !(b[0] == 198 && b[1] in 18..19)
    }
}
