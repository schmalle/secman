package com.secman.integration

import com.secman.domain.Asset
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.AssetRepository
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CrowdStrikeIpImportIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var importService: CrowdStrikeVulnerabilityImportService
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
    fun `authoritative import cannot reassign a hostname to another cloud instance`() {
        val asset = existingAsset()
        asset.cloudInstanceId = "i-00000000000000001"
        assetRepository.update(asset)
        entityManager.flush()
        entityManager.clear()
        val batch = CrowdStrikeVulnerabilityBatchDto(asset.name, null, null,
            "i-00000000000000002", null, null, "10.0.0.99", vulnerabilities = emptyList())
        importService.importVulnerabilitiesForServer(batch)
        assertThat(assetRepository.findByCloudInstanceIdIgnoreCase("i-00000000000000002")!!.id).isNotEqualTo(asset.id)
        assertPersistedAddresses(asset.id!!, "10.0.0.1", setOf("10.0.0.1", "203.0.113.1"))
        assertThat(assetRepository.findById(asset.id!!).orElseThrow().cloudInstanceId).isEqualTo("i-00000000000000001")
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
