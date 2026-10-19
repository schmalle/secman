package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.client.QueriedHost
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.dto.CrowdStrikeSyncRequest
import com.secman.dto.ImportStatisticsDto
import com.secman.repository.AssetRepository
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class CrowdStrikeSyncServiceTest {
    private val client = mockk<CrowdStrikeApiClient>()
    private val queries = mockk<CrowdStrikeQueryService>()
    private val importer = mockk<CrowdStrikeVulnerabilityImportService>(relaxed = true)
    private val assets = mockk<AssetRepository>()
    private val access = mockk<AssetFilterService>()
    private val identities = mockk<com.secman.repository.CrowdStrikeAssetIdentityRepository>()
    private val service = CrowdStrikeSyncService(client, queries, importer, assets, access, identities)
    private val admin = Authentication.build("test-admin", listOf("ADMIN"))

    @Test
    fun `non-admin sync cannot discover or replace any device`() {
        assertThatThrownBy { service.sync(CrowdStrikeSyncRequest("server.example.test"), Authentication.build("viewer", listOf("VULN"))) }
            .isInstanceOf(io.micronaut.http.exceptions.HttpStatusException::class.java)
        verify(exactly = 0) { client.queryAllVulnerabilities(any(), any()) }
        verify(exactly = 0) { importer.importServerVulnerabilities(any(), any(), any(), any()) }
    }

    @Test
    fun `failed provider query is never forwarded as an empty replacement`() {
        every { queries.getConfiguration() } returns mockk()
        every { client.queryAllVulnerabilities(any(), any()) } returns CrowdStrikeQueryResponse("server.example.test",
            vulnerabilities = emptyList(), totalCount = 0, queriedAt = LocalDateTime.now(), failedAids = setOf("failed-aid"))
        assertThatThrownBy { service.sync(CrowdStrikeSyncRequest("server.example.test"), admin) }.hasMessageContaining("Incomplete")
        verify(exactly = 0) { importer.importServerVulnerabilities(any(), any(), any(), any()) }
    }

    @Test
    fun `verified zero is refetched and forwarded with selection evidence`() {
        val selection = CrowdStrikeDeviceSelection(CrowdStrikeDeviceRecord("aid-current", "server.example.test", firstSeen = Instant.now()))
        every { queries.getConfiguration() } returns mockk()
        every { client.queryAllVulnerabilities(any(), any()) } returns CrowdStrikeQueryResponse("server.example.test",
            vulnerabilities = emptyList(), totalCount = 0, queriedAt = LocalDateTime.now(),
            devices = setOf(QueriedHost("server.example.test", null, "aid-current", deviceSelection = selection, ipAddresses = setOf("10.0.0.2", "203.0.113.2"))))
        every { importer.importServerVulnerabilities(any(), any(), any(), any()) } returns ImportStatisticsDto(
            serversProcessed = 1, serversCreated = 0, serversUpdated = 1, vulnerabilitiesImported = 0,
            vulnerabilitiesSkipped = 0, vulnerabilitiesWithPatchDate = 0, errors = emptyList())
        val result = service.sync(CrowdStrikeSyncRequest("server.example.test"), admin)
        assertThat(result.vulnerabilitiesSaved).isZero()
        verify { importer.importServerVulnerabilities(match { it.single().deviceSelection == selection && it.single().vulnerabilities.isEmpty() &&
            it.single().ipAddresses == setOf("10.0.0.2", "203.0.113.2") },
            "test-admin", true, true) }
    }
    @Test
    fun `selected asset sync discovers current hostname instead of stale stored instance`() {
        val asset = com.secman.domain.Asset(id = 1L, name = "server.example.test", type = "SERVER", owner = "Human owner",
            cloudInstanceId = "i-old", cloudAccountId = "123456789012")
        val device = CrowdStrikeDeviceRecord("aid-current", asset.name, instanceId = "i-new", cloudAccountId = asset.cloudAccountId,
            firstSeen = Instant.now())
        every { access.canAccessAsset(1L, admin) } returns true
        every { assets.findById(1L) } returns java.util.Optional.of(asset)
        every { assets.findByCloudInstanceIdIgnoreCase("i-old") } returns asset
        every { identities.findByAssetIdIn(listOf(1L)) } returns listOf(com.secman.domain.CrowdStrikeAssetIdentity(
            asset = asset, crowdStrikeAid = device.aid, sourceHostname = asset.name))
        every { queries.getConfiguration() } returns mockk()
        every { client.queryAllVulnerabilities(asset.name, any()) } returns CrowdStrikeQueryResponse(asset.name,
            vulnerabilities = emptyList(), totalCount = 0, queriedAt = LocalDateTime.now(),
            devices = setOf(QueriedHost(asset.name, device.instanceId, device.aid, cloudAccountId = device.cloudAccountId,
                deviceSelection = CrowdStrikeDeviceSelection(device))))
        every { importer.importServerVulnerabilities(any(), any(), any(), any()) } returns ImportStatisticsDto(
            1, 0, 1, 0, 0, 0, errors = emptyList())
        service.sync(CrowdStrikeSyncRequest(asset.name, assetId = 1L), admin)
        service.sync(CrowdStrikeSyncRequest(asset.name, instanceId = "i-old"), admin)
        verify(exactly = 0) { client.queryVulnerabilitiesByInstanceId(any(), any()) }
        verify { importer.importServerVulnerabilities(match { it.single().cloudInstanceId == "i-new" }, "test-admin", true, true) }
    }

}
