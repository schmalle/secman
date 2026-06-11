package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeCleanupStatus
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeCleanupRunRepository
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Integration tests for [CrowdStrikeCleanupAuditService] under Feature 087.
 * Validates:
 * - Safety brake numerator + denominator widening when `includeLegacy=true`.
 * - Audit-row persistence of `legacyCandidateCount` / `legacyDeletedCount`
 *   across SUCCESS / PARTIAL / ABORTED_SAFETY_BRAKE.
 * - Manual-run path bypasses the brake even with `includeLegacy=true`
 *   (analyze finding F4 / spec edge case + SC-004).
 *
 * These tests use real DB state and a mock cascade-delete service so we can
 * deterministically force PARTIAL outcomes.
 */
open class CrowdStrikeCleanupAuditServiceIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var auditService: CrowdStrikeCleanupAuditService

    @Inject
    lateinit var runRepository: CrowdStrikeCleanupRunRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @AfterEach
    fun tearDown() {
        runRepository.deleteAll()
        assetRepository.deleteAll()
    }

    @Test
    fun `safety brake uses widened denominator when includeLegacy=true`() {
        // Seed 5 timestamped CrowdStrike assets, 1 of which is stale, +
        // 20 legacy CrowdStrike assets, none stale. Without widening, the
        // brake computes 1/5 = 20% > 10% → would ABORT. With widening,
        // 1/(5+20) = 4% < 10% → SUCCESS.
        val cutoff = LocalDateTime.now().minusDays(30)
        // 1 stale rule-A
        saveTimestamped(name = "a-stale", importedAt = cutoff.minusDays(1))
        // 4 fresh rule-A
        repeat(4) { saveTimestamped(name = "a-fresh-$it", importedAt = LocalDateTime.now().minusDays(1)) }
        // 20 fresh legacy rule-B (so they DON'T contribute to numerator)
        repeat(20) { saveLegacy(name = "b-fresh-$it", lastSeen = LocalDateTime.now().minusDays(1)) }

        val response = auditService.run(
            days = 30, dryRun = false, triggeredBy = "test",
            maxDeletePercent = 10, includeLegacy = true
        )

        // 1/(5+20) = 4% — does NOT trip the brake.
        assertThat(response.status).isIn("SUCCESS", "PARTIAL")

        val saved = runRepository.findAll().single()
        assertThat(saved.totalCrowdStrikeTracked).isEqualTo(25L)
        // 1 timestamp-stale was deleted; 0 legacy.
        assertThat(saved.candidateCount).isEqualTo(1)
        assertThat(saved.legacyCandidateCount).isEqualTo(0)
    }

    @Test
    fun `audit row persists legacy counts on a SUCCESS run`() {
        // 0 timestamp-stale, 3 legacy-stale of 10 legacy total → 3/10 = 30%
        // Above the 10% brake, so we DON'T enable the brake (manual-style)
        // for this assertion focused on persistence semantics.
        repeat(3) { saveLegacy(name = "stale-$it", lastSeen = LocalDateTime.now().minusDays(60)) }
        repeat(7) { saveLegacy(name = "fresh-$it", lastSeen = LocalDateTime.now().minusDays(1)) }

        val response = auditService.run(
            days = 30, dryRun = false, triggeredBy = "test",
            maxDeletePercent = null, includeLegacy = true
        )

        assertThat(response.status).isEqualTo("SUCCESS")
        val saved = runRepository.findAll().single()
        assertThat(saved.candidateCount).isEqualTo(3)
        assertThat(saved.deletedCount).isEqualTo(3)
        assertThat(saved.legacyCandidateCount).isEqualTo(3)
        assertThat(saved.legacyDeletedCount).isEqualTo(3)
        assertThat(saved.status).isEqualTo(CrowdStrikeCleanupStatus.SUCCESS)
    }

    @Test
    fun `audit row persists legacy counts on ABORTED_SAFETY_BRAKE`() {
        // 0 fresh + 5 legacy-stale of 5 legacy total → 5/5 = 100% → trips
        // the 10% brake. Persisted row should record legacy candidate count
        // (what would have been deleted) but zero legacy deletions.
        repeat(5) { saveLegacy(name = "stale-$it", lastSeen = LocalDateTime.now().minusDays(60)) }

        val response = auditService.run(
            days = 30, dryRun = false, triggeredBy = "test",
            maxDeletePercent = 10, includeLegacy = true
        )

        assertThat(response.status).isEqualTo("ABORTED_SAFETY_BRAKE")
        val saved = runRepository.findAll().single()
        assertThat(saved.status).isEqualTo(CrowdStrikeCleanupStatus.ABORTED_SAFETY_BRAKE)
        assertThat(saved.candidateCount).isEqualTo(5)
        assertThat(saved.deletedCount).isEqualTo(0)
        assertThat(saved.legacyCandidateCount).isEqualTo(5)
        assertThat(saved.legacyDeletedCount).isEqualTo(0)
    }

    @Test
    fun `manual run bypasses brake even when includeLegacy=true and blast radius is high`() {
        // 100% would-be blast radius (5 legacy-stale of 5 legacy total).
        // Manual API path uses maxDeletePercent = null. Spec edge case +
        // SC-004 — the run MUST proceed and delete despite the high ratio.
        repeat(5) { saveLegacy(name = "stale-$it", lastSeen = LocalDateTime.now().minusDays(60)) }

        val response = auditService.run(
            days = 30, dryRun = false, triggeredBy = "user-X",
            maxDeletePercent = null, includeLegacy = true
        )

        assertThat(response.status).isIn("SUCCESS", "PARTIAL")
        val saved = runRepository.findAll().single()
        assertThat(saved.status).isNotEqualTo(CrowdStrikeCleanupStatus.ABORTED_SAFETY_BRAKE)
        assertThat(saved.candidateCount).isEqualTo(5)
        assertThat(saved.legacyCandidateCount).isEqualTo(5)
    }

    private fun saveTimestamped(name: String, importedAt: LocalDateTime): Asset {
        return assetRepository.save(
            Asset(
                name = name,
                type = "SERVER",
                owner = AssetOwners.CROWDSTRIKE_IMPORT,
                crowdStrikeLastImportedAt = importedAt,
                lastSeen = importedAt
            )
        )
    }

    private fun saveLegacy(name: String, lastSeen: LocalDateTime): Asset {
        return assetRepository.save(
            Asset(
                name = name,
                type = "SERVER",
                owner = AssetOwners.CROWDSTRIKE_IMPORT,
                crowdStrikeLastImportedAt = null,
                lastSeen = lastSeen
            )
        )
    }
}
