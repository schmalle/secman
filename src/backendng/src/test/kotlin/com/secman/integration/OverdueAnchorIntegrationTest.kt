package com.secman.integration

import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The materialized-view refresh must use the same SLA anchor as the live overdue
 * badge (VulnerabilityService.calculateOverdueStatus): firstSeenAt when present,
 * scanTimestamp as legacy fallback. scanTimestamp drifts on every re-import
 * (CrowdStrike recomputes it from daysOpen; XLSX re-imports reset it), so
 * anchoring the refresh on it makes the dashboard's "Overdue Patching" card
 * disagree with the Current Vulnerabilities view.
 */
@DisplayName("Overdue SLA anchor: view refresh query vs live badge")
class OverdueAnchorIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    private val threshold: LocalDateTime = LocalDateTime.now().minusDays(30)

    @AfterEach
    fun cleanup() {
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    @Test
    @DisplayName("finds vulnerability whose firstSeenAt is past the threshold even when scanTimestamp is fresh")
    fun `firstSeenAt anchors the overdue query`() {
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "anchor-asset-1"))
        val vuln = TestDataFactory.createVulnerabilityWithTimestamp(
            asset = asset,
            cve = "CVE-2026-1111",
            severity = "High",
            scanTimestamp = LocalDateTime.now().minusDays(2) // refreshed by a re-import
        )
        vuln.firstSeenAt = LocalDateTime.now().minusDays(40) // true SLA anchor
        vulnerabilityRepository.save(vuln)

        val overdue = vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(threshold)

        assertThat(overdue.map { it.vulnerabilityId }).contains("CVE-2026-1111")
    }

    @Test
    @DisplayName("falls back to scanTimestamp for legacy rows without firstSeenAt")
    fun `scanTimestamp fallback still works`() {
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "anchor-asset-2"))
        val vuln = TestDataFactory.createVulnerabilityWithTimestamp(
            asset = asset,
            cve = "CVE-2026-2222",
            severity = "High",
            scanTimestamp = LocalDateTime.now().minusDays(40)
        )
        vuln.firstSeenAt = null
        vulnerabilityRepository.save(vuln)

        val overdue = vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(threshold)

        assertThat(overdue.map { it.vulnerabilityId }).contains("CVE-2026-2222")
    }

    @Test
    @DisplayName("does not report a vulnerability whose firstSeenAt is within the threshold")
    fun `recent firstSeenAt is not overdue`() {
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "anchor-asset-3"))
        val vuln = TestDataFactory.createVulnerabilityWithTimestamp(
            asset = asset,
            cve = "CVE-2026-3333",
            severity = "High",
            scanTimestamp = LocalDateTime.now().minusDays(40)
        )
        vuln.firstSeenAt = LocalDateTime.now().minusDays(10)
        vulnerabilityRepository.save(vuln)

        val overdue = vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(threshold)

        assertThat(overdue.map { it.vulnerabilityId }).doesNotContain("CVE-2026-3333")
    }
}
