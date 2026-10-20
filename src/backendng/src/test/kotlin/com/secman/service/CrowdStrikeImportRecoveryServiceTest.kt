package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.*
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
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
    private val em = mockk<EntityManager>(relaxed = true)
    private val service = CrowdStrikeImportRecoveryService(assets, identities, demands, assessments, risks, deletion, em)
    private val asset = Asset(id = 7, name = "ARCGIS", type = "SERVER", owner = "fixture", cloudInstanceId = "i-old")
    private val batch = CrowdStrikeVulnerabilityBatchDto(hostname = "ARCGIS", groups = null, cloudAccountId = null,
        cloudInstanceId = "i-new", adDomain = null, osVersion = null, ip = null, vulnerabilities = emptyList(),
        crowdStrikeAids = setOf("aid-one"), findingCoverage = com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage(0, 0),
        deviceSelection = CrowdStrikeDeviceSelection(CrowdStrikeDeviceRecord("aid-one", "ARCGIS", cloudAccountId = "123456789012")))
    private val conflict = CrowdStrikeIdentityConflict("CLOUD_ACCOUNT_CONFLICT", "conflict", 7, "i-old", "i-new")

    private fun stub(targets: List<Asset> = listOf(asset), bindings: List<CrowdStrikeAssetIdentity> = emptyList()) {
        val remaining = targets.toMutableList()
        every { assets.findCrowdStrikeReplacementCandidates("ARCGIS", any()) } answers {
            Page.of(remaining.toList(), Pageable.from(0, 102), remaining.size.toLong())
        }
        every { identities.findByCrowdStrikeAidIn(any()) } answers {
            bindings.filter { it.crowdStrikeAid in firstArg<List<String>>() }
        }
        every { identities.findByAssetIdIn(any()) } answers {
            bindings.filter { it.asset.id in firstArg<List<Long>>() }
        }
        targets.forEach { target ->
            val id = requireNotNull(target.id)
            every { em.find(Asset::class.java, id, LockModeType.PESSIMISTIC_WRITE) } returns target
            every { demands.findByExistingAssetId(id) } returns emptyList()
            every { assessments.findAllByInvolvedAssetId(id) } returns emptyList()
            every { risks.findByAssetId(id) } returns emptyList()
            every { deletion.deleteAsset(id, "admin", false, null, any()) } answers {
                assertThat(arg<(Asset) -> Boolean>(4)(target)).isTrue()
                remaining.remove(target)
                mockk(relaxed = true)
            }
        }
    }

    @Test
    fun `hostname cleanup does not require matching stored AIDs account domain or enrollment`() {
        val selected = requireNotNull(batch.deviceSelection).selected
        for (code in listOf("CLOUD_ACCOUNT_CONFLICT", "AD_DOMAIN_CONFLICT", "ENROLLMENT_MISMATCH", "UNEXPLAINED_AID", "INCOMPLETE_IDENTITY", "DUPLICATE_ASSET_CONFLICT", "SUPERSEDED_IDENTITY_CONFLICT")) {
            assertThat(CrowdStrikeImportRecoveryService.canRecover(batch.copy(deviceSelection = CrowdStrikeDeviceSelection(
                selected.copy(cloudAccountId = null, adDomain = null, firstSeen = null))), CrowdStrikeIdentityConflict(code, "conflict"))).isTrue()
        }
        val wrong = batch.copy(deviceSelection = CrowdStrikeDeviceSelection(selected.copy(hostname = "OTHER")))
        assertThat(CrowdStrikeImportRecoveryService.canRecover(wrong, conflict)).isFalse()
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
    fun `all exact hostname matches and their other Falcon bindings are deleted through cascade`() {
        val sibling = asset.copy(id = 8, name = "Human label", crowdStrikeHostname = "arcgis", cloudInstanceId = "i-independent")
        stub(listOf(sibling, asset), listOf(CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = "another-aid", sourceHostname = "ARCGIS")))
        assertThat(service.recover(batch, conflict, "admin")).containsExactly(7L, 8L)
        verify(exactly = 1) { deletion.deleteAsset(7, "admin", false, null, any()) }
        verify(exactly = 1) { deletion.deleteAsset(8, "admin", false, null, any()) }
    }

    @Test
    fun `protected references on any match prevent every deletion`() {
        stub(listOf(asset, asset.copy(id = 8)))
        every { demands.findByExistingAssetId(8) } returns listOf(mockk())
        val error = assertThrows<CrowdStrikeRecoveryBlocked> { service.recover(batch, conflict, "admin") }
        assertThat(error.reason).isEqualTo("PROTECTED_REFERENCES")
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `target changed between initial rejection and recovery cannot be deleted`() {
        stub(listOf(asset.copy(updatedAt = java.time.LocalDateTime.now())))
        val error = assertThrows<CrowdStrikeRecoveryBlocked> { service.recover(batch, conflict, "admin") }
        assertThat(error.reason).isEqualTo("TARGET_CHANGED")
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `conflicting asset outside exact hostname is never deleted`() {
        stub(listOf(asset.copy(id = 8)))
        val error = assertThrows<CrowdStrikeRecoveryBlocked> { service.recover(batch, conflict, "admin") }
        assertThat(error.reason).isEqualTo("CONFLICT_OUTSIDE_HOSTNAME")
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `renamed asset discovered under lock cannot be deleted`() {
        stub()
        every { em.refresh(asset, LockModeType.PESSIMISTIC_WRITE) } answers { asset.crowdStrikeHostname = "OTHER" }
        assertThrows<CrowdStrikeRecoveryBlocked> { service.recover(batch, conflict, "admin") }
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `selected AID outside hostname scope prevents every deletion`() {
        val outside = asset.copy(id = 9, name = "OTHER")
        stub(bindings = listOf(CrowdStrikeAssetIdentity(asset = outside, crowdStrikeAid = "aid-one", sourceHostname = "OTHER")))
        val error = assertThrows<CrowdStrikeRecoveryBlocked> { service.recover(batch, conflict, "admin") }
        assertThat(error.reason).isEqualTo("CONFLICT_OUTSIDE_HOSTNAME")
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `oversized hostname cleanup refuses without partial deletion`() {
        stub()
        every { assets.findCrowdStrikeReplacementCandidates("ARCGIS", any()) } returns Page.of(listOf(asset), Pageable.from(0, 102), 102)
        val error = assertThrows<CrowdStrikeRecoveryBlocked> { service.recover(batch, conflict, "admin") }
        assertThat(error.reason).isEqualTo("TOO_MANY_HOSTNAME_MATCHES")
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any(), any()) }
    }
}
