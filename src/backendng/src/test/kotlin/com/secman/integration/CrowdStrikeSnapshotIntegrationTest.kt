package com.secman.integration

import com.secman.constants.VulnerabilitySources
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.domain.Vulnerability
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.VulnerabilityDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.CrowdStrikeSnapshotRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.CrowdStrikeQueryService
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class CrowdStrikeSnapshotIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var importer: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var queries: CrowdStrikeQueryService
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var identities: CrowdStrikeAssetIdentityRepository
    @Inject lateinit var findings: VulnerabilityRepository
    @Inject lateinit var snapshots: CrowdStrikeSnapshotRepository
    @Inject lateinit var em: EntityManager

    private fun device() = CrowdStrikeDeviceRecord("aid-${System.nanoTime()}", "snapshot-${System.nanoTime()}.example.test",
        firstSeen = Instant.parse("2026-01-01T00:00:00Z"), productType = "Server")
    private fun batch(device: CrowdStrikeDeviceRecord, rows: List<VulnerabilityDto> = emptyList(), scope: List<String>? = null,
        superseded: List<CrowdStrikeDeviceRecord> = emptyList()) = CrowdStrikeVulnerabilityBatchDto(
        device.hostname, null, device.cloudAccountId, device.instanceId, device.adDomain, null, null, vulnerabilities = rows,
        crowdStrikeAids = setOf(device.aid), runSeverities = scope,
        deviceSelection = CrowdStrikeDeviceSelection(device, superseded))

    @Test
    fun `stored ORM and native readers hide lower Falcon severities but retain other sources`() {
        val device = device()
        importer.importVulnerabilitiesForServer(batch(device, listOf("HIGH", "CRITICAL", "MEDIUM", "LOW", "INFORMATIONAL", "UNKNOWN").mapIndexed { i, severity ->
            VulnerabilityDto("CVE-2026-${4000 + i}", severity, "Product", 10)
        }), "test-admin", true)
        em.flush(); em.clear()
        val asset = identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset
        findings.save(Vulnerability(asset = asset, vulnerabilityId = "MANUAL-LOW", cvssSeverity = "LOW",
            source = "MANUAL", scanTimestamp = LocalDateTime.now()))
        em.flush(); em.clear()
        val id = requireNotNull(asset.id)
        assertThat(findings.findByAssetId(id, Pageable.from(0, 20)).content.map { it.vulnerabilityId })
            .containsExactlyInAnyOrder("CVE-2026-4000", "CVE-2026-4001", "MANUAL-LOW")
        val count = em.createNativeQuery("SELECT COUNT(*) FROM ${com.secman.repository.CrowdStrikeSnapshotSql.CURRENT_FINDINGS} v WHERE v.asset_id = :id")
            .setParameter("id", id).singleResult as Number
        assertThat(count.toInt()).isEqualTo(3)
        assertThat((em.createNativeQuery("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id")
            .setParameter("id", id).singleResult as Number).toInt()).isEqualTo(7)
    }

    @Test
    fun `verified empty snapshot is stored and served without falling through to Falcon`() {
        val device = device()
        importer.importVulnerabilitiesForServer(batch(device), "test-admin", true)
        em.flush(); em.clear()
        val result = requireNotNull(queries.queryFromDatabase(device.hostname))
        assertThat(result.dataSource).isEqualTo("DATABASE")
        assertThat(result.snapshotStatus).isEqualTo("VERIFIED")
        assertThat(result.selectedAid).isEqualTo(device.aid)
        assertThat(result.totalCount).isZero()
        assertThat(result.snapshotCoverage).hasSize(6)
    }

    @Test
    fun `ORM and native reads exclude retired snapshots while legacy risk stays visible`() {
        val device = device()
        importer.importVulnerabilitiesForServer(batch(device, listOf(VulnerabilityDto("CVE-2026-1234", "HIGH", "Product", 10))), "test-admin", true)
        em.flush(); em.clear()
        val asset = identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset
        val oldSnapshot = snapshots.findByAssetIdAndActive(requireNotNull(asset.id), true).first { it.severity == "HIGH" }
        val rows = findings.findByAssetId(requireNotNull(asset.id), Pageable.from(0, 10))
        assertThat(rows.content.single().crowdStrikeSnapshotId).isEqualTo(oldSnapshot.id)
        oldSnapshot.active = false; snapshots.update(oldSnapshot)
        findings.save(Vulnerability(asset = asset, vulnerabilityId = "CVE-2026-9999", cvssSeverity = "HIGH",
            scanTimestamp = LocalDateTime.now(), source = VulnerabilitySources.CROWDSTRIKE))
        em.flush(); em.clear()
        assertThat(findings.findByAssetId(requireNotNull(asset.id), Pageable.from(0, 10)).content.map { it.vulnerabilityId })
            .containsExactly("CVE-2026-9999")
        val nativeCount = em.createNativeQuery("SELECT COUNT(*) FROM ${com.secman.repository.CrowdStrikeSnapshotSql.CURRENT_FINDINGS} v WHERE v.asset_id = :id")
            .setParameter("id", asset.id).singleResult as Number
        assertThat(nativeCount.toLong()).isEqualTo(1)
    }

    @Test
    fun `entirely filtered legacy attempt preserves the last successful snapshot`() {
        val device = device()
        importer.importVulnerabilitiesForServer(batch(device, listOf(VulnerabilityDto("CVE-2026-3002", "HIGH", "Product", 10))), "test-admin", true)
        em.flush(); em.clear()
        importer.importVulnerabilitiesForServer(batch(device, listOf(VulnerabilityDto("", "HIGH", "Product", 10)))
            .copy(deviceSelection = null), "legacy-admin", false)
        em.flush(); em.clear()
        val result = requireNotNull(queries.queryFromDatabase(device.hostname))
        assertThat(result.snapshotStatus).isEqualTo("VERIFIED")
        assertThat(result.vulnerabilities.map { it.cveId }).containsExactly("CVE-2026-3002")
    }

    @Test
    fun `legacy scoped writer invalidates only its fetched coverage`() {
        val device = device()
        importer.importVulnerabilitiesForServer(batch(device, listOf(VulnerabilityDto("CVE-2026-3003", "LOW", "Product", 10))), "test-admin", true)
        em.flush(); em.clear()
        importer.importVulnerabilitiesForServer(batch(device, scope = listOf("HIGH")).copy(deviceSelection = null), "legacy-admin", false)
        em.flush(); em.clear()
        val result = requireNotNull(queries.queryFromDatabase(device.hostname))
        assertThat(result.snapshotStatus).isEqualTo("PARTIAL")
        assertThat(result.vulnerabilities).isEmpty()
        assertThat(snapshots.findCurrentByAssetId(requireNotNull(result.assetId)).map { it.severity }).contains("LOW").doesNotContain("HIGH")
    }

    @Test
    fun `legacy writer cannot retain a verified coverage claim`() {
        val device = device()
        importer.importVulnerabilitiesForServer(batch(device), "test-admin", true)
        em.flush(); em.clear()
        importer.importVulnerabilitiesForServer(batch(device, listOf(VulnerabilityDto("CVE-2026-3001", "HIGH", "Product", 10)))
            .copy(deviceSelection = null), "legacy-admin", false)
        em.flush(); em.clear()
        val result = requireNotNull(queries.queryFromDatabase(device.hostname))
        assertThat(result.snapshotStatus).isEqualTo("UNVERIFIED")
        assertThat(result.totalCount).isEqualTo(1)
    }

    @Test
    fun `AWS coverage queries distinguish partial from verified empty snapshot`() {
        val device = device().copy(cloudAccountId = "123456789012", instanceId = "i-0123456789abcdef0", lastSeen = Instant.now())
        importer.importVulnerabilitiesForServer(batch(device, scope = listOf("HIGH")), "test-admin", true)
        em.flush(); em.clear()
        val window = com.secman.service.FalconSeenTodayWindow.today()
        assertThat(findings.countUnknownAwsServers(window.startUtc, window.endUtc)).isEqualTo(1)
        importer.importVulnerabilitiesForServer(batch(device), "test-admin", true)
        em.flush(); em.clear()
        assertThat(findings.countUnknownAwsServers(window.startUtc, window.endUtc)).isZero()
        assertThat(findings.countDirtyVerifiedAwsServers(LocalDateTime.now(), window.startUtc, window.endUtc)).isZero()
    }

    @Test
    fun `scoped refresh keeps older verified findings in latest account projections`() {
        val device = device()
        importer.importVulnerabilitiesForServer(batch(device, listOf(VulnerabilityDto("CVE-2026-2001", "LOW", "Product", 10))), "test-admin", true)
        em.flush(); em.clear()
        importer.importVulnerabilitiesForServer(batch(device, listOf(VulnerabilityDto("CVE-2026-2002", "HIGH", "Product", 10)), listOf("HIGH")), "test-admin", true)
        em.flush(); em.clear()
        val id = requireNotNull(identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset.id)
        assertThat(findings.findLatestVulnerabilitiesForAssetIds(setOf(id)).map { it.vulnerabilityId })
            .containsExactly("CVE-2026-2002")
        assertThat((em.createNativeQuery("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id AND cvss_severity = 'LOW'")
            .setParameter("id", id).singleResult as Number).toLong()).isEqualTo(1)
        assertThat(findings.deleteStaleVulnerabilitiesBySource(LocalDateTime.now().plusSeconds(1), listOf("LOW"), "CROWDSTRIKE", listOf(id))).isZero()
    }

    @Test
    fun `proven retired duplicate with manual relationships keeps inventory and loses only Falcon data`() {
        val device = device()
        val retired = device.copy(aid = "old-${device.aid}", firstSeen = device.firstSeen!!.minusSeconds(1))
        val duplicate = assets.save(Asset(name = device.hostname, type = "SERVER", owner = "Human owner"))
        identities.save(CrowdStrikeAssetIdentity(asset = duplicate, crowdStrikeAid = retired.aid, sourceHostname = device.hostname))
        val canonical = assets.save(Asset(name = "Display ${device.hostname}", crowdStrikeHostname = device.hostname, type = "SERVER", owner = "Human owner"))
        identities.save(CrowdStrikeAssetIdentity(asset = canonical, crowdStrikeAid = device.aid, sourceHostname = device.hostname))
        findings.save(Vulnerability(asset = duplicate, vulnerabilityId = "CVE-2026-1001", scanTimestamp = LocalDateTime.now(), source = "MANUAL"))
        findings.save(Vulnerability(asset = duplicate, vulnerabilityId = "CVE-2026-1002", scanTimestamp = LocalDateTime.now(), source = VulnerabilitySources.CROWDSTRIKE))
        em.flush(); em.clear()
        importer.importVulnerabilitiesForServer(batch(device, superseded = listOf(retired)), "test-admin", true)
        em.flush(); em.clear()
        assertThat(assets.findById(requireNotNull(duplicate.id))).isPresent
        assertThat(findings.findByAssetId(requireNotNull(duplicate.id), Pageable.from(0, 10)).content.map { it.vulnerabilityId })
            .containsExactly("CVE-2026-1001")
        assertThat(identities.findByAssetIdIn(listOf(requireNotNull(duplicate.id)))).isEmpty()
    }
}
