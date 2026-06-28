package com.secman.service

import com.secman.constants.AssetTypes
import com.secman.domain.ExecutionStatus
import com.secman.dto.DependabotAlertDto
import com.secman.dto.GitHubDependabotBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for the GitHub Dependabot import + repository notification
 * path against a real database. Validates:
 *  - repository asset auto-creation (type REPOSITORY) and vulnerability persistence
 *  - idempotency / remediation via the per-repo transactional replace
 *  - the repository-notification native query (incl. the exception-match join)
 */
open class GitHubDependabotIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var importService: GitHubDependabotImportService

    @Inject
    lateinit var notificationService: RepositoryVulnerabilityNotificationService

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var vulnerabilityRepository: VulnerabilityRepository

    @AfterEach
    fun tearDown() {
        assetRepository.findByNameIgnoreCase("octo/sample")?.let { asset ->
            vulnerabilityRepository.deleteByAssetId(asset.id!!)
            assetRepository.delete(asset)
        }
    }

    private fun alert(ghsaId: String, cveId: String?, pkg: String) = DependabotAlertDto(
        ghsaId = ghsaId,
        cveId = cveId,
        severity = "HIGH",
        ecosystem = "npm",
        packageName = pkg,
        vulnerableVersionRange = "< 1.0.0",
        createdAt = "2024-01-01T00:00:00Z"  // well past any reasonable threshold
    )

    @Test
    fun `imports alerts as repository asset and vulnerabilities`() {
        val result = importService.importDependabotAlerts(
            listOf(
                GitHubDependabotBatchDto(
                    "octo/sample", "https://github.com/octo/sample",
                    listOf(alert("GHSA-1", "CVE-2024-1", "left-pad"), alert("GHSA-2", "CVE-2024-2", "lodash"))
                )
            ),
            triggeredBy = "test"
        )

        assertThat(result.reposCreated).isEqualTo(1)
        assertThat(result.vulnerabilitiesImported).isEqualTo(2)

        val asset = assetRepository.findByNameIgnoreCase("octo/sample")!!
        assertThat(asset.type).isEqualTo(AssetTypes.REPOSITORY)
        assertThat(vulnerabilityRepository.countByAssetId(asset.id!!)).isEqualTo(2)
    }

    @Test
    fun `re-import drops remediated alerts`() {
        importService.importDependabotAlerts(
            listOf(GitHubDependabotBatchDto("octo/sample", null,
                listOf(alert("GHSA-1", "CVE-2024-1", "left-pad"), alert("GHSA-2", "CVE-2024-2", "lodash")))),
            "test"
        )
        // Second import has only one of the two alerts → the other is remediated.
        importService.importDependabotAlerts(
            listOf(GitHubDependabotBatchDto("octo/sample", null,
                listOf(alert("GHSA-1", "CVE-2024-1", "left-pad")))),
            "test"
        )

        val asset = assetRepository.findByNameIgnoreCase("octo/sample")!!
        assertThat(vulnerabilityRepository.countByAssetId(asset.id!!)).isEqualTo(1)
    }

    @Test
    fun `notification dry-run surfaces overdue repository with no recipients as unmapped`() {
        importService.importDependabotAlerts(
            listOf(GitHubDependabotBatchDto("octo/sample", "https://github.com/octo/sample",
                listOf(alert("GHSA-1", "CVE-2024-1", "left-pad")))),
            "test"
        )

        val result = notificationService.sendRepositoryVulnerabilityNotifications(
            thresholdDays = 30, dryRun = true, verbose = true
        )

        assertThat(result.status).isEqualTo(ExecutionStatus.DRY_RUN)
        assertThat(result.repositoriesAffected).isGreaterThanOrEqualTo(1)
        assertThat(result.unmappedRepositories).contains("octo/sample")
    }
}
