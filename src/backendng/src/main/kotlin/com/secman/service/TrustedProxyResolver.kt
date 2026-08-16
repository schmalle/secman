package com.secman.service

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import java.net.InetAddress

/**
 * Trust boundary for `X-Forwarded-For`: the header is honoured only when the immediate TCP
 * peer is inside one of [cidrs] (the reverse proxy). Mirrors
 * `src/relay/internal/httpx/httpx.go`'s `ResolveClientIP`/`clientIP` — same algorithm, same
 * reasoning: trusting the header unconditionally lets any caller pick its own rate-limit
 * bucket or audit-log identity by sending one header, and taking the *left-most* hop (the
 * naive approach) still trusts the caller, since that is exactly the entry the caller wrote
 * itself. Only the right-most hop that is not itself inside a trusted network is safe to use.
 *
 * Defaults to loopback only, matching every documented nginx deployment (nginx and the
 * backend on the same host — see `docs/DEPLOYMENT.md` and `scripts/install/nginx/nginx.conf`,
 * both of which `proxy_pass` to `127.0.0.1`).
 */
@Singleton
open class TrustedProxyResolver(
    @Value("\${secman.account-onboarding.trusted-proxy-cidrs:127.0.0.1/32,::1/128}")
    cidrs: String
) {
    private val networks: List<Pair<InetAddress, Int>> = cidrs.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { parseCidr(it) }

    /** Is [address] itself a trusted reverse proxy? */
    fun isTrusted(address: InetAddress?): Boolean {
        if (address == null) return false
        return networks.any { (network, prefixLength) -> matches(address, network, prefixLength) }
    }

    /**
     * The address to key rate limiting / audit logging on: [peer] unless it is a trusted
     * proxy, in which case the right-most `X-Forwarded-For` hop that is not itself trusted
     * (skipping our own infrastructure's hops). Falls back to [peer] if the header is absent,
     * unparsable, or every hop is trusted.
     */
    fun resolveClientAddress(peer: InetAddress?, forwardedFor: String?): String {
        val peerAddress = peer?.hostAddress ?: "unknown"
        if (!isTrusted(peer) || forwardedFor.isNullOrBlank()) {
            return peerAddress
        }
        val hops = forwardedFor.split(',').map { it.trim() }
        for (hop in hops.asReversed()) {
            val candidate = parseLiteral(hop) ?: continue
            if (isTrusted(candidate)) continue // another hop of our own infrastructure
            return candidate.hostAddress
        }
        return peerAddress
    }

    private fun parseCidr(cidr: String): Pair<InetAddress, Int>? {
        val parts = cidr.split('/')
        val address = parseLiteral(parts[0]) ?: return null
        val prefixLength = if (parts.size > 1) {
            parts[1].toIntOrNull() ?: return null
        } else {
            address.address.size * 8
        }
        if (prefixLength < 0 || prefixLength > address.address.size * 8) return null
        return address to prefixLength
    }

    /**
     * Parses a strict IP literal — never a hostname. `InetAddress.getByName` performs a real
     * DNS lookup for anything that is not already a numeric literal, and `forwardedFor` is
     * attacker-influenced (the caller controls every hop except the one the trusted proxy
     * itself appended), so resolving it as a hostname would let a header value trigger an
     * outbound DNS query. [IPV4_LITERAL] and [looksLikeIpv6Literal] gate that.
     */
    private fun parseLiteral(value: String): InetAddress? {
        if (!IPV4_LITERAL.matches(value) && !looksLikeIpv6Literal(value)) return null
        return try {
            InetAddress.getByName(value)
        } catch (e: Exception) {
            null
        }
    }

    private fun looksLikeIpv6Literal(value: String): Boolean =
        value.contains(':') && IPV6_LITERAL_CHARS.matches(value)

    private fun matches(address: InetAddress, network: InetAddress, prefixLength: Int): Boolean {
        val addrBytes = address.address
        val netBytes = network.address
        if (addrBytes.size != netBytes.size) return false
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (i in 0 until fullBytes) {
            if (addrBytes[i] != netBytes[i]) return false
        }
        if (remainingBits > 0) {
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            if ((addrBytes[fullBytes].toInt() and mask) != (netBytes[fullBytes].toInt() and mask)) return false
        }
        return true
    }

    companion object {
        private val IPV4_LITERAL = Regex(
            """^(25[0-5]|2[0-4]\d|1?\d{1,2})(\.(25[0-5]|2[0-4]\d|1?\d{1,2})){3}$"""
        )
        private val IPV6_LITERAL_CHARS = Regex("""^[0-9a-fA-F:]+(%[a-zA-Z0-9._-]+)?$""")
    }
}
