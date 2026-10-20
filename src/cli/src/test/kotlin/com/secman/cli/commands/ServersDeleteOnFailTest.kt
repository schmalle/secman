package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.VulnerabilityStorageService
import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import io.micronaut.context.ApplicationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class ServersDeleteOnFailTest {
    @Test
    fun `deleteonfail requires save before opening application context`() {
        assertThat(ServersCommand().apply { deleteOnFail = true }.execute()).isNotZero()
    }

    @Test
    fun `save dry run with deleteonfail never authenticates saves or deletes`() {
        val context = mockk<ApplicationContext>()
        val api = mockk<CrowdStrikeApiClient>()
        val auth = mockk<CliHttpClient>()
        val storage = mockk<VulnerabilityStorageService>()
        every { context.getBean(CrowdStrikeApiClient::class.java) } returns api
        every { context.getBean(CliHttpClient::class.java) } returns auth
        every { context.getBean(VulnerabilityStorageService::class.java) } returns storage
        every { api.queryServersWithFiltersSummary(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            com.secman.crowdstrike.client.StreamingSummary(1, mapOf("ARCGIS" to 1))
        every { api.queryServersWithFilters(any(), any(), any(), any(), any(), any(), any()) } returns
            CrowdStrikeQueryResponse(hostname = "ARCGIS", vulnerabilities = emptyList(), totalCount = 0, queriedAt = LocalDateTime.now())
        val cmd = ServersCommand().apply {
            deleteOnFail = true; save = true; dryRun = true
            hostnames = listOf("ARCGIS")
            clientId = "fixture-client"; clientSecret = UUID.randomUUID().toString()
        }
        ServersCommand::class.java.getDeclaredField("appContext\$delegate").apply {
            isAccessible = true
            set(cmd, lazyOf(context))
        }
        assertThat(cmd.execute()).isZero()
        cmd.hostnames = null
        assertThat(cmd.execute()).isNotZero()
        verify(exactly = 0) { api.queryServersWithFiltersSummary(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { api.queryServersWithFilters(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { auth.authenticate(any(), any(), any()) }
        verify(exactly = 0) { storage.storeServerVulnerabilities(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { auth.deleteWithStatus(any(), any()) }
    }
}
