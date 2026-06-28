package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.constants.AssetTypes
import com.secman.constants.VulnerabilitySources
import com.secman.domain.Asset
import com.secman.domain.Vulnerability
import com.secman.dto.DependabotAlertDto
import com.secman.dto.GitHubDependabotBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class GitHubDependabotImportServiceTest {

    private lateinit var assetRepository: AssetRepository
    private lateinit var vulnerabilityRepository: VulnerabilityRepository
    private lateinit var materializedViewRefreshService: MaterializedViewRefreshService
    private lateinit var entityManager: jakarta.persistence.EntityManager
    private lateinit var service: GitHubDependabotImportService

    @BeforeEach
    fun setUp() {
        assetRepository = mockk()
        vulnerabilityRepository = mockk(relaxed = true)
        materializedViewRefreshService = mockk(relaxed = true)
        entityManager = mockk(relaxed = true)

        service = GitHubDependabotImportService(
            assetRepository = assetRepository,
            vulnerabilityRepository = vulnerabilityRepository,
            materializedViewRefreshService = materializedViewRefreshService,
            entityManager = entityManager
        )

        every { vulnerabilityRepository.findByAssetId(any(), any()) } returns Page.empty()
        every { vulnerabilityRepository.deleteByAssetId(any()) } returns 0
    }

    /** Stub a not-found lookup so the service takes the create path. */
    private fun stubNewAsset(name: String) {
        every { assetRepository.findByNameIgnoreCase(name) } returns null
    }

    private fun alert(
        ghsaId: String = "GHSA-xxxx",
        cveId: String? = "CVE-2024-1",
        severity: String = "HIGH",
        ecosystem: String? = "npm",
        packageName: String? = "left-pad",
        range: String? = "< 1.3.0",
        createdAt: String? = "2024-01-01T00:00:00Z"
    ) = DependabotAlertDto(ghsaId, cveId, severity, ecosystem, packageName, range, createdAt)

    @Test
    fun `creates repository asset with REPOSITORY type, GitHub owner and uri`() {
        val assetSlot = slot<Asset>()
        val savedVulns = slot<List<Vulnerability>>()
        every { assetRepository.findByNameIgnoreCase("octo/api") } returns null
        every { assetRepository.save(capture(assetSlot)) } answers { assetSlot.captured.apply { id = 5L } }
        every { vulnerabilityRepository.saveAll(capture(savedVulns)) } answers { savedVulns.captured }

        val result = service.importAlertsForRepository(
            GitHubDependabotBatchDto("octo/api", "https://github.com/octo/api", listOf(alert(severity = "CRITICAL")))
        )

        assertThat(result.assetCreated).isTrue()
        assertThat(assetSlot.captured.type).isEqualTo(AssetTypes.REPOSITORY)
        assertThat(assetSlot.captured.owner).isEqualTo(AssetOwners.GITHUB_IMPORT)
        assertThat(assetSlot.captured.uri).isEqualTo("https://github.com/octo/api")

        val v = savedVulns.captured.single()
        assertThat(v.source).isEqualTo(VulnerabilitySources.GITHUB_DEPENDABOT)
        assertThat(v.cvssSeverity).isEqualTo("Critical")      // title-cased
        assertThat(v.vulnerabilityId).isEqualTo("CVE-2024-1") // CVE preferred over GHSA
        assertThat(v.vulnerableProductVersions).isEqualTo("npm:left-pad < 1.3.0")
    }

    @Test
    fun `uses GHSA id when CVE is absent`() {
        stubNewAsset("octo/api")
        val saved = slot<List<Vulnerability>>()
        val assetSlot = slot<Asset>()
        every { assetRepository.save(capture(assetSlot)) } answers { assetSlot.captured.apply { id = 6L } }
        every { vulnerabilityRepository.saveAll(capture(saved)) } answers { saved.captured }

        service.importAlertsForRepository(
            GitHubDependabotBatchDto("octo/api", null, listOf(alert(ghsaId = "GHSA-abcd", cveId = null)))
        )

        assertThat(saved.captured.single().vulnerabilityId).isEqualTo("GHSA-abcd")
    }

    @Test
    fun `deduplicates alerts by (vulnerabilityId, product)`() {
        stubNewAsset("octo/api")
        val saved = slot<List<Vulnerability>>()
        val assetSlot = slot<Asset>()
        every { assetRepository.save(capture(assetSlot)) } answers { assetSlot.captured.apply { id = 7L } }
        every { vulnerabilityRepository.saveAll(capture(saved)) } answers { saved.captured }

        val dup = alert(cveId = "CVE-2024-9", packageName = "lodash", range = "< 4.0.0")
        service.importAlertsForRepository(
            GitHubDependabotBatchDto("octo/api", null, listOf(dup, dup.copy()))
        )

        assertThat(saved.captured).hasSize(1)
    }

    @Test
    fun `preserves firstSeenAt across re-import`() {
        // Earlier than the re-imported alert's createdAt (2024-06-01) so the rule
        // "keep the earliest firstSeenAt" preserves this value.
        val originalFirstSeen = LocalDateTime.of(2023, 1, 1, 0, 0)
        val existing = Asset(id = 8L, name = "octo/api", type = AssetTypes.REPOSITORY, owner = AssetOwners.GITHUB_IMPORT)
        val priorVuln = Vulnerability(
            asset = existing,
            vulnerabilityId = "CVE-2024-1",
            cvssSeverity = "High",
            vulnerableProductVersions = "npm:left-pad < 1.3.0",
            scanTimestamp = originalFirstSeen,
            firstSeenAt = originalFirstSeen,
            source = VulnerabilitySources.GITHUB_DEPENDABOT
        )
        every { assetRepository.findByNameIgnoreCase("octo/api") } returns existing
        every { assetRepository.update(existing) } returns existing
        every { vulnerabilityRepository.findByAssetId(8L, any()) } returns
            Page.of(listOf(priorVuln), Pageable.UNPAGED, 1)
        val saved = slot<List<Vulnerability>>()
        every { vulnerabilityRepository.saveAll(capture(saved)) } answers { saved.captured }

        service.importAlertsForRepository(
            GitHubDependabotBatchDto("octo/api", null, listOf(alert(createdAt = "2024-06-01T00:00:00Z")))
        )

        assertThat(saved.captured.single().firstSeenAt).isEqualTo(originalFirstSeen)
    }
}
