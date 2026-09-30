package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import com.secman.domain.FalconConfig
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.ImportStatisticsDto
import com.secman.repository.FalconConfigRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class DomainVulnsServiceTest {
    @Test
    fun `domain sync passes all host addresses and the first reported primary to the importer`() {
        val api = mockk<CrowdStrikeApiClient>()
        val repository = mockk<FalconConfigRepository>()
        val importer = mockk<CrowdStrikeVulnerabilityImportService>()
        val config = FalconConfig(clientId = UUID.randomUUID().toString(), clientSecret = UUID.randomUUID().toString(), cloudRegion = "eu-1")
        every { repository.findActiveConfig() } returns Optional.of(config)
        val first = CrowdStrikeVulnerabilityDto(
            id = "finding-1", hostname = "multi-ip", ip = null, ipAddresses = setOf("10.0.0.1", "2001:db8::1"),
            cveId = "CVE-2026-1234", severity = "HIGH", cvssScore = 8.1, affectedProduct = "Example",
            daysOpen = "10 days", detectedAt = null, status = "open", hasException = false
        )
        every { api.queryVulnerabilitiesByDomains(listOf("example.test"), "", 0, any(), 10000) } returns
            CrowdStrikeQueryResponse(hostname = "multi-ip", vulnerabilities = listOf(first, first.copy(ip = "10.0.0.2")), totalCount = 2, queriedAt = LocalDateTime.now())
        val batches = slot<List<CrowdStrikeVulnerabilityBatchDto>>()
        every { importer.importServerVulnerabilities(capture(batches), "operator", triggerRefresh = true) } returns
            ImportStatisticsDto(1, 0, 1, 2, 0, 0, errors = emptyList())
        val service = DomainVulnsService(mockk(), mockk(), mockk(), api, repository, importer)

        service.syncDomainFromCrowdStrike("example.test", "operator")

        assertThat(batches.captured.single().ip).isEqualTo("10.0.0.2")
        assertThat(batches.captured.single().ipAddresses).containsExactly("10.0.0.1", "10.0.0.2", "2001:db8::1")
    }
}
