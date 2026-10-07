package com.secman.integration

import com.secman.domain.Asset
import com.secman.dto.CrowdStrikeSaveRequest
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.CrowdStrikeVulnerabilityDto
import com.secman.repository.AssetRepository
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.service.CrowdStrikeVulnerabilityService
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CrowdStrikeIpImportIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var importService: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var saveService: CrowdStrikeVulnerabilityService
    @Inject lateinit var assetRepository: AssetRepository
    @Inject lateinit var entityManager: jakarta.persistence.EntityManager

    @Test
    fun `batch import removes persisted historical addresses and keeps current addresses`() {
        val asset = existingAsset()
        val batch = CrowdStrikeVulnerabilityBatchDto(
            hostname = asset.name, groups = null, cloudAccountId = null,
            cloudInstanceId = null, adDomain = null, osVersion = null,
            ip = "10.0.0.2", ipAddresses = setOf("10.0.0.2", "203.0.113.2"),
            vulnerabilities = emptyList()
        )
        repeat(2) {
            importService.importVulnerabilitiesForServer(batch)
            assertPersistedAddresses(asset.id!!, "10.0.0.2", setOf("10.0.0.2", "203.0.113.2"))
        }
        importService.importVulnerabilitiesForServer(batch.copy(ip = null, ipAddresses = emptySet()))
        assertPersistedAddresses(asset.id!!, "10.0.0.2", setOf("10.0.0.2", "203.0.113.2"))
    }

    @Test
    fun `interactive save replaces persisted addresses from all current rows`() {
        val asset = existingAsset()
        val row = CrowdStrikeVulnerabilityDto(
            id = "current-finding", hostname = asset.name, ip = "10.0.0.2",
            cveId = "CVE-2026-1234", severity = "High", cvssScore = 8.0,
            affectedProduct = "test-product", daysOpen = "5 days", detectedAt = null,
            status = "open", hasException = false
        )
        val request = CrowdStrikeSaveRequest(asset.name, listOf(row, row.copy(id = "other-finding", ip = "10.0.0.3")))
        val authentication = Authentication.build("test-admin", listOf("ADMIN"))
        val result = saveService.saveToDatabase(request, authentication)
        assertThat(result.errors).isEmpty()
        assertPersistedAddresses(asset.id!!, "10.0.0.2", setOf("10.0.0.2", "10.0.0.3"))
        val missing = saveService.saveToDatabase(request.copy(vulnerabilities = listOf(row.copy(ip = " "))), authentication)
        assertThat(missing.errors).isEmpty()
        assertPersistedAddresses(asset.id!!, "10.0.0.2", setOf("10.0.0.2", "10.0.0.3"))
    }

    private fun existingAsset(): Asset = assetRepository.save(Asset(
        name = "ip-import-${System.nanoTime()}", type = "SERVER", owner = "test",
        ip = "10.0.0.1", ipAddresses = mutableSetOf("10.0.0.1", "203.0.113.1")
    )).also { entityManager.flush() }

    private fun assertPersistedAddresses(id: Long, primary: String, addresses: Set<String>) {
        entityManager.flush()
        entityManager.clear()
        val reloaded = entityManager.find(Asset::class.java, id)
        assertThat(reloaded.ip).isEqualTo(primary)
        assertThat(reloaded.ipAddresses).containsExactlyInAnyOrderElementsOf(addresses)
    }
}
