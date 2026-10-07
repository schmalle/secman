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
            nameOverriddenAt = LocalDateTime.now(), nameOverriddenBy = "test-admin", type = "SERVER", owner = "User owner"))
        val old = assets.save(Asset(name = hostname, crowdStrikeHostname = hostname, type = "SERVER", owner = "CrowdStrike Import"))
        val oldRecord = CrowdStrikeDeviceRecord("old-${old.id}", hostname, firstSeen = Instant.parse("2026-01-01T00:00:00Z"))
        val newRecord = oldRecord.copy(aid = "new-${winner.id}", firstSeen = Instant.parse("2026-02-01T00:00:00Z"))
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
            assertThat(identities.findByAssetIdIn(listOf(winner.id!!)).map { it.crowdStrikeAid }).containsExactly(newRecord.aid)
            assertThat(vulnerabilities.findByAssetId(winner.id!!, Pageable.from(0, 10)).content.map { it.source })
                .containsExactly("MANUAL")
        }
    }
}
