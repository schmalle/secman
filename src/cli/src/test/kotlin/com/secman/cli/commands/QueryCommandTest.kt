package com.secman.cli.commands

import com.secman.cli.service.BatchStorageResult
import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.VulnerabilityStorageService
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.client.QueriedHost
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.CrowdStrikeVulnerabilityDto
import io.micronaut.context.ApplicationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class QueryCommandTest {
    private val api = mockk<CrowdStrikeApiClient>()
    private val storage = mockk<VulnerabilityStorageService>()
    private val auth = mockk<CliHttpClient>()
    private val context = mockk<ApplicationContext>()

    private fun command(): QueryCommand {
        every { context.getBean(CrowdStrikeApiClient::class.java) } returns api
        every { context.getBean(VulnerabilityStorageService::class.java) } returns storage
        every { context.getBean(CliHttpClient::class.java) } returns auth
        return QueryCommand(context) { name ->
            when (name) {
                "SECMAN_BACKEND_URL" -> "https://fixture.test"
                "SECMAN_ADMIN_NAME" -> "fixture-user"
                "SECMAN_ADMIN_PASS" -> UUID.randomUUID().toString()
                else -> null
            }
        }.apply {
            hostname = "server-1"
            clientId = "fixture-client"
            clientSecret = UUID.randomUUID().toString()
            save = true
        }
    }

    private fun response(failed: Set<String> = emptySet()) = CrowdStrikeQueryResponse(
        hostname = "server-1", failedAids = failed,
        devices = setOf(QueriedHost("server-1", null, "aid-one")),
        vulnerabilities = listOf(CrowdStrikeVulnerabilityDto(
            id = "one", hostname = "server-1", ip = null, cveId = "CVE-2026-1234", severity = "High",
            cvssScore = null, affectedProduct = null, detectedAt = null,
            status = "open", hasException = false, daysOpen = "5 days", crowdStrikeAid = "aid-one"
        )), totalCount = 1, queriedAt = LocalDateTime.now()
    )

    @Test
    fun `partial hostname lookup refuses saving before authentication or storage`() {
        val cmd = command()
        every { api.queryAllVulnerabilities(any(), any()) } returns response(setOf("aid-failed"))
        assertThat(cmd.execute()).isEqualTo(2)
        verify(exactly = 0) { auth.authenticate(any(), any(), any()) }
        verify(exactly = 0) { storage.storeServerVulnerabilities(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `backend replacement failure exits nonzero`() {
        val cmd = command()
        every { api.queryAllVulnerabilities(any(), any()) } returns response()
        every { auth.authenticate(any(), any(), any()) } returns UUID.randomUUID().toString()
        every { storage.storeServerVulnerabilities(any(), any(), any(), any(), any()) } returns
            BatchStorageResult(0, 0, 0, 0, 0, 0, listOf("identity conflict"), listOf("server-1"))
        assertThat(cmd.execute()).isEqualTo(1)
    }

    @Test
    fun `product filter cannot save a partial host snapshot`() {
        val cmd = command().apply { product = "openssl" }
        assertThat(cmd.execute()).isEqualTo(2)
        verify(exactly = 0) { api.queryAllVulnerabilities(any(), any()) }
        verify(exactly = 0) { storage.storeServerVulnerabilities(any(), any(), any(), any(), any()) }
    }
}
