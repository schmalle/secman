package com.secman.service

import com.secman.domain.Asset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class AssetCurrentnessPolicyTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val utc = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
    private fun asset() = Asset(name = "observed", type = "SERVER", owner = "ops")

    @Test
    fun `window includes both boundaries and rejects missing stale and future evidence`() {
        for ((seen, expected) in listOf(utc to true, utc.minusHours(72) to true,
                utc.minusHours(72).minusNanos(1000) to false, utc.plusSeconds(1) to false, null to false)) {
            assertThat(AssetCurrentnessPolicy.isCurrent(asset().apply { verifiedObservedAt = seen }, clock))
                .describedAs("observation=%s", seen).isEqualTo(expected)
        }
    }

    @Test
    fun `Falcon-managed inventory never falls back to a recent scan or import timestamp`() {
        val a = asset().apply {
            crowdStrikeHostname = name
            crowdStrikeLastImportedAt = utc
            lastSeen = utc
            verifiedObservedAt = utc
        }
        assertThat(AssetCurrentnessPolicy.isCurrent(a, clock)).isFalse()
        a.crowdStrikeAgentSeenAt = utc.minusHours(73)
        assertThat(AssetCurrentnessPolicy.isCurrent(a, clock)).isFalse()
        a.crowdStrikeAgentSeenAt = utc.minusHours(1)
        assertThat(AssetCurrentnessPolicy.isCurrent(a, clock)).isTrue()
    }

    @Test
    fun `established Falcon management survives owner and contact metadata changes`() {
        val a = asset().apply { crowdStrikeManaged = true; verifiedObservedAt = utc }
        assertThat(AssetCurrentnessPolicy.isCurrent(a, clock)).isFalse()
    }

    @Test
    fun `future and out-of-order observations do not replace verified evidence`() {
        val a = asset()
        a.recordObservation(utc.minusHours(1), utc)
        a.recordObservation(utc.minusHours(2), utc)
        a.recordObservation(utc.plusHours(1), utc)
        assertThat(a.verifiedObservedAt).isEqualTo(utc.minusHours(1))
    }
}
