package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.ComplianceStatus
import com.secman.domain.VulnerabilityException
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.VulnerabilityDto
import com.secman.repository.AssetComplianceHistoryRepository
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityExceptionRepository
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CrowdStrikeImportComplianceIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var importService: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var assetRepository: AssetRepository
    @Inject lateinit var exceptionRepository: VulnerabilityExceptionRepository
    @Inject lateinit var historyRepository: AssetComplianceHistoryRepository
    @Inject lateinit var entityManager: jakarta.persistence.EntityManager

    @Test
    fun `reimported excepted findings do not record false non compliant transitions`() {
        val name = "import-compliance-${System.nanoTime()}"
        val asset = assetRepository.save(Asset(
            name = name, type = "SERVER", owner = "test", cloudInstanceId = "i-$name"
        ))
        val exception = exceptionRepository.save(VulnerabilityException(
            subject = VulnerabilityException.Subject.ALL_VULNS,
            scope = VulnerabilityException.Scope.ASSET,
            assetId = asset.id, reason = "import compliance regression", createdBy = "test"
        ))
        val batch = CrowdStrikeVulnerabilityBatchDto(
            hostname = name, groups = null, cloudAccountId = null,
            cloudInstanceId = asset.cloudInstanceId, adDomain = null, osVersion = null, ip = null,
            runSeverities = listOf("HIGH"),
            vulnerabilities = listOf(VulnerabilityDto("CVE-2026-1234", "HIGH", "test-product", 90,
                detectedAt = LocalDateTime.now().minusDays(90)))
        )
        entityManager.flush()

        importService.importVulnerabilitiesForServer(batch)
        importService.importVulnerabilitiesForServer(batch)
        entityManager.flush()
        assertThat(historyRepository.findByAssetIdOrderByChangedAtDesc(asset.id!!).map { it.status })
            .containsExactly(ComplianceStatus.COMPLIANT)

        exception.expirationDate = LocalDateTime.now().minusDays(1)
        exceptionRepository.update(exception)
        entityManager.flush()
        importService.importVulnerabilitiesForServer(batch)
        entityManager.flush()
        assertThat(historyRepository.findByAssetIdOrderByChangedAtDesc(asset.id!!).map { it.status })
            .containsExactly(ComplianceStatus.NON_COMPLIANT, ComplianceStatus.COMPLIANT)
    }
}
