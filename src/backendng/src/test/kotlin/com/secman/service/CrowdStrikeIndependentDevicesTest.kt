package com.secman.service

import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.VulnerabilityDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.CrowdStrikeSnapshotRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@MicronautTest(environments = ["test"], transactional = false)
class CrowdStrikeIndependentDevicesTest : BaseIntegrationTest() {
    @Inject lateinit var importer: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var identities: CrowdStrikeAssetIdentityRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var findings: VulnerabilityRepository
    @Inject lateinit var snapshots: CrowdStrikeSnapshotRepository
    @Inject lateinit var deletion: AssetCascadeDeleteService

    @Test
    fun `same hostname persists separate assets and refreshing or deleting one preserves the other`() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val hostname = "ARCGIS-$suffix"
        val first = CrowdStrikeDeviceRecord("first-$suffix", hostname,
            firstSeen = Instant.now().minusSeconds(86400), lastSeen = Instant.now().minusSeconds(120))
        val second = first.copy(aid = "second-$suffix", lastSeen = Instant.now().minusSeconds(60))
        fun batch(device: CrowdStrikeDeviceRecord, cve: String?) = CrowdStrikeVulnerabilityBatchDto(
            hostname, null, null, null, null, "Windows Server", if (device == first) "192.0.2.104" else "192.0.2.109",
            vulnerabilities = cve?.let { listOf(VulnerabilityDto(it, "HIGH", "test-product", 1,
                patchPublicationDate = LocalDateTime.now().minusDays(2))) }.orEmpty(),
            crowdStrikeAids = setOf(device.aid), deviceSelection = CrowdStrikeDeviceSelection(device))
        val createdIds = mutableSetOf<Long>()
        try {
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val results = executor.invokeAll(listOf(
                    java.util.concurrent.Callable { importer.importServerVulnerabilities(listOf(batch(first, "CVE-2026-10001")), "test-admin", false, true) },
                    java.util.concurrent.Callable { importer.importServerVulnerabilities(listOf(batch(second, "CVE-2026-10002")), "test-admin", false, true) }
                )).map { it.get() }
                assertThat(results.flatMap { it.errors }).isEmpty()
            } finally { executor.shutdownNow() }
            val bound = identities.findByCrowdStrikeAidIn(listOf(first.aid, second.aid))
            createdIds.addAll(bound.map { requireNotNull(it.asset.id) })
            assertThat(createdIds).hasSize(2)
            val firstId = requireNotNull(bound.single { it.crowdStrikeAid == first.aid }.asset.id)
            val secondId = requireNotNull(bound.single { it.crowdStrikeAid == second.aid }.asset.id)
            val firstSnapshots = snapshots.findCurrentByAssetId(firstId).map { it.id }
            assertThat(findings.countByAssetId(firstId)).isEqualTo(1)
            assertThat(findings.countByAssetId(secondId)).isEqualTo(1)
            val refreshed = importer.importServerVulnerabilities(listOf(batch(second, null)), "test-admin", false, true)
            assertThat(refreshed.errors).isEmpty()
            assertThat(refreshed.serversCreated).isZero()
            assertThat(findings.countByAssetId(secondId)).isZero()
            assertThat(findings.countByAssetId(firstId)).isEqualTo(1)
            assertThat(snapshots.findCurrentByAssetId(firstId).map { it.id }).containsExactlyElementsOf(firstSnapshots)
            deletion.deleteAsset(secondId, "test-admin")
            createdIds.remove(secondId)
            assertThat(assets.findById(firstId)).isPresent()
            assertThat(identities.findByCrowdStrikeAidIn(listOf(first.aid))).hasSize(1)
            assertThat(findings.countByAssetId(firstId)).isEqualTo(1)
        } finally {
            identities.findByCrowdStrikeAidIn(listOf(first.aid, second.aid)).forEach { createdIds.add(requireNotNull(it.asset.id)) }
            createdIds.forEach { deletion.deleteAsset(it, "test-admin") }
        }
    }
}
