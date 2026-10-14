package com.secman.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class ScanObservationEvidenceTest {
    @Test
    fun `nmap normalizes source time to UTC and does not verify missing timestamps`() {
        val parser = NmapParserService()
        val dated = parser.parseNmapXml("<nmaprun start=\"1700000000\"/>".toByteArray())
        assertThat(dated.scanDateVerified).isTrue()
        assertThat(dated.scanDate).isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC))
        for (attribute in listOf("", " start=\"invalid\"")) {
            assertThat(parser.parseNmapXml("<nmaprun$attribute/>".toByteArray()).scanDateVerified).isFalse()
        }
    }

    @Test
    fun `masscan host evidence requires an actual endtime`() {
        val parser = MasscanParserService()
        for ((attribute, verified) in listOf(" endtime=\"1700000000\"" to true, "" to false)) {
            val xml = "<nmaprun scanner=\"masscan\"><host$attribute><address addr=\"192.0.2.1\" addrtype=\"ipv4\"/></host></nmaprun>"
            val host = parser.parseMasscanXml(xml.toByteArray()).hosts.single()
            assertThat(host.timestampVerified).isEqualTo(verified)
            if (verified) assertThat(host.timestamp).isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC))
        }
    }
}
