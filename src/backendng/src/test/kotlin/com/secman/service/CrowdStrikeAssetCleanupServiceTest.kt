package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.domain.Asset
import com.secman.dto.CleanupCandidateReason
import com.secman.repository.AssetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
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
    private val firstPage = Pageable.from(0, 200, Sort.of(Sort.Order.asc("id")))

    @BeforeEach
    fun setUp() {
        assetRepository = mockk()
        assetCascadeDeleteService = mockk()
        service = CrowdStrikeAssetCleanupService(assetRepository, assetCascadeDeleteService, clock)
    }

    @Test
    fun `local cleanup cutoff is converted to UTC for Falcon contact`() {
        val berlinClock = clock.withZone(java.time.ZoneId.of("Europe/Berlin"))
        val localCutoff = LocalDateTime.of(2026, 5, 4, 12, 0)
        val utcCutoff = LocalDateTime.of(2026, 5, 4, 10, 0)
        val stale = asset(1L, "stale-contact", localCutoff.minusDays(1)).apply {
            crowdStrikeAgentSeenAt = utcCutoff.minusSeconds(1)
        }
        val recent = asset(2L, "contact-at-cutoff", localCutoff.minusDays(1)).apply {
            crowdStrikeAgentSeenAt = utcCutoff
        }
        population(stale, recent)
        val berlinService = CrowdStrikeAssetCleanupService(assetRepository, assetCascadeDeleteService, berlinClock)

        val result = berlinService.cleanup(days = 3, dryRun = true, username = "admin", includeLegacy = false)

        assertThat(result.cutoff).isEqualTo(localCutoff)
        assertThat(result.cutoffUtc).isEqualTo(utcCutoff.toInstant(ZoneOffset.UTC))
        assertThat(result.candidates.map { it.assetId }).containsExactly(1L)
        assertThat(result.exclusions).containsEntry("RECENT_OR_FUTURE_SENSOR_CONTACT", 1L)
    }

    @Test
    fun `rejects non-positive day threshold`() {
        for (days in listOf(-1, 0, 36501)) {
            assertThatThrownBy {
                service.cleanup(days = days, dryRun = true, username = "admin", includeLegacy = false)
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Days must be between 1 and 36500")
        }
        verify(exactly = 0) { assetRepository.findCleanupPopulation(any(), any()) }
    }

    @Test
    fun `cutoff override is used verbatim for candidate selection`() {
        // The audit service computes ONE cutoff per run and passes it here, so the
        // safety brake and the deletion select the same population.
        val override = LocalDateTime.of(2026, 1, 1, 0, 0)
        population(asset(1L, "before-cutoff", override.minusSeconds(1)), asset(2L, "at-cutoff", override))

        val result = service.cleanup(
            days = 30, dryRun = true, username = "admin",
            includeLegacy = false, cutoffOverride = override
        )

        assertThat(result.cutoff).isEqualTo(override)
        assertThat(result.candidates.map { it.assetId }).containsExactly(1L)
        verify(exactly = 1) { assetRepository.findCleanupPopulation(AssetOwners.CROWDSTRIKE_IMPORT, firstPage) }
    }

    @Test
    fun `dry run returns stale CrowdStrike assets without deleting them`() {
        val stale = asset(1L, "server-old", LocalDateTime.of(2026, 5, 1, 9, 0))
        population(stale)

        val result = service.cleanup(days = 3, dryRun = true, username = "admin", includeLegacy = false)

        assertThat(result.dryRun).isTrue()
        assertThat(result.candidateCount).isEqualTo(1)
        assertThat(result.deletedCount).isEqualTo(0)
        assertThat(result.candidates).extracting("name").containsExactly("server-old")
        verify(exactly = 0) { assetCascadeDeleteService.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `delete mode deletes stale CrowdStrike assets through cascade service`() {
        val stale = asset(2L, "server-delete", LocalDateTime.of(2026, 4, 30, 10, 0))
        population(stale)
        val eligibility = slot<(Asset) -> Boolean>()
        every {
            assetCascadeDeleteService.deleteAsset(2L, "admin", forceTimeout = true, bulkOperationId = any(),
                cleanupEligibility = capture(eligibility))
        } returns mockk(relaxed = true)

        val result = service.cleanup(days = 5, dryRun = false, username = "admin", includeLegacy = false)

        assertThat(result.dryRun).isFalse()
        assertThat(result.candidateCount).isEqualTo(1)
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.errors).isEmpty()
        verify(exactly = 1) {
            assetCascadeDeleteService.deleteAsset(2L, "admin", forceTimeout = true, bulkOperationId = any(), cleanupEligibility = any())
        }
        assertThat(eligibility.captured(stale)).isTrue()
        assertThat(eligibility.captured(stale.copy(crowdStrikeLastImportedAt = LocalDateTime.now(clock)))).isFalse()
    }

    @Test
    fun `ignores assets without CrowdStrike import timestamp even if repository returns them`() {
        val withoutTimestamp = asset(3L, "manual-server", null)
        population(withoutTimestamp)

        val result = service.cleanup(days = 3, dryRun = true, username = "admin", includeLegacy = false)

        assertThat(result.candidateCount).isEqualTo(0)
        assertThat(result.candidates).isEmpty()
    }

    @Test
    fun `reports per asset deletion failures without aborting later deletions`() {
        val blocked = asset(4L, "server-blocked", LocalDateTime.of(2026, 4, 20, 10, 0))
        val deleted = asset(5L, "server-deleted", LocalDateTime.of(2026, 4, 21, 10, 0))
        population(blocked, deleted)
        every {
            assetCascadeDeleteService.deleteAsset(4L, "admin", forceTimeout = true, bulkOperationId = any(), cleanupEligibility = any())
        } throws IllegalStateException("referenced by risk")
        every {
            assetCascadeDeleteService.deleteAsset(5L, "admin", forceTimeout = true, bulkOperationId = any(), cleanupEligibility = any())
        } returns mockk(relaxed = true)

        val result = service.cleanup(days = 10, dryRun = false, username = "admin", includeLegacy = false)

        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors.first().assetName).isEqualTo("server-blocked")
        assertThat(result.errors.first().message).isEqualTo("Deletion failed; existing data was preserved. See backend log for details")
        assertThat(result.errors.first().message).doesNotContain("referenced by risk")
        verify(exactly = 1) {
            assetCascadeDeleteService.deleteAsset(5L, "admin", forceTimeout = true, bulkOperationId = any(), cleanupEligibility = any())
        }
    }

    // ---------- Feature 087 (rule B / legacy fence) ----------
    // The unit tests below cover service-level flag gating, pagination, and
    // response-field population. The actual JPQL fence (manualCreator,
    // scanUploader, COALESCE fall-through) is tested at the repository
    // level in AssetRepositoryLegacyStaleTest (T013, integration).

    @Test
    fun `includeLegacy=true picks rule-B candidates with LEGACY_NULL_TIMESTAMP reason`() {
        val legacy = legacyAsset(10L, "legacy-host")
        population(legacy)

        val result = service.cleanup(days = 30, dryRun = true, username = "admin", includeLegacy = true)

        assertThat(result.candidateCount).isEqualTo(1)
        assertThat(result.candidates).hasSize(1)
        with(result.candidates.first()) {
            assertThat(assetId).isEqualTo(10L)
            assertThat(name).isEqualTo("legacy-host")
            assertThat(crowdStrikeLastImportedAt).isNull()
            assertThat(reason).isEqualTo(CleanupCandidateReason.LEGACY_NULL_TIMESTAMP)
        }
        assertThat(result.legacyCandidateCount).isEqualTo(1)
    }

    @Test
    fun `includeLegacy=false ignores rule-B even if repository would return legacy assets`() {
        val legacy = legacyAsset(11L, "would-be-legacy")
        population(legacy)

        val result = service.cleanup(days = 30, dryRun = true, username = "admin", includeLegacy = false)

        assertThat(result.candidateCount).isEqualTo(0)
        assertThat(result.legacyCandidateCount).isEqualTo(0)
        assertThat(result.exclusions).containsEntry("LEGACY_DISABLED", 1L)
    }

    @Test
    fun `paginated population includes candidates from both rules across page boundaries`() {
        val timestampStale = (1L..200L).map { asset(it, "stale-$it", LocalDateTime.of(2026, 4, 1, 10, 0)) }
        val legacy = legacyAsset(201L, "legacy-next-page")
        val secondPage = Pageable.from(1, 200, Sort.of(Sort.Order.asc("id")))
        every { assetRepository.findCleanupPopulation(AssetOwners.CROWDSTRIKE_IMPORT, firstPage) } returns
            Page.of(timestampStale, firstPage, 201)
        every {
            assetRepository.findCleanupPopulation(AssetOwners.CROWDSTRIKE_IMPORT, secondPage)
        } returns Page.of(listOf(legacy), secondPage, 201)

        val result = service.cleanup(days = 30, dryRun = true, username = "admin", includeLegacy = true)

        assertThat(result.candidateCount).isEqualTo(201)
        assertThat(result.candidates.map { it.assetId }).containsExactlyElementsOf(1L..201L)
        assertThat(result.candidates.take(200).map { it.reason }).containsOnly(CleanupCandidateReason.TIMESTAMP_STALE)
        assertThat(result.candidates.last().reason).isEqualTo(CleanupCandidateReason.LEGACY_NULL_TIMESTAMP)
        verify(exactly = 1) { assetRepository.findCleanupPopulation(AssetOwners.CROWDSTRIKE_IMPORT, firstPage) }
        verify(exactly = 1) { assetRepository.findCleanupPopulation(AssetOwners.CROWDSTRIKE_IMPORT, secondPage) }
    }

    @Test
    fun `mixed batch with distinct ids surfaces both rules' candidates with their reasons`() {
        val timestampStale = asset(20L, "ts-host", LocalDateTime.of(2026, 4, 1, 10, 0))
        val legacy = legacyAsset(21L, "legacy-host")
        population(timestampStale, legacy)

        val result = service.cleanup(days = 30, dryRun = true, username = "admin", includeLegacy = true)

        assertThat(result.candidates).hasSize(2)
        assertThat(result.candidates.map { it.reason }).containsExactlyInAnyOrder(
            CleanupCandidateReason.TIMESTAMP_STALE,
            CleanupCandidateReason.LEGACY_NULL_TIMESTAMP
        )
        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(result.legacyCandidateCount).isEqualTo(1)
    }

    @Test
    fun `real run with legacy candidates populates legacyDeletedCount per rule attribution`() {
        val legacy1 = legacyAsset(100L, "legacy-a")
        val legacy2 = legacyAsset(101L, "legacy-b")
        population(legacy1, legacy2)
        every {
            assetCascadeDeleteService.deleteAsset(any(), any(), any(), any(), any())
        } returns mockk(relaxed = true)

        val result = service.cleanup(days = 30, dryRun = false, username = "admin", includeLegacy = true)

        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(result.deletedCount).isEqualTo(2)
        assertThat(result.legacyCandidateCount).isEqualTo(2)
        assertThat(result.legacyDeletedCount).isEqualTo(2)
    }

    private fun population(vararg assets: Asset) {
        every { assetRepository.findCleanupPopulation(AssetOwners.CROWDSTRIKE_IMPORT, firstPage) } returns
            Page.of(assets.toList(), firstPage, assets.size.toLong())
    }

    private fun asset(id: Long, name: String, importedAt: LocalDateTime?) =
        Asset(
            id = id,
            name = name,
            type = "SERVER",
            owner = AssetOwners.CROWDSTRIKE_IMPORT,
            crowdStrikeLastImportedAt = importedAt
        )

    private fun legacyAsset(id: Long, name: String) =
        Asset(
            id = id,
            name = name,
            type = "SERVER",
            owner = AssetOwners.CROWDSTRIKE_IMPORT,
            crowdStrikeLastImportedAt = null,
            lastSeen = LocalDateTime.of(2026, 3, 1, 10, 0)
        )
}
