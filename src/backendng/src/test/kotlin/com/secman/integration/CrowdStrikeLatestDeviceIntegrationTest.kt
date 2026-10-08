package com.secman.integration

import com.secman.constants.VulnerabilitySources
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.domain.Vulnerability
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class CrowdStrikeLatestDeviceIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var importer: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var identities: CrowdStrikeAssetIdentityRepository
    @Inject lateinit var vulnerabilities: VulnerabilityRepository
    @Inject lateinit var em: EntityManager

    @Test
    fun `zero finding winner removes duplicate asset and stale severities but preserves manual findings and display name`() {
        val hostname = "latest-${System.nanoTime()}.example.test"
        val winner = assets.save(Asset(name = "User display name $hostname", crowdStrikeHostname = hostname,
            nameOverriddenAt = LocalDateTime.now(), nameOverriddenBy = "test-admin", type = "DATABASE", owner = "User owner"))
        val old = assets.save(Asset(name = hostname, crowdStrikeHostname = hostname, type = "SERVER", owner = "CrowdStrike Import"))
        val oldRecord = CrowdStrikeDeviceRecord("old-${old.id}", hostname, firstSeen = Instant.parse("2026-01-01T00:00:00Z"))
        val newRecord = oldRecord.copy(aid = "new-${winner.id}", firstSeen = Instant.parse("2026-02-01T00:00:00Z"),
            lastSeen = Instant.parse("2026-09-12T01:02:03Z"), productType = "Domain Controller")
        identities.save(CrowdStrikeAssetIdentity(asset = old, crowdStrikeAid = oldRecord.aid, sourceHostname = hostname))
        identities.save(CrowdStrikeAssetIdentity(asset = winner, crowdStrikeAid = newRecord.aid, sourceHostname = hostname))
        vulnerabilities.save(Vulnerability(asset = old, vulnerabilityId = "CVE-2026-1000", cvssSeverity = "HIGH",
            scanTimestamp = LocalDateTime.now(), source = VulnerabilitySources.CROWDSTRIKE))
        vulnerabilities.save(Vulnerability(asset = winner, vulnerabilityId = "CVE-2026-1001", cvssSeverity = "LOW",
            scanTimestamp = LocalDateTime.now(), source = VulnerabilitySources.CROWDSTRIKE))
        vulnerabilities.save(Vulnerability(asset = winner, vulnerabilityId = "CVE-2026-1002", cvssSeverity = "HIGH",
            scanTimestamp = LocalDateTime.now(), source = "MANUAL"))
        em.flush()
        // An HTTP import starts with no managed entities from fixture creation.
        em.clear()
        val batch = CrowdStrikeVulnerabilityBatchDto(hostname, null, null, null, null, null, null,
            vulnerabilities = emptyList(), runSeverities = listOf("HIGH"), crowdStrikeAids = setOf(newRecord.aid),
            deviceSelection = CrowdStrikeDeviceSelection(newRecord, listOf(oldRecord)))
        repeat(2) {
            importer.importVulnerabilitiesForServer(batch, "test-admin", allowDeviceReplacement = true)
            em.flush()
            em.clear()
            assertThat(assets.findById(old.id!!)).isEmpty()
            val surviving = assets.findById(winner.id!!).orElseThrow()
            assertThat(surviving.name).isEqualTo(winner.name)
            assertThat(surviving.owner).isEqualTo("User owner")
            assertThat(surviving.type).isEqualTo("DATABASE")
            assertThat(surviving.crowdStrikeProductType).isEqualTo("Domain Controller")
            assertThat(identities.findByAssetIdIn(listOf(winner.id!!)).map { it.crowdStrikeAid }).containsExactly(newRecord.aid)
            assertThat(vulnerabilities.findByAssetId(winner.id!!, Pageable.from(0, 10)).content.map { it.source })
                .containsExactly("MANUAL")
        }
    }
    @Test
    fun `same-name cloud instances are restored separately with provider type contact and enrollment history`() {
        val suffix = System.nanoTime()
        val hostname = "reused-$suffix.example.test"
        val contact = Instant.parse("2026-09-12T01:02:03Z")
        val devices = (1..2).map { index -> CrowdStrikeDeviceRecord(
            "restore-$suffix-$index", hostname, instanceId = "i-$suffix-$index", cloudAccountId = "account-a",
            firstSeen = contact.minusSeconds(3600), lastSeen = contact,
            productType = if (index == 1) "Server" else "Domain Controller"
        ) }
        repeat(2) {
            devices.forEach { device ->
                val batch = CrowdStrikeVulnerabilityBatchDto(hostname, null, device.cloudAccountId,
                    device.instanceId, null, null, null, vulnerabilities = emptyList(),
                    crowdStrikeAids = setOf(device.aid), deviceSelection = CrowdStrikeDeviceSelection(device))
                importer.importVulnerabilitiesForServer(batch, "test-admin", allowDeviceReplacement = true)
                em.flush()
                em.clear()
            }
        }
        val stored = identities.findByCrowdStrikeAidIn(devices.map { it.aid })
        assertThat(stored.map { it.asset.id }.distinct()).hasSize(2)
        assertThat(stored.map { it.asset.type }).containsOnly("SERVER")
        assertThat(stored.map { it.asset.crowdStrikeProductType }).containsExactlyInAnyOrder("Server", "Domain Controller")
        assertThat(stored.map { it.asset.crowdStrikeAgentSeenAt }).containsOnly(
            LocalDateTime.ofInstant(contact, java.time.ZoneOffset.UTC))
        val history = em.createQuery("SELECT h FROM CrowdStrikeEnrollmentHistory h WHERE h.hostname = :hostname",
            com.secman.domain.CrowdStrikeEnrollmentHistory::class.java).setParameter("hostname", hostname).resultList
        assertThat(history.map { it.crowdStrikeAid }).containsExactlyInAnyOrderElementsOf(devices.map { it.aid })
    }

    @Test
    fun `hostname only legacy binding does not block an independent cloud instance`() {
        val hostname = "legacy-cloud-${System.nanoTime()}.example.test"
        val legacy = assets.save(Asset(name = hostname, type = "SERVER", owner = "CrowdStrike Import"))
        identities.save(CrowdStrikeAssetIdentity(asset = legacy, crowdStrikeAid = "legacy-${legacy.id}", sourceHostname = hostname))
        val finding = vulnerabilities.save(Vulnerability(asset = legacy, vulnerabilityId = "CVE-2026-1001",
            cvssSeverity = "HIGH", scanTimestamp = LocalDateTime.now(), source = VulnerabilitySources.CROWDSTRIKE))
        val device = CrowdStrikeDeviceRecord("independent-${legacy.id}", hostname, instanceId = "i-${legacy.id}",
            cloudAccountId = "123456789012", firstSeen = Instant.parse("2026-01-01T00:00:00Z"),
            lastSeen = Instant.parse("2026-09-30T12:00:00Z"), productType = "Server")
        val batch = CrowdStrikeVulnerabilityBatchDto(hostname, null, device.cloudAccountId, device.instanceId,
            null, null, null, vulnerabilities = emptyList(), crowdStrikeAids = setOf(device.aid),
            deviceSelection = CrowdStrikeDeviceSelection(device))

        importer.importVulnerabilitiesForServer(batch, "test-admin", allowDeviceReplacement = true)
        em.flush()
        em.clear()

        val imported = identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset
        assertThat(imported.id).isNotEqualTo(legacy.id)
        assertThat(imported.cloudInstanceId).isEqualTo(device.instanceId)
        assertThat(identities.findByCrowdStrikeAidIn(listOf("legacy-${legacy.id}")).single().asset.id).isEqualTo(legacy.id)
        assertThat(vulnerabilities.findById(finding.id!!)).isPresent
    }

    @Test
    fun `reconcile resolves more than one batch of cloud instances and preserves source timestamps`() {
        val suffix = System.nanoTime()
        val contact = Instant.parse("2026-09-30T12:00:00Z")
        val hosts = (1..1001).map { index ->
            val hostname = "scope-$suffix-$index"
            val asset = assets.save(Asset(name = hostname, type = "SERVER", owner = "CrowdStrike Import",
                cloudInstanceId = "i-$suffix-$index", crowdStrikeAgentSeenAt = LocalDateTime.ofInstant(contact, java.time.ZoneOffset.UTC)))
            identities.save(CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = "aid-$suffix-$index", sourceHostname = hostname))
            com.secman.dto.QueriedHostDto(hostname = hostname, instanceId = asset.cloudInstanceId,
                crowdStrikeAid = "aid-$suffix-$index", lastSeen = contact)
        }
        em.flush()
        em.clear()

        val result = importer.reconcileStaleCrowdStrikeImports(LocalDateTime.now().minusHours(1), listOf("HIGH"), hosts)

        assertThat(result.resolvedAssetCount).isEqualTo(1001)
        assertThat(result.aborted).isFalse()
        val stored = identities.findByCrowdStrikeAidIn(hosts.map { it.crowdStrikeAid!! })
        assertThat(stored.map { it.asset.crowdStrikeAgentSeenAt }).containsOnly(LocalDateTime.ofInstant(contact, java.time.ZoneOffset.UTC))
    }

}
