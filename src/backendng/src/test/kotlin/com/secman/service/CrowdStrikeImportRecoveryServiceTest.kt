package com.secman.service

import com.secman.domain.Asset
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.*
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CrowdStrikeImportRecoveryServiceTest {
    private val assets = mockk<AssetRepository>()
    private val identities = mockk<CrowdStrikeAssetIdentityRepository>()
    private val demands = mockk<DemandRepository>()
    private val assessments = mockk<RiskAssessmentRepository>()
    private val risks = mockk<RiskRepository>()
    private val deletion = mockk<AssetCascadeDeleteService>()
    private val service = CrowdStrikeImportRecoveryService(assets, identities, demands, assessments, risks, deletion)
    private val asset = Asset(id = 7, name = "ARCGIS", type = "SERVER", owner = "fixture", cloudInstanceId = "i-old")
    private val batch = CrowdStrikeVulnerabilityBatchDto(hostname = "ARCGIS", groups = null, cloudAccountId = null,
        cloudInstanceId = "i-new", adDomain = null, osVersion = null, ip = null, vulnerabilities = emptyList(),
        crowdStrikeAids = setOf("aid-one"), findingCoverage = com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage(0, 0),
        deviceSelection = CrowdStrikeDeviceSelection(CrowdStrikeDeviceRecord("aid-one", "ARCGIS", cloudAccountId = "123456789012")))
    private val conflict = CrowdStrikeIdentityConflict("CLOUD_ACCOUNT_CONFLICT", "conflict", 7, "i-old", "i-new")

    private fun stub() {
        every { assets.findCrowdStrikeReplacementCandidates("ARCGIS", any()) } returns Page.of(listOf(asset), Pageable.from(0, 2), 1)
        every { identities.findByAssetIdIn(listOf(7L)) } returns emptyList()
        every { demands.findByExistingAssetId(7) } returns emptyList()
        every { assessments.findAllByInvolvedAssetId(7) } returns emptyList()
        every { risks.findByAssetId(7) } returns emptyList()
    }

    @Test
    fun `missing account domain or enrollment evidence cannot authorize deletion`() {
        val selected = requireNotNull(batch.deviceSelection).selected
        for ((code, device) in listOf(
            "CLOUD_ACCOUNT_CONFLICT" to selected.copy(cloudAccountId = null),
            "AD_DOMAIN_CONFLICT" to selected.copy(adDomain = null),
            "ENROLLMENT_MISMATCH" to selected.copy(firstSeen = null))) {
            val payload = batch.copy(deviceSelection = CrowdStrikeDeviceSelection(device))
            assertThat(CrowdStrikeImportRecoveryService.canRecover(payload,
                CrowdStrikeIdentityConflict(code, "conflict", 7))).isFalse()
        }
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing incomplete or invalid coverage never permits deletion`() {
        for (coverage in listOf(null,
            com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage(0, 0, complete = false),
            com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage(1, 0, invalid = 1))) {
            assertThrows<IllegalArgumentException> { service.recover(batch.copy(findingCoverage = coverage), conflict, "admin") }
        }
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `delete uses normal cascade guard under lock and refuses changed hostname instance or references`() {
        stub()
        var guard: ((Asset) -> Boolean)? = null
        every { deletion.deleteAsset(7, "admin", false, null, any()) } answers {
            guard = arg(4)
            mockk(relaxed = true)
        }
        service.recover(batch, conflict, "admin")
        assertThat(guard!!(asset)).isTrue()
        assertThat(guard!!(asset.copy(name = "OTHER"))).isFalse()
        assertThat(guard!!(asset.copy(cloudInstanceId = "i-changed"))).isFalse()
        assertThat(guard!!(asset.copy(cloudAccountId = "123456789012"))).isFalse()
        every { demands.findByExistingAssetId(7) } returns listOf(mockk())
        assertThat(guard!!(asset)).isFalse()
    }

    @Test
    fun `ambiguous or wrong hostname match cannot reach deletion`() {
        stub()
        for (matches in listOf(emptyList(), listOf(asset, asset.copy(id = 8)), listOf(asset.copy(id = 8)))) {
            every { assets.findCrowdStrikeReplacementCandidates("ARCGIS", any()) } returns
                Page.of(matches, Pageable.from(0, 2), matches.size.toLong())
            assertThrows<IllegalStateException> { service.recover(batch, conflict, "admin") }
        }
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unrelated Falcon bindings cannot be deleted`() {
        stub()
        every { identities.findByAssetIdIn(listOf(7L)) } returns listOf(
            com.secman.domain.CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = "sibling", sourceHostname = "ARCGIS"))
        assertThrows<IllegalStateException> { service.recover(batch, conflict, "admin") }
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }
}
