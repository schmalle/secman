package com.secman.integration

import com.secman.controller.VulnerabilityManagementController.FilterOptionsResponse
import com.secman.domain.Asset
import com.secman.domain.ProductClass
import com.secman.domain.UserMapping
import com.secman.domain.Vulnerability
import com.secman.dto.PaginatedVulnerabilitiesResponse
import com.secman.dto.VulnerabilityCountResponse
import com.secman.dto.VulnerabilityExportFilters
import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.FalconSeenTodayWindow
import com.secman.service.VulnerabilityService
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

@MicronautTest(environments = ["test"], transactional = false)
class AnalyticsOverviewIntegrationTest : BaseIntegrationTest() {
    @Inject @field:Client("/") lateinit var client: HttpClient
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var mappings: UserMappingRepository
    @Inject lateinit var findings: VulnerabilityRepository
    @Inject lateinit var vulnerabilityService: VulnerabilityService

    @Test
    fun `scoped overview counts and exports current findings across observation sources`() {
        val prefix = "analytics-${System.nanoTime()}"
        val user = users.save(TestDataFactory.createVulnUser(prefix, "$prefix@example.test"))
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val yesterdayContact = maxOf(now.minusHours(23), FalconSeenTodayWindow.today().startUtc.minusSeconds(1))
        val falcon = saveAsset("$prefix-falcon", yesterdayContact, true, "123456789012", "falcon.example.test")
        val scan = saveAsset("$prefix-scan", now.minusHours(1), false, "123456789012", "scan.example.test")
        val stale = saveAsset("$prefix-stale", now.minusHours(25), true, "123456789012", "expired.example.test")
        val hidden = saveAsset("$prefix-hidden", now.minusHours(1), false, "987654321098", "hidden.example.test")
        mappings.save(UserMapping(email = user.email, awsAccountId = falcon.cloudAccountId, domain = null))
        saveFinding(falcon, "CVE-2099-1001", "Overview Falcon")
        saveFinding(scan, "CVE-2099-1002", "Overview Scanner")
        saveFinding(scan, "CVE-2099-1003", "Overview Excepted", excepted = true)
        saveFinding(scan, "CVE-2099-1004", "Overview Installer", installer = true)
        saveFinding(stale, "CVE-2099-1005", "Overview Expired")
        saveFinding(hidden, "CVE-2099-1006", "Overview Hidden")

        val token = TestAuthHelper.getAuthToken(client, user.username)
        for ((status, includeInstaller, expected) in listOf(
            Triple("not_excepted", false, listOf("CVE-2099-1001", "CVE-2099-1002")),
            Triple("all", false, listOf("CVE-2099-1001", "CVE-2099-1002", "CVE-2099-1003")),
            Triple("all", true, listOf("CVE-2099-1001", "CVE-2099-1002", "CVE-2099-1003", "CVE-2099-1004"))
        )) {
            assertOverview(token, prefix, status, includeInstaller, expected, setOf(falcon.id!!, scan.id!!))
        }

        val options = get("/api/vulnerabilities/filter-options?productSearch=Overview", token, FilterOptionsResponse::class.java)
        assertThat(options.products).containsExactly("Overview Excepted", "Overview Falcon", "Overview Installer", "Overview Scanner")
        assertThat(options.adDomains).containsExactly("falcon.example.test", "scan.example.test")
        assertThat(options.cloudAccountIds).containsExactly("123456789012")
    }

    private fun assertOverview(
        token: String, system: String, status: String, includeInstaller: Boolean, expected: List<String>, assetIds: Set<Long>
    ) {
        val query = "system=$system&exceptionStatus=$status&includeInstallerFindings=$includeInstaller&sort=vulnerabilityId&sortDir=asc"
        val page = get("/api/vulnerabilities/current?$query&size=1&countMode=deferred", token, PaginatedVulnerabilitiesResponse::class.java)
        assertThat(page.content.map { it.vulnerabilityId }).containsExactly(expected.first())
        assertThat(page.hasNext).isTrue()
        val last = get("/api/vulnerabilities/current?$query&size=1&page=${expected.lastIndex}", token, PaginatedVulnerabilitiesResponse::class.java)
        assertThat(last.content.map { it.vulnerabilityId }).containsExactly(expected.last())
        assertThat(last.hasNext).isFalse()
        val count = get("/api/vulnerabilities/current/count?$query&size=1", token, VulnerabilityCountResponse::class.java)
        assertThat(count.totalElements).isEqualTo(expected.size.toLong())

        val filters = VulnerabilityExportFilters(system = system, exceptionStatus = status, includeInstallerFindings = includeInstaller)
        var exported = vulnerabilityService.getCurrentVulnerabilitiesExportPage(assetIds, false, filters, 0, null, 1)
        val cves = exported.content.map { it.vulnerabilityId }.toMutableList()
        for (index in 1..expected.lastIndex) {
            assertThat(exported.hasNext).isTrue()
            exported = vulnerabilityService.getCurrentVulnerabilitiesExportPage(assetIds, false, filters, index, exported.next, 1)
            cves += exported.content.map { it.vulnerabilityId }
        }
        assertThat(exported.hasNext).isFalse()
        assertThat(cves).containsExactlyInAnyOrderElementsOf(expected)
    }

    private fun saveAsset(name: String, observed: LocalDateTime, falcon: Boolean, account: String, domain: String): Asset =
        assets.save(Asset(name = name, type = "SERVER", owner = "test", cloudAccountId = account, adDomain = domain,
            crowdStrikeManaged = falcon, crowdStrikeAgentSeenAt = observed.takeIf { falcon }, verifiedObservedAt = observed))

    private fun saveFinding(asset: Asset, cve: String, product: String, excepted: Boolean = false, installer: Boolean = false) {
        findings.save(Vulnerability(asset = asset, vulnerabilityId = cve, vulnerableProductVersions = product,
            cvssSeverity = "High", scanTimestamp = LocalDateTime.now(ZoneOffset.UTC), excepted = excepted,
            productClass = if (installer) ProductClass.INSTALLER_ARTIFACT else ProductClass.INSTALLED))
    }

    private fun <T : Any> get(path: String, token: String, type: Class<T>): T =
        client.toBlocking().retrieve(HttpRequest.GET<Any>(path).bearerAuth(token), type)
}
