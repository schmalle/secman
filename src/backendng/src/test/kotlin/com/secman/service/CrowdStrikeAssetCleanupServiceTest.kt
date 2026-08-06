package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.domain.Asset
import com.secman.dto.CleanupCandidateReason
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
            service.cleanup(days = 0, dryRun = true, username = "admin", includeLegacy = false)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Days must be greater than zero")
    }

    @Test
    fun `dry run returns stale CrowdStrike assets without deleting them`() {
        val stale = asset(1L, "server-old", LocalDateTime.of(2026, 5, 1, 9, 0))
        every {
            assetRepository.findByCrowdStrikeLastImportedAtBefore(LocalDateTime.of(2026, 5, 4, 10, 0))
        } returns listOf(stale)

        val result = service.cleanup(days = 3, dryRun = true, username = "admin", includeLegacy = false)

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

        val result = service.cleanup(days = 5, dryRun = false, username = "admin", includeLegacy = false)

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

        val result = service.cleanup(days = 3, dryRun = true, username = "admin", includeLegacy = false)

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

        val result = service.cleanup(days = 10, dryRun = false, username = "admin", includeLegacy = false)

        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(result.deletedCount).isEqualTo(1)
        assertThat(result.errors).hasSize(1)
        assertThat(result.errors.first().assetName).isEqualTo("server-blocked")
        assertThat(result.errors.first().message).contains("referenced by risk")
    }

    // ---------- Feature 087 (rule B / legacy fence) ----------
    // The unit tests below cover service-level flag gating, dedup, and
    // response-field population. The actual JPQL fence (manualCreator,
    // scanUploader, COALESCE fall-through) is tested at the repository
    // level in AssetRepositoryLegacyStaleTest (T013, integration).

    @Test
    fun `includeLegacy=true picks rule-B candidates with LEGACY_NULL_TIMESTAMP reason`() {
        val legacy = legacyAsset(10L, "legacy-host")
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns emptyList()
        every {
            assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, any())
        } returns listOf(legacy)

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
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns emptyList()

        val result = service.cleanup(days = 30, dryRun = true, username = "admin", includeLegacy = false)

        // Service must NOT call findLegacyCrowdStrikeStale when flag is off.
        verify(exactly = 0) { assetRepository.findLegacyCrowdStrikeStale(any(), any()) }
        assertThat(result.candidateCount).isEqualTo(0)
        assertThat(result.legacyCandidateCount).isEqualTo(0)
        // Reference legacy var so the compiler doesn't warn — confirms the
        // legacy asset would have been visible if the repo were queried.
        assertThat(legacy.id).isEqualTo(11L)
    }

    @Test
    fun `mixed batch with same id in both rules is deduped to a single candidate`() {
        // Defensive belt-and-braces test: the JPQL predicates of rule A
        // (timestamp < cutoff) and rule B (timestamp IS NULL) are mutually
        // exclusive in SQL, so this state cannot arise in production. The
        // service still de-duplicates by id to protect against future
        // predicate widening — see spec FR-005 + clarification session.
        val sharedId = 42L
        val timestampStale = asset(sharedId, "shared-host", LocalDateTime.of(2026, 4, 1, 10, 0))
        val legacyDup = legacyAsset(sharedId, "shared-host")

        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns listOf(timestampStale)
        every {
            assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, any())
        } returns listOf(legacyDup)

        val result = service.cleanup(days = 30, dryRun = true, username = "admin", includeLegacy = true)

        // Deduped to one. Rule A is evaluated first, so the surviving
        // candidate carries TIMESTAMP_STALE.
        assertThat(result.candidates).hasSize(1)
        assertThat(result.candidates.first().reason).isEqualTo(CleanupCandidateReason.TIMESTAMP_STALE)
        assertThat(result.candidateCount).isEqualTo(1)
    }

    @Test
    fun `mixed batch with distinct ids surfaces both rules' candidates with their reasons`() {
        val timestampStale = asset(20L, "ts-host", LocalDateTime.of(2026, 4, 1, 10, 0))
        val legacy = legacyAsset(21L, "legacy-host")
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns listOf(timestampStale)
        every {
            assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, any())
        } returns listOf(legacy)

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
        every { assetRepository.findByCrowdStrikeLastImportedAtBefore(any()) } returns emptyList()
        every {
            assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, any())
        } returns listOf(legacy1, legacy2)
        every {
            assetCascadeDeleteService.deleteAsset(any(), any(), any(), any())
        } returns mockk(relaxed = true)

        val result = service.cleanup(days = 30, dryRun = false, username = "admin", includeLegacy = true)

        assertThat(result.candidateCount).isEqualTo(2)
        assertThat(result.deletedCount).isEqualTo(2)
        assertThat(result.legacyCandidateCount).isEqualTo(2)
        assertThat(result.legacyDeletedCount).isEqualTo(2)
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
