package com.secman.service

import com.secman.domain.ComplianceStatus
import com.secman.repository.AssetComplianceHistoryRepository
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Integration tests for [AssetComplianceTrackingService].
 * Covers the AWS/EC2 population scoping, firstSeenAt SLA anchor, and
 * exception handling that align this service with [AwsCleanServerKpiService].
 */
open class AssetComplianceTrackingServiceTest : BaseIntegrationTest() {

    @Inject
    lateinit var trackingService: AssetComplianceTrackingService

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @Inject
    lateinit var historyRepository: AssetComplianceHistoryRepository

    @AfterEach
    fun tearDown() {
        historyRepository.deleteAll()
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    @Test
    fun `non-AWS asset without cloudInstanceId is skipped, no history row written`() {
        val asset = assetRepository.save(TestDataFactory.createAsset(name = "onprem-host"))
        vulnerabilityRepository.save(
            TestDataFactory.createVulnerabilityWithTimestamp(
                asset, "CVE-2024-0001", "High", LocalDateTime.now().minusDays(60)
            )
        )

        trackingService.trackComplianceAfterImport(asset.id!!, "TEST")

        assertThat(historyRepository.findByAssetIdOrderByChangedAtDesc(asset.id!!)).isEmpty()
    }

    @Test
    fun `vulnerability under an active exception does not count as overdue`() {
        val asset = assetRepository.save(
            TestDataFactory.createAsset(name = "aws-excepted").apply { cloudInstanceId = "i-excepted001" }
        )
        val vuln = TestDataFactory.createVulnerabilityWithTimestamp(
            asset, "CVE-2024-0002", "Critical", LocalDateTime.now().minusDays(60)
        )
        vuln.excepted = true
        vulnerabilityRepository.save(vuln)

        trackingService.trackComplianceAfterImport(asset.id!!, "TEST")

        val history = historyRepository.findByAssetIdOrderByChangedAtDesc(asset.id!!)
        assertThat(history).hasSize(1)
        assertThat(history.first().status).isEqualTo(ComplianceStatus.COMPLIANT)
    }

    @Test
    fun `overdue is anchored on firstSeenAt, not a recently-refreshed scanTimestamp`() {
        val asset = assetRepository.save(
            TestDataFactory.createAsset(name = "aws-reimported").apply { cloudInstanceId = "i-reimport001" }
        )
        // scanTimestamp looks fresh (as if just re-imported), but firstSeenAt shows the
        // vulnerability has really been open for 60 days — must still be NON_COMPLIANT.
        val vuln = TestDataFactory.createVulnerabilityWithTimestamp(
            asset, "CVE-2024-0003", "High", LocalDateTime.now()
        )
        vuln.firstSeenAt = LocalDateTime.now().minusDays(60)
        vulnerabilityRepository.save(vuln)

        trackingService.trackComplianceAfterImport(asset.id!!, "TEST")

        val history = historyRepository.findByAssetIdOrderByChangedAtDesc(asset.id!!)
        assertThat(history).hasSize(1)
        assertThat(history.first().status).isEqualTo(ComplianceStatus.NON_COMPLIANT)
        assertThat(history.first().oldestVulnDays).isGreaterThanOrEqualTo(60)
    }

    @Test
    fun `getSummary totals and percentage are computed over the AWS-scoped population`() {
        val baselineTotal = assetRepository.countAllAwsAssetsWithInstanceId()

        val compliantAsset = assetRepository.save(
            TestDataFactory.createAsset(name = "aws-clean").apply { cloudInstanceId = "i-clean001" }
        )
        val nonCompliantAsset = assetRepository.save(
            TestDataFactory.createAsset(name = "aws-dirty").apply { cloudInstanceId = "i-dirty001" }
        )
        // Never assessed: an AWS asset with no compliance history at all.
        assetRepository.save(
            TestDataFactory.createAsset(name = "aws-unassessed").apply { cloudInstanceId = "i-unassessed001" }
        )

        vulnerabilityRepository.save(
            TestDataFactory.createVulnerabilityWithTimestamp(
                nonCompliantAsset, "CVE-2024-0004", "High", LocalDateTime.now().minusDays(45)
            )
        )

        trackingService.trackComplianceAfterImport(compliantAsset.id!!, "TEST")
        trackingService.trackComplianceAfterImport(nonCompliantAsset.id!!, "TEST")

        val summary = trackingService.getSummary()

        assertThat(summary.totalAssets).isEqualTo(baselineTotal + 3)
        assertThat(summary.neverAssessedCount).isEqualTo(summary.totalAssets - summary.compliantCount - summary.nonCompliantCount)
        assertThat(summary.compliancePercentage)
            .isEqualTo((summary.compliantCount.toDouble() / summary.totalAssets * 100).let { Math.round(it * 10) / 10.0 })
    }
}
