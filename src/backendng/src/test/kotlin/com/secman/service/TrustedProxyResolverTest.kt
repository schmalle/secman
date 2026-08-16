package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Covers the trust boundary behind [AccountOnboardingPublicController]'s rate-limit key:
 * X-Forwarded-For must never let an unauthenticated caller pick its own bucket, and the
 * fallback to the raw TCP peer must not silently bucket every legitimate request together.
 */
class TrustedProxyResolverTest {

    private fun resolver(cidrs: String = "127.0.0.1/32,::1/128") = TrustedProxyResolver(cidrs)

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun `untrusted peer's X-Forwarded-For is ignored entirely`() {
        val r = resolver()
        // Peer is a public address, not the configured proxy — the header it sent must not be honoured.
        val result = r.resolveClientAddress(ip("203.0.113.5"), "9.9.9.9")
        assertThat(result).isEqualTo("203.0.113.5")
    }

    @Test
    fun `trusted peer's X-Forwarded-For right-most hop is used`() {
        val r = resolver()
        val result = r.resolveClientAddress(ip("127.0.0.1"), "198.51.100.7, 127.0.0.1")
        assertThat(result).isEqualTo("198.51.100.7")
    }

    @Test
    fun `spoofed left-most hop is not trusted over the real client appended by the proxy`() {
        val r = resolver()
        // Attacker sends "X-Forwarded-For: 9.9.9.9", nginx appends the real peer as the last hop.
        val result = r.resolveClientAddress(ip("127.0.0.1"), "9.9.9.9, 203.0.113.9")
        assertThat(result).isEqualTo("203.0.113.9")
    }

    @Test
    fun `every hop trusted falls back to the peer address`() {
        val r = resolver()
        val result = r.resolveClientAddress(ip("127.0.0.1"), "127.0.0.1, ::1")
        assertThat(result).isEqualTo("127.0.0.1")
    }

    @Test
    fun `missing header falls back to the peer address`() {
        val r = resolver()
        assertThat(r.resolveClientAddress(ip("127.0.0.1"), null)).isEqualTo("127.0.0.1")
        assertThat(r.resolveClientAddress(ip("127.0.0.1"), "")).isEqualTo("127.0.0.1")
    }

    @Test
    fun `unparsable hops are skipped rather than trusted`() {
        val r = resolver()
        val result = r.resolveClientAddress(ip("127.0.0.1"), "not-an-ip, 203.0.113.10")
        assertThat(result).isEqualTo("203.0.113.10")
    }

    @Test
    fun `a hostname in the header is never resolved via DNS`() {
        // Regression guard for the DNS-lookup-on-attacker-data trap: a hostname-looking hop
        // must be skipped, not passed to InetAddress.getByName (which would trigger a lookup).
        val r = resolver()
        val result = r.resolveClientAddress(ip("127.0.0.1"), "attacker.example, 203.0.113.11")
        assertThat(result).isEqualTo("203.0.113.11")
    }

    @Test
    fun `no trusted proxies configured means the header is never honoured`() {
        val r = resolver(cidrs = "")
        val result = r.resolveClientAddress(ip("127.0.0.1"), "203.0.113.12")
        assertThat(result).isEqualTo("127.0.0.1")
    }

    @Test
    fun `isTrusted reflects the configured CIDR`() {
        val r = resolver(cidrs = "10.0.0.0/8")
        assertThat(r.isTrusted(ip("10.1.2.3"))).isTrue()
        assertThat(r.isTrusted(ip("11.1.2.3"))).isFalse()
        assertThat(r.isTrusted(null)).isFalse()
    }
}
