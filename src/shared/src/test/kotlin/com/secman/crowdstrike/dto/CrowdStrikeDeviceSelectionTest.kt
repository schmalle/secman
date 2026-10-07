package com.secman.crowdstrike.dto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class CrowdStrikeDeviceSelectionTest {
    private val first = Instant.parse("2026-01-01T00:00:00Z")
    private fun device(index: Int) = CrowdStrikeDeviceRecord("aid-$index", "server.example.test",
        firstSeen = first.plusSeconds(index.toLong()), lastSeen = first.plusSeconds(100))

    @Test
    fun `nineteen enrollments select one newest device regardless of response order`() {
        val records = (1..19).map(::device).reversed()
        val selection = selectLatestCrowdStrikeDevices(records).single()
        assertThat(selection.selected.aid).isEqualTo("aid-19")
        assertThat(selection.superseded.map { it.aid }).containsExactlyElementsOf((18 downTo 1).map { "aid-$it" })
    }

    @Test
    fun `newest enrollment wins even when an older device checked in later`() {
        val older = device(1).copy(lastSeen = first.plusSeconds(500))
        assertThat(selectLatestCrowdStrikeDevices(listOf(older, device(2))).single().selected.aid).isEqualTo("aid-2")
    }

    @Test
    fun `last seen breaks an enrollment timestamp tie`() {
        val older = device(1).copy(firstSeen = device(2).firstSeen, lastSeen = first.plusSeconds(10))
        assertThat(selectLatestCrowdStrikeDevices(listOf(older, device(2))).single().selected.aid).isEqualTo("aid-2")
    }

    @Test
    fun `equal timestamps and missing timestamps fail closed`() {
        assertThatThrownBy { selectLatestCrowdStrikeDevices(listOf(device(1), device(1).copy(aid = "other"))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Ambiguous")
        assertThatThrownBy { selectLatestCrowdStrikeDevices(listOf(device(1), device(2).copy(firstSeen = null))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Incomplete")
    }

    @Test
    fun `short name collisions and account or domain differences remain independent`() {
        val records = listOf(device(1).copy(cloudAccountId = "account-a"),
            device(2).copy(hostname = "server.other.test", cloudAccountId = "account-a"),
            device(3).copy(cloudAccountId = "account-b"), device(4).copy(adDomain = "other.test", cloudAccountId = "account-a"))
        assertThat(selectLatestCrowdStrikeDevices(records)).hasSize(4)
    }

    @Test
    fun `missing account records join the sole known hostname account but never bridge conflicting accounts`() {
        val records = (1..19).map { device(it).copy(cloudAccountId = if (it <= 4) null else "account-a") }
        val selection = selectLatestCrowdStrikeDevices(records).single()
        assertThat(selection.selected.aid).isEqualTo("aid-19")
        assertThat(selection.superseded).hasSize(18)
        val conflicting = listOf(device(1), device(2).copy(cloudAccountId = "account-a"),
            device(3).copy(cloudAccountId = "account-b"))
        assertThat(selectLatestCrowdStrikeDevices(conflicting)).hasSize(3)
    }

    @Test
    fun `cloud instance aliases stay together across domain joins but accounts stay separate`() {
        val old = device(1).copy(instanceId = "i-shared", cloudAccountId = "account-a", adDomain = "old.test")
        val newest = device(2).copy(hostname = "new-name", instanceId = "i-shared", cloudAccountId = "account-a")
        val otherAccount = device(3).copy(instanceId = "i-shared", cloudAccountId = "account-b")
        val selections = selectLatestCrowdStrikeDevices(listOf(old, newest, otherAccount))
        assertThat(selections).hasSize(2)
        assertThat(selections.first { it.selected.aid == newest.aid }.superseded).containsExactly(old)
    }

    @Test
    fun `hostname comparison is case insensitive and cloud aliases keep one winner`() {
        val older = device(1).copy(hostname = "OLD.example.test", instanceId = "i-shared", cloudAccountId = "account-a")
        val newer = device(2).copy(instanceId = "i-shared", cloudAccountId = "ACCOUNT-A")
        assertThat(selectLatestCrowdStrikeDevices(listOf(older, newer)).single().selected).isEqualTo(newer)
        assertThat(selectLatestCrowdStrikeDevices(listOf(device(1), device(2).copy(hostname = "SERVER.EXAMPLE.TEST"))))
            .hasSize(1)
    }
}
