package com.secman.service

import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.domain.CrowdStrikeSnapshot
import com.secman.repository.CrowdStrikeSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class CrowdStrikeSnapshotServiceTest {
    private val repository = mockk<CrowdStrikeSnapshotRepository>(relaxed = true)
    init { every { repository.update(any<CrowdStrikeSnapshot>()) } answers { firstArg() } }
    private val service = CrowdStrikeSnapshotService(repository)
    private val observed = Instant.parse("2026-01-03T00:00:00Z")
    private val selection = CrowdStrikeDeviceSelection(CrowdStrikeDeviceRecord("aid-current", "server.example.test",
        firstSeen = observed.minusSeconds(100)), observedAt = observed)

    @Test
    fun `an explicitly empty severity declaration is not proof of all-severity coverage`() {
        assertThatThrownBy { service.replace(7, selection, emptyList(), false) }
            .hasMessageContaining("Invalid snapshot severity coverage")
        verify(exactly = 0) { repository.update(any<CrowdStrikeSnapshot>()) }
        verify(exactly = 0) { repository.save(any<CrowdStrikeSnapshot>()) }
    }

    @Test
    fun `older concurrent response cannot change snapshot coverage`() {
        val current = CrowdStrikeSnapshot(id = 1, assetId = 7, crowdStrikeAid = "aid-current", severity = "HIGH",
            observedAt = LocalDateTime.ofInstant(observed.plusSeconds(1), ZoneOffset.UTC))
        every { repository.findByAssetIdAndActive(7, true) } returns listOf(current)
        assertThatThrownBy { service.replace(7, selection, listOf("HIGH"), false) }
            .hasMessageContaining("older Falcon snapshot")
        verify(exactly = 0) { repository.update(any()) }
        verify(exactly = 0) { repository.save(any<CrowdStrikeSnapshot>()) }
    }

    @Test
    fun `scoped same enrollment refresh preserves other verified coverage`() {
        val high = CrowdStrikeSnapshot(id = 1, assetId = 7, crowdStrikeAid = "aid-current", severity = "HIGH",
            observedAt = LocalDateTime.ofInstant(observed.minusSeconds(1), ZoneOffset.UTC))
        val low = high.copy(id = 2, severity = "LOW")
        every { repository.findByAssetIdAndActive(7, true) } returns listOf(high, low)
        every { repository.save(any<CrowdStrikeSnapshot>()) } answers { firstArg<CrowdStrikeSnapshot>().copy(id = 3) }
        assertThat(service.replace(7, selection, listOf("HIGH"), false)).containsEntry("HIGH", 3)
        assertThat(high.active).isFalse()
        assertThat(low.active).isTrue()
    }
}
