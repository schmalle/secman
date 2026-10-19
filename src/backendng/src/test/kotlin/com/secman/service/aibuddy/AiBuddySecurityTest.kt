package com.secman.service.aibuddy

import com.secman.service.OutboundUrlPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.URI

class AiBuddySecurityTest {
    @Test fun `HTTPS policy rejects aliases credentials redirects and internal address families`() {
        for (address in listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:10.0.0.1")) {
            assertThat(OutboundUrlPolicy.isPublic(InetAddress.getByName(address))).isFalse()
            assertThrows<IllegalArgumentException> { OutboundUrlPolicy.validate(URI("https://vendor.example"), setOf("vendor.example")) { arrayOf(InetAddress.getByName(address)) } }
        }
        for (url in listOf("http://vendor.example", "https://vendor.example.evil.test", "https://user:password@vendor.example", "https://vendor.example:8080", "https://vendor.example/#fragment")) {
            assertThrows<IllegalArgumentException> { OutboundUrlPolicy.validate(URI(url), setOf("vendor.example")) { arrayOf(InetAddress.getByName("8.8.8.8")) } }
        }
        assertThat(OutboundUrlPolicy.validate(URI("https://vendor.example/source"), setOf("vendor.example")) { arrayOf(InetAddress.getByName("8.8.8.8")) }.path).isEqualTo("/source")
        assertThrows<IllegalArgumentException> { OutboundUrlPolicy.validate(URI("https://vendor.example/source"), setOf("vendor.example")) { arrayOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")) } }
    }
}
