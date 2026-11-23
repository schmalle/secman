package com.secman.integration

import com.secman.constants.VulnerabilitySources
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage
import com.secman.crowdstrike.dto.CrowdStrikeRemediationDto
import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.domain.Vulnerability
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.VulnerabilityDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.CrowdStrikeSnapshotRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.projection.AwsServerDetectionCounts
import com.secman.service.AwsCleanServerKpiService
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
import java.time.temporal.ChronoUnit

class CrowdStrikeSnapshotIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var awsKpi: AwsCleanServerKpiService
    @Inject lateinit var importer: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var queries: CrowdStrikeQueryService
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var identities: CrowdStrikeAssetIdentityRepository
    @Inject lateinit var findings: VulnerabilityRepository
    @Inject lateinit var snapshots: CrowdStrikeSnapshotRepository
    @Inject lateinit var em: EntityManager

    private fun device() = CrowdStrikeDeviceRecord("aid-${System.nanoTime()}", "snapshot-${System.nanoTime()}.example.test",
        firstSeen = Instant.parse("2026-01-01T00:00:00Z"), lastSeen = Instant.now().minusSeconds(60), productType = "Server")
    private fun batch(device: CrowdStrikeDeviceRecord, rows: List<VulnerabilityDto> = emptyList(), scope: List<String>? = null,
        superseded: List<CrowdStrikeDeviceRecord> = emptyList()) = CrowdStrikeVulnerabilityBatchDto(
        device.hostname, null, device.cloudAccountId, device.instanceId, device.adDomain, null, null, vulnerabilities = rows,
        crowdStrikeAids = setOf(device.aid), runSeverities = scope,
        deviceSelection = CrowdStrikeDeviceSelection(device, superseded))

    @Test
    fun `identical verified findings retain their IDs and refresh reconcile timestamps`() {
        val device = device()
        val detected = LocalDateTime.now().minusDays(5).truncatedTo(ChronoUnit.SECONDS)
        val high = VulnerabilityDto("CVE-2026-4210", "HIGH", "Product", 5,
            detectedAt = detected, patchPublicationDate = detected, cvePublishedDate = detected.minusDays(1),
            noVendorFix = false)
        val critical = high.copy(cveId = "CVE-2026-4211", severity = "CRITICAL", noVendorFix = null)
        val initial = batch(device, listOf(high, critical), listOf("HIGH", "CRITICAL")).copy(
            findingCoverage = CrowdStrikeFindingCoverage(2, 2)
        )
        val first = importer.importVulnerabilitiesForServer(initial, "test-admin", true)
        val assetId = requireNotNull(first.outcome?.assetId)
        em.flush()
        em.clear()
        val before = findings.findByAssetId(assetId, Pageable.from(0, 10)).content.associateBy { it.cvssSeverity }
        val highBefore = before.getValue("HIGH")
        val cutoff = LocalDateTime.now().minusMinutes(1)
        em.createNativeQuery("UPDATE vulnerability SET import_timestamp = :old, days_open = '4 days' WHERE id = :id")
            .setParameter("old", cutoff.minusMinutes(1)).setParameter("id", highBefore.id).executeUpdate()
        em.clear()

        val refresh = initial.copy(vulnerabilities = listOf(high), runSeverities = listOf("HIGH"),
            findingCoverage = CrowdStrikeFindingCoverage(1, 1),
            deviceSelection = requireNotNull(initial.deviceSelection).copy(observedAt = Instant.now()))
        val result = importer.importVulnerabilitiesForServer(refresh, "test-admin", true)
        em.flush()
        em.clear()

        val retained = requireNotNull(result.findingChanges)
        assertThat(retained.mode).isEqualTo("RETAINED")
        assertThat(retained.unchanged).isEqualTo(1)
        assertThat(retained.rowsDeleted).isZero()
        assertThat(result.vulnerabilitiesImported).isEqualTo(1)
        val after = findings.findByAssetId(assetId, Pageable.from(0, 10)).content.associateBy { it.cvssSeverity }
        val highAfter = after.getValue("HIGH")
        assertThat(highAfter.id).isEqualTo(highBefore.id)
        assertThat(highAfter.crowdStrikeSnapshotId).isEqualTo(highBefore.crowdStrikeSnapshotId)
        assertThat(highAfter.importTimestamp).isAfter(cutoff)
        assertThat(highAfter.daysOpen).isEqualTo("5 days")
        assertThat(highAfter.firstSeenAt).isEqualTo(detected)
        assertThat(after.getValue("CRITICAL").importTimestamp).isEqualTo(before.getValue("CRITICAL").importTimestamp)
        assertThat(findings.countRefreshedBySourceSinceCutoff(cutoff, listOf("HIGH"), VulnerabilitySources.CROWDSTRIKE))
            .isGreaterThanOrEqualTo(1)
        assertThat(snapshots.findCurrentByAssetId(assetId).single { it.severity == "HIGH" }.acceptedCount).isEqualTo(1)
        assertThat(snapshots.findByAssetIdAndActive(assetId, false).single { it.severity == "HIGH" }.acceptedCount).isEqualTo(2)

        val changed = refresh.copy(vulnerabilities = listOf(high.copy(noVendorFix = true)),
            deviceSelection = requireNotNull(refresh.deviceSelection).copy(observedAt = Instant.now()))
        val replacement = importer.importVulnerabilitiesForServer(changed, "test-admin", true)
        assertThat(replacement.findingChanges?.mode).isEqualTo("REPLACE")
        assertThat(replacement.findingChanges?.changed).isEqualTo(1)
        assertThat(replacement.findingChanges?.rowsDeleted).isEqualTo(1)
    }

    @Test
    fun `remediation is persisted, preserved on lookup failure and replaced when Falcon updates it`() {
        val device = device()
        val initialAdvice = CrowdStrikeRemediationDto("Update the package", vendorUrl = "https://vendor.example.test/advisory")
        val changedAdvice = initialAdvice.copy(action = "Upgrade to the latest supported version")
        val row = VulnerabilityDto("CVE-2026-4310", "HIGH", "Product", 5,
            detectedAt = LocalDateTime.now().minusDays(5).truncatedTo(ChronoUnit.SECONDS),
            remediation = listOf(initialAdvice))

        fun importAndRead(guidance: List<CrowdStrikeRemediationDto>?): List<CrowdStrikeRemediationDto>? {
            val request = batch(device, listOf(row.copy(remediation = guidance)), listOf("HIGH"))
                .copy(findingCoverage = CrowdStrikeFindingCoverage(1, 1))
            importer.importVulnerabilitiesForServer(request, "test-admin", true)
            em.flush()
            em.clear()
            return requireNotNull(queries.queryFromDatabase(device.hostname)).vulnerabilities.single().remediation
        }

        assertThat(importAndRead(listOf(initialAdvice))).containsExactly(initialAdvice)
        assertThat(importAndRead(null)).containsExactly(initialAdvice)
        assertThat(importAndRead(listOf(changedAdvice))).containsExactly(changedAdvice)
        assertThat(importAndRead(emptyList())).isEmpty()
    }

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
    fun `AWS KPI uses host detection across the current fleet and exposes unknown coverage`() {
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val cutoff = now.minusDays(30)
        val hosts = (1..6).map { index ->
            device().copy(cloudAccountId = "123456789012",
                instanceId = "i-0123456789abcdef$index", lastSeen = Instant.now().minus(index.toLong(), ChronoUnit.HOURS))
        }
        val finding = VulnerabilityDto("CVE-2026-3001", "HIGH", "Product", 2,
            detectedAt = now.minusDays(2), cvePublishedDate = now.minusDays(100))
        val fixture = listOf(
            batch(hosts[0], listOf(finding)),
            batch(hosts[1], listOf(finding.copy(detectedAt = now.minusDays(40))), listOf("HIGH", "CRITICAL")),
            batch(hosts[2]),
            batch(hosts[4], listOf(finding.copy(severity = "LOW", detectedAt = cutoff))),
            batch(hosts[5], listOf(finding.copy(detectedAt = null, detectionUnknown = true)))
        )
        fixture.forEach { importer.importVulnerabilitiesForServer(it, "test-admin", true) }
        assets.save(Asset(name = hosts[3].hostname, type = "SERVER", owner = "ops",
            verifiedObservedAt = LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(1),
            cloudAccountId = hosts[3].cloudAccountId, cloudInstanceId = hosts[3].instanceId))
        assets.save(Asset(name = "account-placeholder", type = "AWS_ACCOUNT", owner = "ops",
            cloudAccountId = hosts[3].cloudAccountId))
        em.flush()
        em.clear()

        assertThat(findings.countAwsServerDetectionAge(cutoff, now))
            .isEqualTo(AwsServerDetectionCounts(6, 3, 1, 2))
        assertThat(findings.countAwsServerDetectionAge(cutoff.plusNanos(1_000), now))
            .isEqualTo(AwsServerDetectionCounts(6, 2, 2, 2))

        awsKpi.recalculate(requireSuccess = true)
        val measured = awsKpi.getKpi()
        assertThat(measured.available).isTrue()
        assertThat(measured.totalAwsServers).isEqualTo(6)
        assertThat(measured.cleanAwsServers).isEqualTo(2)
        assertThat(measured.overdueAwsServers).isEqualTo(2)
        assertThat(measured.unknownAwsServers).isEqualTo(2)
        assertThat(measured.percentage).isEqualTo(33.3)
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
        // Only a shared cloud instance proves the retired AID was the same machine; a hostname alone does not.
        val device = device().let { it.copy(instanceId = "i-${it.aid}", cloudAccountId = "account-snapshot") }
        val retired = device.copy(aid = "old-${device.aid}", firstSeen = device.firstSeen!!.minusSeconds(1),
            lastSeen = device.lastSeen!!.minusSeconds(3600))
        val duplicate = assets.save(Asset(name = device.hostname, type = "SERVER", owner = "Human owner", verifiedObservedAt = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1)))
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
