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
import com.secman.service.CrowdStrikeIdentityConflict
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class CrowdStrikeLatestDeviceIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var importer: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var identities: CrowdStrikeAssetIdentityRepository
    @Inject lateinit var vulnerabilities: VulnerabilityRepository
    @Inject lateinit var em: EntityManager

    @Test
    fun `Azure VM move rebinds Falcon to the new subscription and keeps the old asset and manual findings`() {
        val now = Instant.now()
        val original = CrowdStrikeDeviceRecord("azure-${UUID.randomUUID()}", "azure-${UUID.randomUUID()}",
            instanceId = UUID.randomUUID().toString(), cloudAccountId = UUID.randomUUID().toString(),
            firstSeen = now.minusSeconds(3600), lastSeen = now.minusSeconds(1200), serviceProvider = "AZURE")
        importDevice(original)
        val former = identities.findByCrowdStrikeAidIn(listOf(original.aid)).single().asset
        listOf("MANUAL", VulnerabilitySources.CROWDSTRIKE).forEach { source ->
            vulnerabilities.save(Vulnerability(asset = former, vulnerabilityId = "CVE-2026-1030",
                scanTimestamp = LocalDateTime.now(), source = source))
        }
        em.flush()
        em.clear()

        val moved = original.copy(instanceId = UUID.randomUUID().toString(), cloudAccountId = UUID.randomUUID().toString(),
            lastSeen = now.minusSeconds(600))
        val conflict = assertThrows<CrowdStrikeIdentityConflict> { importDevice(moved.copy(serviceProvider = null)) }
        assertThat(conflict.code).isEqualTo("INCOMPLETE_IDENTITY")
        val bootstrap = moved.copy(aid = "bootstrap-${UUID.randomUUID()}", lastSeen = now.minusSeconds(601))
        repeat(2) { importDevice(moved, listOf(bootstrap)) }

        val current = identities.findByCrowdStrikeAidIn(listOf(original.aid)).single().asset
        assertThat(current.id).isNotEqualTo(former.id)
        assertThat(current.cloudInstanceId).isEqualTo(moved.instanceId)
        assertThat(current.cloudAccountId).isEqualTo(moved.cloudAccountId)
        assertThat(assets.findById(former.id!!).orElseThrow().cloudAccountId).isEqualTo(original.cloudAccountId)
        assertThat(vulnerabilities.findByAssetId(former.id!!, Pageable.from(0, 10)).content.map { it.source })
            .containsExactly("MANUAL")
    }

    @Test
    fun `Azure subscription move retires the former binding when a newer AID wins on the destination VM`() {
        val now = Instant.now()
        val original = CrowdStrikeDeviceRecord("desktop-${UUID.randomUUID()}", "desktop-${UUID.randomUUID()}",
            instanceId = UUID.randomUUID().toString(), cloudAccountId = UUID.randomUUID().toString(),
            firstSeen = now.minusSeconds(3600), lastSeen = now.minusSeconds(1200), serviceProvider = "AZURE")
        importDevice(original)
        val formerId = identities.findByCrowdStrikeAidIn(listOf(original.aid)).single().asset.id!!
        val moved = original.copy(instanceId = UUID.randomUUID().toString(), cloudAccountId = UUID.randomUUID().toString(),
            lastSeen = now.minusSeconds(600))
        val winner = moved.copy(aid = "winner-${UUID.randomUUID()}", firstSeen = now.minusSeconds(900),
            lastSeen = now.minusSeconds(500))

        importDevice(winner, listOf(moved))
        assertThat(identities.findByCrowdStrikeAidIn(listOf(original.aid))).isEmpty()
        assertThat(assets.findById(formerId).orElseThrow().cloudAccountId).isEqualTo(original.cloudAccountId)
        val current = identities.findByCrowdStrikeAidIn(listOf(winner.aid)).single().asset
        assertThat(current.id).isNotEqualTo(formerId)
        assertThat(current.cloudAccountId).isEqualTo(winner.cloudAccountId)
        assertThat(current.cloudInstanceId).isEqualTo(winner.instanceId)
    }

    @Test
    fun `new contact accepts a nonoverlapping non-cloud enrollment and an older contact cannot regress it`() {
        val now = Instant.now()
        val original = CrowdStrikeDeviceRecord("renewed-${UUID.randomUUID()}", "renewed-${UUID.randomUUID()}",
            firstSeen = now.minusSeconds(7200), lastSeen = now.minusSeconds(3600))
        importDevice(original)
        val assetId = identities.findByCrowdStrikeAidIn(listOf(original.aid)).single().asset.id
        val current = original.copy(firstSeen = now.minusSeconds(1800), lastSeen = now.minusSeconds(600))
        importDevice(current)
        importDevice(current.copy(lastSeen = current.lastSeen!!.minusSeconds(22)))

        val binding = identities.findByCrowdStrikeAidIn(listOf(current.aid)).single()
        val contact = LocalDateTime.ofInstant(current.lastSeen, ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        assertThat(binding.asset.id).isEqualTo(assetId)
        assertThat(binding.falconLastSeenAt).isEqualTo(contact)
        assertThat(binding.asset.crowdStrikeAgentSeenAt).isEqualTo(contact)
        assertThat(binding.falconFirstSeenAt).isEqualTo(
            LocalDateTime.ofInstant(current.firstSeen, ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
    }

    private fun importDevice(device: CrowdStrikeDeviceRecord, superseded: List<CrowdStrikeDeviceRecord> = emptyList()) {
        val batch = CrowdStrikeVulnerabilityBatchDto(device.hostname, null, device.cloudAccountId, device.instanceId,
            null, null, null, vulnerabilities = emptyList(), crowdStrikeAids = setOf(device.aid),
            deviceSelection = CrowdStrikeDeviceSelection(device, superseded))
        importer.importVulnerabilitiesForServer(batch, "test-admin", allowDeviceReplacement = true)
        em.flush()
        em.clear()
    }

    @Test
    fun `zero finding winner removes duplicate asset and stale severities but preserves manual findings and display name`() {
        val hostname = "latest-${System.nanoTime()}.example.test"
        val winner = assets.save(Asset(name = "User display name $hostname", crowdStrikeHostname = hostname,
            nameOverriddenAt = LocalDateTime.now(), nameOverriddenBy = "test-admin", type = "DATABASE", owner = "User owner"))
        val old = assets.save(Asset(name = hostname, crowdStrikeHostname = hostname, type = "SERVER", owner = "CrowdStrike Import"))
        // Only a shared cloud instance proves both AIDs were one machine; a hostname alone does not.
        val oldRecord = CrowdStrikeDeviceRecord("old-${old.id}", hostname, instanceId = "i-latest-${old.id}",
            cloudAccountId = "account-latest", firstSeen = Instant.parse("2026-01-01T00:00:00Z"),
            lastSeen = Instant.parse("2026-01-15T00:00:00Z"))
        val newRecord = oldRecord.copy(aid = "new-${winner.id}", firstSeen = Instant.parse("2026-02-01T00:00:00.123456789Z"),
            lastSeen = Instant.now().minusSeconds(60), productType = "Domain Controller")
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
        val batch = CrowdStrikeVulnerabilityBatchDto(hostname, null, newRecord.cloudAccountId, newRecord.instanceId, null, null, null,
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
            val binding = identities.findByAssetIdIn(listOf(winner.id!!)).single()
            assertThat(binding.crowdStrikeAid).isEqualTo(newRecord.aid)
            assertThat(binding.falconFirstSeenAt).isEqualTo(LocalDateTime.parse("2026-02-01T00:00:00.123456"))
            assertThat(vulnerabilities.findByAssetId(winner.id!!, Pageable.from(0, 10)).content.map { it.source })
                .containsExactly("MANUAL")
        }
        vulnerabilities.save(Vulnerability(asset = assets.findById(winner.id!!).orElseThrow(),
            vulnerabilityId = "CVE-2026-1003", cvssSeverity = "HIGH",
            scanTimestamp = LocalDateTime.now(), source = VulnerabilitySources.CROWDSTRIKE))
        em.flush()
        em.clear()

        val changedEnrollment = newRecord.copy(firstSeen = requireNotNull(newRecord.firstSeen).plusNanos(1_000))
        val conflict = assertThrows<CrowdStrikeIdentityConflict> {
            importer.importVulnerabilitiesForServer(
                batch.copy(deviceSelection = CrowdStrikeDeviceSelection(changedEnrollment, listOf(oldRecord))),
                "test-admin", allowDeviceReplacement = true
            )
        }
        assertThat(conflict.code).isEqualTo("ENROLLMENT_MISMATCH")
        assertThat(vulnerabilities.findByAssetId(winner.id!!, Pageable.from(0, 10)).content.map { it.vulnerabilityId })
            .containsExactlyInAnyOrder("CVE-2026-1002", "CVE-2026-1003")
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
