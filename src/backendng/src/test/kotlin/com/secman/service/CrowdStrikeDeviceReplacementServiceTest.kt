package com.secman.service

import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class CrowdStrikeDeviceReplacementServiceTest {
    private val assets = mockk<AssetRepository>()
    private val identities = mockk<CrowdStrikeAssetIdentityRepository>(relaxed = true)
    private val deletion = mockk<AssetCascadeDeleteService>(relaxed = true)
    private val em = mockk<EntityManager>()
    private val products = mockk<com.secman.repository.InstalledProductRepository>(relaxed = true)
    private val service = CrowdStrikeDeviceReplacementService(assets, identities, products, deletion, em)
    private val older = CrowdStrikeDeviceRecord("aid-old", "server.example.test", firstSeen = Instant.parse("2026-01-01T00:00:00Z"))
    private val newest = older.copy(aid = "aid-new", firstSeen = Instant.parse("2026-02-01T00:00:00Z"))
    private val selection = CrowdStrikeDeviceSelection(newest, listOf(older))
    private val batch = CrowdStrikeVulnerabilityBatchDto(newest.hostname, null, null, null, null, null, null,
        vulnerabilities = emptyList(), crowdStrikeAids = setOf(newest.aid), deviceSelection = selection)

    private fun stub(vararg bindings: CrowdStrikeAssetIdentity) {
        val candidates = bindings.map { it.asset }.distinctBy { it.id }
        every { assets.findCrowdStrikeReplacementCandidates(any(), any()) } returns
            Page.of(candidates, Pageable.from(0, 102), candidates.size.toLong())
        every { identities.findByCrowdStrikeAidIn(any()) } returns bindings.toList()
        every { identities.findByAssetIdIn(any()) } returns bindings.toList()
        every { identities.save(any<CrowdStrikeAssetIdentity>()) } answers { firstArg() }
        candidates.forEach { asset -> every { em.find(Asset::class.java, asset.id, LockModeType.PESSIMISTIC_WRITE) } returns asset }
    }

    @Test
    fun `shared asset keeps user metadata and replaces the old snapshot`() {
        val asset = Asset(id = 1L, name = "User display name", crowdStrikeHostname = newest.hostname,
            type = "SERVER", owner = "User owner")
        val oldBinding = CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = older.aid, sourceHostname = older.hostname)
        val newBinding = oldBinding.copy(crowdStrikeAid = newest.aid)
        stub(oldBinding, newBinding)
        val plan = service.prepare(batch, selection)
        assertThat(plan.asset).isSameAs(asset)
        assertThat(plan.snapshotChanged).isTrue()
        assertThat(plan.duplicates).isEmpty()
        service.finish(asset, plan, selection, "admin")
        verify { identities.delete(oldBinding) }
        verify { products.deleteByAssetId(1L) }
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any()) }
        assertThat(asset.name).isEqualTo("User display name")
        assertThat(asset.owner).isEqualTo("User owner")
        assertThat(newBinding.falconFirstSeenAt).isEqualTo(LocalDateTime.ofInstant(newest.firstSeen, ZoneOffset.UTC))
    }

    @Test
    fun `known AID updates cloud metadata after an instance move and domain join`() {
        val asset = Asset(id = 1L, name = newest.hostname, type = "SERVER", owner = "CrowdStrike Import",
            cloudInstanceId = "i-old", cloudAccountId = "account-a", adDomain = "old.test")
        val binding = CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = newest.aid, sourceHostname = newest.hostname)
        stub(binding)
        every { assets.findByCloudInstanceIdIgnoreCase(any()) } returns null
        val incoming = newest.copy(instanceId = "i-new", cloudAccountId = "account-a", adDomain = "new.test")
        val evidence = CrowdStrikeDeviceSelection(incoming)
        val incomingBatch = batch.copy(cloudInstanceId = incoming.instanceId, cloudAccountId = incoming.cloudAccountId,
            adDomain = incoming.adDomain, deviceSelection = evidence)
        assertThat(service.prepare(incomingBatch, evidence).asset).isSameAs(asset)
        assertThatThrownBy { service.prepare(incomingBatch.copy(cloudAccountId = "account-b"),
            CrowdStrikeDeviceSelection(incoming.copy(cloudAccountId = "account-b"))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("account or domain")
    }

    @Test
    fun `older enrollment and unknown additional AIDs cannot replace current device`() {
        val asset = Asset(id = 1L, name = newest.hostname, type = "SERVER", owner = "CrowdStrike Import")
        val binding = CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = newest.aid, sourceHostname = newest.hostname,
            falconFirstSeenAt = LocalDateTime.ofInstant(newest.firstSeen, ZoneOffset.UTC).plusDays(1))
        stub(binding)
        assertThatThrownBy { service.prepare(batch, selection) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("older Falcon enrollment")
        stub(binding.copy(crowdStrikeAid = "unverified"))
        assertThatThrownBy { service.prepare(batch, selection) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unverified")
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any()) }
    }

    @Test
    fun `tampered selection and mismatched import metadata are rejected`() {
        assertThatThrownBy { service.prepare(batch, CrowdStrikeDeviceSelection(older, listOf(newest))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.prepare(batch.copy(adDomain = "different.test"), selection) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { assets.findCrowdStrikeReplacementCandidates(any(), any()) }
    }
}
