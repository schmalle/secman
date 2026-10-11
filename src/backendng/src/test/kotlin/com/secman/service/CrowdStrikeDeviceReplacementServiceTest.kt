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
    private val assets = mockk<AssetRepository>(relaxed = true)
    private val identities = mockk<CrowdStrikeAssetIdentityRepository>(relaxed = true)
    private val deletion = mockk<AssetCascadeDeleteService>(relaxed = true)
    private val em = mockk<EntityManager>(relaxed = true)
    private val products = mockk<com.secman.repository.InstalledProductRepository>(relaxed = true)
    private val service = CrowdStrikeDeviceReplacementService(assets, identities, products, deletion, em)
    private val older = CrowdStrikeDeviceRecord("aid-old", "server.example.test", firstSeen = Instant.parse("2026-01-01T00:00:00Z"))
    private val newest = older.copy(aid = "aid-new", firstSeen = Instant.parse("2026-02-01T00:00:00Z"))
    private val selection = CrowdStrikeDeviceSelection(newest, listOf(older))
    private val batch = CrowdStrikeVulnerabilityBatchDto(newest.hostname, null, null, null, null, null, null,
        vulnerabilities = emptyList(), crowdStrikeAids = setOf(newest.aid), deviceSelection = selection)

    private fun stub(vararg bindings: CrowdStrikeAssetIdentity) {
        val candidates = bindings.map { it.asset }.distinctBy { it.id }
        val owners = mockk<jakarta.persistence.Query>(relaxed = true)
        every { owners.setParameter(any<String>(), any()) } returns owners
        every { owners.resultList } returns emptyList<Any>()
        every { em.createNativeQuery(match { it.contains("SELECT id FROM asset") }) } returns owners
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
    fun `known AID cannot silently move to a conflicting cloud instance`() {
        val asset = Asset(id = 1L, name = newest.hostname, type = "SERVER", owner = "CrowdStrike Import",
            cloudInstanceId = "i-old", cloudAccountId = "account-a", adDomain = "old.test")
        val binding = CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = newest.aid, sourceHostname = newest.hostname)
        stub(binding)
        every { assets.findByCloudInstanceIdIgnoreCase(any()) } returns null
        val incoming = newest.copy(instanceId = "i-0123456789abcdef1", cloudAccountId = "account-a", adDomain = "new.test", lastSeen = Instant.now())
        val evidence = CrowdStrikeDeviceSelection(incoming)
        val incomingBatch = batch.copy(cloudInstanceId = incoming.instanceId, cloudAccountId = incoming.cloudAccountId,
            adDomain = incoming.adDomain, deviceSelection = evidence)
        assertThatThrownBy { service.prepare(incomingBatch, evidence) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.prepare(incomingBatch.copy(cloudAccountId = "account-b"),
            CrowdStrikeDeviceSelection(incoming.copy(cloudAccountId = "account-b"))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("account or domain")
    }

    private fun instanceChange(): Triple<Asset, CrowdStrikeAssetIdentity, CrowdStrikeDeviceRecord> {
        val asset = Asset(id = 1L, name = "Human display name", type = "SERVER", owner = "Human owner",
            cloudInstanceId = "i-0123456789abcdef0", cloudAccountId = "123456789012", adDomain = "example.test")
        val incoming = newest.copy(instanceId = "i-0123456789abcdef1", cloudAccountId = asset.cloudAccountId,
            adDomain = asset.adDomain, lastSeen = Instant.parse("2026-03-01T00:00:00Z"))
        val binding = CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = incoming.aid, sourceHostname = incoming.hostname,
            falconFirstSeenAt = LocalDateTime.ofInstant(requireNotNull(incoming.firstSeen), ZoneOffset.UTC))
        stub(binding)
        return Triple(asset, binding, incoming)
    }

    private fun incomingBatch(device: CrowdStrikeDeviceRecord, observed: Instant = Instant.now()) = batch.copy(
        cloudInstanceId = device.instanceId, cloudAccountId = device.cloudAccountId, adDomain = device.adDomain,
        deviceSelection = CrowdStrikeDeviceSelection(device, observedAt = observed))

    @Test
    fun `same verified enrollment permits audited instance correction and keeps metadata`() {
        val (asset, binding, incoming) = instanceChange()
        val payload = incomingBatch(incoming)
        val plan = service.prepare(payload, requireNotNull(payload.deviceSelection))
        assertThat(plan.asset).isSameAs(asset)
        assertThat(plan.formerInstanceId).isEqualTo(asset.cloudInstanceId)
        assertThat(plan.snapshotChanged).isFalse()
        assertThat(plan.duplicates).isEmpty()
        service.finish(asset, plan, requireNotNull(payload.deviceSelection), "admin")
        verify { em.persist(match<com.secman.domain.CrowdStrikeInstanceTransition> {
            it.assetId == 1L && it.formerInstanceId == "i-0123456789abcdef0" && it.instanceId == incoming.instanceId
        }) }
        assertThat(binding.identityObservedAt).isEqualTo(LocalDateTime.ofInstant(payload.deviceSelection!!.observedAt, ZoneOffset.UTC))
        assertThat(asset.name).isEqualTo("Human display name")
        verify(exactly = 0) { products.deleteByAssetId(any()) }
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any()) }
    }

    @Test
    fun `instance correction rejects account domain enrollment and incomplete metadata`() {
        val (_, _, incoming) = instanceChange()
        val cases = listOf(
            incoming.copy(cloudAccountId = "other-account") to "CLOUD_ACCOUNT_CONFLICT",
            incoming.copy(cloudAccountId = null) to "CLOUD_ACCOUNT_CONFLICT",
            incoming.copy(adDomain = null) to "AD_DOMAIN_CONFLICT",
            incoming.copy(adDomain = "other.test") to "AD_DOMAIN_CONFLICT",
            incoming.copy(instanceId = null) to "INCOMPLETE_IDENTITY",
            incoming.copy(lastSeen = null) to "INCOMPLETE_IDENTITY",
            incoming.copy(firstSeen = incoming.firstSeen!!.plusSeconds(1)) to "ENROLLMENT_MISMATCH")
        cases.forEach { (device, code) ->
            val payload = incomingBatch(device)
            assertThatThrownBy { service.prepare(payload, requireNotNull(payload.deviceSelection)) }
                .isInstanceOf(CrowdStrikeIdentityConflict::class.java)
                .extracting("code").isEqualTo(code)
        }
        verify(exactly = 0) { em.persist(any()) }
    }

    @Test
    fun `instance correction refuses another asset owning the incoming instance`() {
        val (_, _, incoming) = instanceChange()
        val competing = Asset(id = 2L, name = "other", type = "SERVER", owner = "Human owner", cloudInstanceId = incoming.instanceId,
            cloudAccountId = incoming.cloudAccountId)
        val owners = mockk<jakarta.persistence.Query>()
        every { owners.setParameter(any<String>(), any()) } returns owners
        every { owners.resultList } returns listOf(2L)
        every { em.createNativeQuery(match { it.contains("SELECT id FROM asset") }) } returns owners
        every { em.find(Asset::class.java, 2L, LockModeType.PESSIMISTIC_WRITE) } returns competing
        val payload = incomingBatch(incoming)
        assertThatThrownBy { service.prepare(payload, payload.deviceSelection!!) }
            .isInstanceOf(CrowdStrikeIdentityConflict::class.java).extracting("code").isEqualTo("INSTANCE_ALREADY_BOUND")
    }

    @Test
    fun `older and tied identity observations cannot restore former instance`() {
        val (_, binding, incoming) = instanceChange()
        val accepted = Instant.now().minusSeconds(5)
        binding.identityObservedAt = LocalDateTime.ofInstant(accepted, ZoneOffset.UTC)
        listOf(accepted.minusSeconds(1), accepted).forEach { observed ->
            val payload = incomingBatch(incoming, observed)
            assertThatThrownBy { service.prepare(payload, payload.deviceSelection!!) }
                .isInstanceOf(CrowdStrikeIdentityConflict::class.java).extracting("code").isEqualTo("STALE_IDENTITY_OBSERVATION")
        }
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
    @Test
    fun `proven retired identity with inventory relationships is preserved for review`() {
        val oldAsset = Asset(id = 1L, name = older.hostname, type = "SERVER", owner = "CrowdStrike Import")
        val newAsset = Asset(id = 2L, name = newest.hostname, type = "SERVER", owner = "CrowdStrike Import")
        stub(CrowdStrikeAssetIdentity(asset = oldAsset, crowdStrikeAid = older.aid, sourceHostname = older.hostname),
            CrowdStrikeAssetIdentity(asset = newAsset, crowdStrikeAid = newest.aid, sourceHostname = newest.hostname))
        val counts = mockk<jakarta.persistence.TypedQuery<Long>>()
        every { counts.setParameter(any<String>(), any()) } returns counts
        every { counts.singleResult } returns 0L
        every { em.createQuery(any<String>(), Long::class.javaObjectType) } returns counts
        val links = mockk<jakarta.persistence.Query>()
        every { links.setParameter(any<String>(), any()) } returns links
        every { links.singleResult } returns 1L
        every { links.resultList } returns emptyList<Any>()
        every { links.executeUpdate() } returns 1
        every { em.createNativeQuery(any<String>()) } returns links
        val replacement = service.prepare(batch, selection)
        service.finish(requireNotNull(replacement.asset), replacement, selection, "test-admin")
        verify(exactly = 0) { deletion.deleteAsset(any(), any(), any(), any()) }
        verify { identities.delete(any()) }
    }

}
