package com.secman.integration

import com.secman.domain.Asset
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.AwsCleanServerKpiService
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** Verifies the database predicates behind the AWS clean-server KPI. */
@DisplayName("AWS clean-server KPI: detection age")
class AwsCleanServerKpiCountIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @AfterEach
    fun cleanup() {
        vulnerabilityRepository.deleteAll()
        assetRepository.deleteAll()
    }

    @Test
    @DisplayName("uses host detection age rather than the remediation SLA anchor")
    fun detectionAgeDefinesOldVulnerabilities() {
        val recentlyDetected = awsAsset("recent-detection", "i-0000000000000018")
        val oldPatch = TestDataFactory.createVulnerabilityWithTimestamp(
            recentlyDetected,
            "CVE-2026-0018",
            "High",
            LocalDateTime.now().minusDays(5)
        ).apply {
            firstSeenAt = LocalDateTime.now().minusDays(100)
        }
        vulnerabilityRepository.save(oldPatch)

        val threshold = LocalDateTime.now().minusDays(AwsCleanServerKpiService.VULN_AGE_THRESHOLD_DAYS)

        assertThat(vulnerabilityRepository.countDirtyAwsServers(threshold)).isZero()
    }

    @Test
    @DisplayName("counts each AWS server once when it has an old detected vulnerability")
    fun oldDetectionCountsDistinctAwsServers() {
        val oldDetection = LocalDateTime.now().minusDays(40)
        val affected = awsAsset("old-detection", "i-0000000000000019")
        vulnerabilityRepository.save(
            TestDataFactory.createVulnerabilityWithTimestamp(affected, "CVE-2026-0019", "High", oldDetection)
        )
        vulnerabilityRepository.save(
            TestDataFactory.createVulnerabilityWithTimestamp(affected, "CVE-2026-0020", "Critical", oldDetection)
        )
        val nonAwsAsset = assetRepository.save(Asset(name = "on-prem", type = "SERVER", owner = "ops"))
        vulnerabilityRepository.save(
            TestDataFactory.createVulnerabilityWithTimestamp(nonAwsAsset, "CVE-2026-0021", "High", oldDetection)
        )

        val threshold = LocalDateTime.now().minusDays(AwsCleanServerKpiService.VULN_AGE_THRESHOLD_DAYS)

        assertThat(vulnerabilityRepository.countDirtyAwsServers(threshold)).isEqualTo(1L)
    }

    private fun awsAsset(name: String, instanceId: String): Asset = assetRepository.save(
        Asset(
            name = name,
            type = "SERVER",
            owner = "ops",
            cloudAccountId = "111122223333",
            cloudInstanceId = instanceId
        )
    )
}
