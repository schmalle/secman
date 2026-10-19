package com.secman.crowdstrike.dto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class CrowdStrikeDeviceSelectionTest {
    private val first = Instant.parse("2026-01-01T00:00:00Z")
    private fun device(index: Int) = CrowdStrikeDeviceRecord("aid-$index", "ARCGIS",
        firstSeen = first.plusSeconds(index.toLong()), lastSeen = first.plusSeconds(100L + index))

    @Test
    fun `same hostname without machine continuity preserves every AID even with tied contacts`() {
        val records = (1..19).map { device(it).copy(lastSeen = first.plusSeconds(500)) }
        for (ordered in listOf(records, records.reversed())) {
            val selections = selectLatestCrowdStrikeDevices(ordered)
            assertThat(selections.map { it.selected.aid }).containsExactlyInAnyOrderElementsOf(records.map { it.aid })
            assertThat(selections.flatMap { it.superseded }).isEmpty()
        }
    }

    @Test
    fun `hostname recency chooses older enrollment without establishing replacement`() {
        val older = device(1).copy(lastSeen = first.plusSeconds(500))
        assertThat(selectMostRecentlySeenCrowdStrikeDevice(listOf(device(2), older))).isEqualTo(older)
        assertThat(selectLatestCrowdStrikeDevices(listOf(device(2), older))).hasSize(2)
    }

    @Test
    fun `known instance and account establish continuity across hostname and domain changes`() {
        val old = device(1).copy(instanceId = "i-shared", cloudAccountId = "account-a", adDomain = "old.test")
        val latest = device(2).copy(hostname = "renamed", instanceId = "i-shared", cloudAccountId = "ACCOUNT-A")
        val selection = selectLatestCrowdStrikeDevices(listOf(latest, old)).single()
        assertThat(selection.selected).isEqualTo(latest)
        assertThat(selection.superseded).containsExactly(old)
    }

    @Test
    fun `different instances accounts or missing account never bridge identities`() {
        val one = device(1).copy(instanceId = "i-one", cloudAccountId = "account-a")
        val two = device(2).copy(instanceId = "i-two", cloudAccountId = "account-a")
        val unknown = device(3).copy(instanceId = "i-one")
        val otherAccount = device(4).copy(instanceId = "i-one", cloudAccountId = "account-b")
        val noInstance = device(5).copy(cloudAccountId = "account-a")
        assertThat(selectLatestCrowdStrikeDevices(listOf(one, two, unknown, otherAccount, noInstance))).hasSize(5)
    }

    @Test
    fun `query default rejects tied missing future and pre-enrollment contact`() {
        val one = device(1)
        for (other in listOf(device(2).copy(lastSeen = one.lastSeen), device(2).copy(lastSeen = null),
            device(2).copy(lastSeen = Instant.now().plusSeconds(3600)), device(2).copy(lastSeen = first))) {
            assertThatThrownBy { selectMostRecentlySeenCrowdStrikeDevice(listOf(one, other)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { selectLatestCrowdStrikeDevices(listOf(one, one)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { selectLatestCrowdStrikeDevices(listOf(one.copy(firstSeen = null))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
