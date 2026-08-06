package com.secman.controller

import com.secman.dto.CrowdStrikeQueryResponse
import com.secman.dto.CrowdStrikeSaveRequest
import com.secman.dto.CrowdStrikeSaveResponse
import com.secman.dto.CrowdStrikeVulnerabilityDto
import com.secman.service.CrowdStrikeError
import com.secman.service.CrowdStrikeQueryService
import com.secman.service.CrowdStrikeReconcileJobService
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.service.CrowdStrikeVulnerabilityService
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Covers the force-refresh routing on `GET /api/vulnerabilities`.
 *
 * The reported bug: the "Refresh" button on the System Vulnerabilities lookup sends `force=true`,
 * but the controller only used it to call two cache-eviction stubs and never passed it to the
 * service — whose query methods are DB-first, so for any imported host the live CrowdStrike call
 * was unreachable. The first two tests here are the ones that would have caught it.
 */
class CrowdStrikeControllerTest {

    private lateinit var crowdStrikeService: CrowdStrikeVulnerabilityService
    private lateinit var queryService: CrowdStrikeQueryService
    private lateinit var importService: CrowdStrikeVulnerabilityImportService
    private lateinit var reconcileJobService: CrowdStrikeReconcileJobService
    private lateinit var controller: CrowdStrikeController

    @BeforeEach
    fun setUp() {
        crowdStrikeService = mockk()
        queryService = mockk()
        importService = mockk()
        reconcileJobService = mockk()
        controller = CrowdStrikeController(
            crowdStrikeService,
            queryService,
            importService,
            reconcileJobService
        )
    }

    // --- force routing ---

