package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.domain.Asset
import com.secman.domain.FalconConfig
import com.secman.domain.Vulnerability
import com.secman.domain.VulnerabilityException
import com.secman.repository.AssetRepository
import com.secman.repository.FalconConfigRepository
import com.secman.repository.VulnerabilityExceptionRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse as SharedResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto as SharedVuln

/**
 * Covers the exception-status fix: hasException must be computed uniformly for every response,
 * whether it is served from the local DB cache or the live-Falcon fall-through path. Before the
 * fix the live path copied the shared client's hardcoded hasException=false, so the System
 * Vulnerabilities lookup silently dropped all exception status whenever the DB lookup missed.
 */
class CrowdStrikeQueryServiceTest {

    private lateinit var apiClient: CrowdStrikeApiClient
    private lateinit var falconConfigRepository: FalconConfigRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var vulnerabilityRepository: VulnerabilityRepository
    private lateinit var vulnerabilityExceptionRepository: VulnerabilityExceptionRepository
    private lateinit var service: CrowdStrikeQueryService

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        falconConfigRepository = mockk()
        assetRepository = mockk()
        vulnerabilityRepository = mockk()
        vulnerabilityExceptionRepository = mockk()
        service = CrowdStrikeQueryService(
            apiClient,
            falconConfigRepository,
            assetRepository,
            vulnerabilityRepository,
            vulnerabilityExceptionRepository
        )
    }

    // --- DB-served path: exceptions must still be applied after centralization ---

    @Test
    fun `db path applies a global product exception via substring match`() {
        val asset = asset("EC2AMAZ-3CSRJ2O")
        val vuln = vuln(asset, cve = "CVE-2022-40303", product = "Universal Forwarder 8.1")
        every { assetRepository.findByNameIgnoreCase("EC2AMAZ-3CSRJ2O") } returns asset
        every { vulnerabilityRepository.findByAssetId(1L, any()) } returns page(vuln)
        every {
            vulnerabilityExceptionRepository.findByExpirationDateIsNullOrExpirationDateGreaterThan(any())
        } returns listOf(productException("Universal Forwarder", reason = "Legacy exception"))

        val result = service.queryVulnerabilities("EC2AMAZ-3CSRJ2O")

        assertThat(result.vulnerabilities).singleElement().satisfies({
            assertThat(it.hasException).isTrue()
            assertThat(it.exceptionReason).isEqualTo("Legacy exception")
        })
    }

    @Test
    fun `db path leaves a non-matching vulnerability unexcepted`() {
        val asset = asset("EC2AMAZ-3CSRJ2O")
        val vuln = vuln(asset, cve = "CVE-2026-11682", product = "Chrome Enterprise")
        every { assetRepository.findByNameIgnoreCase("EC2AMAZ-3CSRJ2O") } returns asset
        every { vulnerabilityRepository.findByAssetId(1L, any()) } returns page(vuln)
        every {
            vulnerabilityExceptionRepository.findByExpirationDateIsNullOrExpirationDateGreaterThan(any())
        } returns listOf(productException("Universal Forwarder", reason = "Legacy exception"))

        val result = service.queryVulnerabilities("EC2AMAZ-3CSRJ2O")

        assertThat(result.vulnerabilities).singleElement().satisfies({
            assertThat(it.hasException).isFalse()
            assertThat(it.exceptionReason).isNull()
        })
    }

    // --- Live-Falcon fall-through path: this is the reported bug ---

    @Test
    fun `live path applies global CVE and product exceptions when DB lookup misses`() {
        // DB lookup misses -> service falls through to the live Falcon API.
        every { assetRepository.findByNameIgnoreCase("EC2AMAZ-3CSRJ2O") } returns null
        every { assetRepository.findByCloudInstanceIdIgnoreCase(any()) } returns null
        every { falconConfigRepository.findActiveConfig() } returns
            Optional.of(FalconConfig(clientId = "cid", clientSecret = "secret"))
        every { apiClient.queryAllVulnerabilities("EC2AMAZ-3CSRJ2O", any()) } returns
            sharedResponse(
                sharedVuln(cve = "CVE-2022-32156", product = "Universal Forwarder"),
                sharedVuln(cve = "CVE-2022-40303", product = "Universal Forwarder 8.1")
            )
        every {
            vulnerabilityExceptionRepository.findByExpirationDateIsNullOrExpirationDateGreaterThan(any())
        } returns listOf(
            cveException("CVE-2022-32156", reason = "CVE excepted"),
            productException("Universal Forwarder", reason = "Product excepted")
        )

        val result = service.queryVulnerabilities("EC2AMAZ-3CSRJ2O")

        // Both rows now excepted: first by the CVE rule, second by the product substring rule.
        assertThat(result.vulnerabilities).allSatisfy({ assertThat(it.hasException).isTrue() })
        assertThat(result.vulnerabilities.map { it.cveId })
            .containsExactly("CVE-2022-32156", "CVE-2022-40303")
    }

    // --- builders ---

    private fun asset(name: String) = Asset(id = 1L, name = name, type = "SERVER", owner = "CrowdStrike Import")

    private fun vuln(asset: Asset, cve: String, product: String) = Vulnerability(
        id = 99L,
        asset = asset,
        vulnerabilityId = cve,
        cvssSeverity = "HIGH",
        vulnerableProductVersions = product,
        scanTimestamp = LocalDateTime.now().minusDays(500)
    )

    private fun page(vararg vulns: Vulnerability): Page<Vulnerability> =
        Page.of(vulns.toList(), Pageable.from(0, 20000), vulns.size.toLong())

    private fun productException(product: String, reason: String) = VulnerabilityException(
        subject = VulnerabilityException.Subject.PRODUCT,
        scope = VulnerabilityException.Scope.GLOBAL,
        subjectValue = product,
        reason = reason,
        createdBy = "adminuser"
    )

    private fun cveException(cve: String, reason: String) = VulnerabilityException(
        subject = VulnerabilityException.Subject.CVE,
        scope = VulnerabilityException.Scope.GLOBAL,
        subjectValue = cve,
        reason = reason,
        createdBy = "adminuser"
    )

    private fun sharedResponse(vararg vulns: SharedVuln) = SharedResponse(
        hostname = "EC2AMAZ-3CSRJ2O",
        vulnerabilities = vulns.toList(),
        totalCount = vulns.size,
        queriedAt = LocalDateTime.now()
    )

    private fun sharedVuln(cve: String, product: String) = SharedVuln(
        id = "cs-$cve",
        hostname = "EC2AMAZ-3CSRJ2O",
        ip = "10.222.148.200",
        cveId = cve,
        severity = "HIGH",
        cvssScore = null,
        affectedProduct = product,
        daysOpen = "501 days",
        detectedAt = LocalDateTime.now().minusDays(501),
        status = "open",
        hasException = false // shared client hardcodes this; applyExceptions must override it
    )
}
