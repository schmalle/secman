package com.secman.service

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class CrowdStrikeAssetCleanupServiceTest {

    private lateinit var assetRepository: AssetRepository
    private lateinit var assetCascadeDeleteService: AssetCascadeDeleteService
    private lateinit var service: CrowdStrikeAssetCleanupService

    private val clock = Clock.fixed(Instant.parse("2026-05-07T10:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        assetRepository = mockk()
        assetCascadeDeleteService = mockk()
        service = CrowdStrikeAssetCleanupService(assetRepository, assetCascadeDeleteService, clock)
    }

    @Test
    fun `rejects non-positive day threshold`() {
        assertThatThrownBy {
            service.cleanup(days = 0, dryRun = true, username = "admin")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Days must be greater than zero")
    }

    @Test
    fun `dry run returns stale CrowdStrike assets without deleting them`() {
        val stale = asset(1L, "server-old", LocalDateTime.of(2026, 5, 1, 9, 0))
        every {
            assetRepository.findByCrowdStrikeLastImportedAtBefore(LocalDateTime.of(2026, 5, 4, 10, 0))
        } returns listOf(stale)

        val result = service.cleanup(days = 3, dryRun = true, username = "admin")

        assertThat(result.dryRun).isTrue()
        assertThat(result.candidateCount).isEqualTo(1)
        assertThat(result.deletedCount).isEqualTo(0)
        assertThat(result.candidates).extracting("name").containsExactly("server-old")
        verify(exactly = 0) { assetCascadeDeleteService.deleteAsset(any(), any(), any(), any()) }
    }

    @Test
    fun `delete mode deletes stale CrowdStrike assets through cascade service`() {
        val stale = asset(2L, "server-delete", LocalDateTime.of(2026, 4, 30, 10, 0))
        every {
            assetRepository.findByCrowdStrikeLastImportedAtBefore(LocalDateTime.of(2026, 5, 2, 10, 0))
        } returns listOf(stale)
        every {
            assetCascadeDeleteService.deleteAsset(2L, "admin", forceTimeout = true, bulkOperationId = any())
        } returns mockk(relaxed = true)

        val result = service.cleanup(days = 5, dryRun = false, username = "admin")

        assertThat(result.dryRun).isFalse()
        assertThat(result.candidateCount).isEqualTo(1)
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.errors).isEmpty()
        verify(exactly = 1) {
            assetCascadeDeleteService.deleteAsset(2L, "admin", forceTimeout = true, bulkOperationId = any())
        }
    }

    @Test
    fun `ignores assets without CrowdStrike import timestamp even if repository returns them`() {
        val withoutTimestamp = asset(3L, "manual-server", null)
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns listOf(withoutTimestamp)

        val result = service.cleanup(days = 3, dryRun = true, username = "admin")

        assertThat(result.candidateCount).isEqualTo(0)
        assertThat(result.candidates).isEmpty()
    }

    @Test
    fun `reports per asset deletion failures without aborting later deletions`() {
        val blocked = asset(4L, "server-blocked", LocalDateTime.of(2026, 4, 20, 10, 0))
        val deleted = asset(5L, "server-deleted", LocalDateTime.of(2026, 4, 21, 10, 0))
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns listOf(blocked, deleted)
        every {
            assetCascadeDeleteService.deleteAsset(4L, "admin", forceTimeout = true, bulkOperationId = any())
        } throws IllegalStateException("referenced by risk")
        every {
            assetCascadeDeleteService.deleteAsset(5L, "admin", forceTimeout = true, bulkOperationId = any())
        } returns mockk(relaxed = true)

        val result = service.cleanup(days = 10, dryRun = false, username = "admin")

        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors.first().assetName).isEqualTo("server-blocked")
        assertThat(result.errors.first().message).contains("referenced by risk")
    }

    private fun asset(id: Long, name: String, importedAt: LocalDateTime?) =
        Asset(
            id = id,
            name = name,
            type = "SERVER",
            owner = "CrowdStrike Import",
            crowdStrikeLastImportedAt = importedAt
        )
}