    @Test
    fun `force=true routes a hostname to the live query and never to the cached one`() {
        every { queryService.queryVulnerabilitiesLive("web-server-01", null, null) } returns
            response(dataSource = "LIVE_API")

        val result = controller.queryVulnerabilities(
            hostname = "web-server-01", severity = null, product = null,
            limit = null, page = null, force = true
        )

        assertThat(result.status.code).isEqualTo(200)
        assertThat((result.body() as CrowdStrikeQueryResponse).dataSource).isEqualTo("LIVE_API")
        verify(exactly = 1) { queryService.queryVulnerabilitiesLive("web-server-01", null, null) }
        verify(exactly = 0) { queryService.queryVulnerabilities(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `force=false routes a hostname to the cached DB-first query`() {
        every { queryService.queryVulnerabilities("web-server-01", null, null, 20000, 0) } returns response()

        val result = controller.queryVulnerabilities(
            hostname = "web-server-01", severity = null, product = null,
            limit = null, page = null, force = false
        )

        assertThat(result.status.code).isEqualTo(200)
        verify(exactly = 1) { queryService.queryVulnerabilities("web-server-01", null, null, 20000, 0) }
        verify(exactly = 0) { queryService.queryVulnerabilitiesLive(any(), any(), any()) }
    }

    @Test
    fun `force=true routes an AWS instance ID to the live instance query`() {
        every { queryService.queryByInstanceIdLive("i-0068f94221fe120df", null, null) } returns
            response(dataSource = "LIVE_API")

        val result = controller.queryVulnerabilities(
            hostname = "i-0068f94221fe120df", severity = null, product = null,
            limit = null, page = null, force = true
        )

        assertThat(result.status.code).isEqualTo(200)
        verify(exactly = 1) { queryService.queryByInstanceIdLive("i-0068f94221fe120df", null, null) }
        verify(exactly = 0) { queryService.queryByInstanceId(any(), any(), any(), any(), any()) }
    }

    // --- host unknown to Falcon: fall back to persisted rows rather than wiping the screen ---

    @Test
    fun `force=true falls back to persisted rows with a notice when Falcon has no device`() {
        every { queryService.queryVulnerabilitiesLive("decommissioned-01", null, null) } throws
            CrowdStrikeError.NotFoundError(hostname = "decommissioned-01")
        every { queryService.queryFromDatabase("decommissioned-01", null, null, 20000, 0) } returns response()

        val result = controller.queryVulnerabilities(
            hostname = "decommissioned-01", severity = null, product = null,
            limit = null, page = null, force = true
        )

        assertThat(result.status.code).isEqualTo(200)
        val body = result.body() as CrowdStrikeQueryResponse
        assertThat(body.dataSource).isEqualTo("DATABASE")
        assertThat(body.vulnerabilities).hasSize(1)
        assertThat(body.notice).contains("Not found in CrowdStrike Falcon")
    }

    @Test
    fun `force=true still returns 404 when the host is unknown to both Falcon and the database`() {
        every { queryService.queryVulnerabilitiesLive("never-existed", null, null) } throws
            CrowdStrikeError.NotFoundError(hostname = "never-existed")
        every { queryService.queryFromDatabase("never-existed", null, null, 20000, 0) } returns null

        val result = controller.queryVulnerabilities(
            hostname = "never-existed", severity = null, product = null,
            limit = null, page = null, force = true
        )

        assertThat(result.status.code).isEqualTo(404)
    }

    @Test
    fun `a rate limit on the force path surfaces as 429 and is not masked by the DB fallback`() {
        every { queryService.queryVulnerabilitiesLive("busy-host", null, null) } throws
            CrowdStrikeError.RateLimitError(retryAfterSeconds = 30)

        val result = controller.queryVulnerabilities(
            hostname = "busy-host", severity = null, product = null,
            limit = null, page = null, force = true
        )

        assertThat(result.status.code).isEqualTo(429)
        verify(exactly = 0) { queryService.queryFromDatabase(any(), any(), any(), any(), any()) }
    }

    // --- save must drop the query cache, otherwise a follow-up search shows pre-save data ---

    @Test
    fun `a successful save invalidates the vulnerability query cache`() {
        every { crowdStrikeService.saveToDatabase(any(), any()) } returns saveResponse(saved = 3)
        every { queryService.invalidateAllCachedQueries() } returns Unit

        val result = controller.saveVulnerabilities(saveRequest(), authentication())

        assertThat(result.status.code).isEqualTo(200)
        verify(exactly = 1) { queryService.invalidateAllCachedQueries() }
    }

    @Test
    fun `a save that persisted nothing leaves the cache alone`() {
        every { crowdStrikeService.saveToDatabase(any(), any()) } returns saveResponse(saved = 0)

        val result = controller.saveVulnerabilities(saveRequest(), authentication())

        assertThat(result.status.code).isEqualTo(200)
        verify(exactly = 0) { queryService.invalidateAllCachedQueries() }
    }

    @Test
    fun `a cache invalidation failure does not fail an otherwise successful save`() {
        every { crowdStrikeService.saveToDatabase(any(), any()) } returns saveResponse(saved = 3)
        every { queryService.invalidateAllCachedQueries() } throws IllegalStateException("cache down")

        val result = controller.saveVulnerabilities(saveRequest(), authentication())

        assertThat(result.status.code).isEqualTo(200)
    }

    // --- builders ---

    private fun response(dataSource: String = "DATABASE") = CrowdStrikeQueryResponse(
        hostname = "web-server-01",
        vulnerabilities = listOf(
            CrowdStrikeVulnerabilityDto(
                id = "db-1",
                hostname = "web-server-01",
                ip = "10.0.0.1",
                cveId = "CVE-2026-00001",
                severity = "HIGH",
                cvssScore = null,
                affectedProduct = "Chrome Enterprise",
                daysOpen = "10 days",
                detectedAt = LocalDateTime.now().minusDays(10),
                status = "open",
                hasException = false,
                exceptionReason = null
            )
        ),
        totalCount = 1,
        queriedAt = "2026-07-27T10:00:00Z",
        dataSource = dataSource
    )

    private fun saveRequest() = CrowdStrikeSaveRequest(
        hostname = "web-server-01",
        vulnerabilities = response().vulnerabilities
    )

    private fun saveResponse(saved: Int) = CrowdStrikeSaveResponse(
        message = "Saved",
        vulnerabilitiesSaved = saved,
        assetsCreated = 0
    )

    private fun authentication(): Authentication = mockk {
        every { name } returns "adminuser"
    }
}
