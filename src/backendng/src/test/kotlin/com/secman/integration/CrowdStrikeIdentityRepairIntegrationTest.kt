package com.secman.integration

import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.*
import com.secman.service.*
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@MicronautTest(environments = ["test"], transactional = false)
class CrowdStrikeIdentityRepairIntegrationTest {
    @Inject lateinit var writer: CrowdStrikeIdentityRepairWriter
    @Inject lateinit var importer: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var identities: CrowdStrikeAssetIdentityRepository
    @Inject lateinit var em: EntityManager
    @Inject lateinit var tx: io.micronaut.transaction.TransactionOperations<org.hibernate.Session>
    @Inject lateinit var discovery: CrowdStrikeIdentityCandidateService

    private data class Fixture(val request: CrowdStrikeIdentityReconcileRequest,
        val batches: List<CrowdStrikeVulnerabilityBatchDto>, val state: LegacyCrowdStrikeAssetState)

    private fun fixture(): Fixture = tx.executeWrite {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = assets.save(Asset(name = "Human name $suffix", crowdStrikeHostname = "same-$suffix",
            nameOverriddenAt = LocalDateTime.now(), nameOverriddenBy = "test-admin", owner = "Human owner", type = "SERVER",
            cloudAccountId = "123456789012", cloudInstanceId = "i-${suffix.take(17)}"))
        val group = em.merge(Workgroup(name = "Repair-$suffix"))
        source.workgroups.add(group)
        em.persist(Vulnerability(asset = source, vulnerabilityId = "MANUAL-KEEP", source = "MANUAL", cvssSeverity = "HIGH", scanTimestamp = LocalDateTime.now()))
        em.persist(Vulnerability(asset = source, vulnerabilityId = "CVE-2026-9999", source = "CROWDSTRIKE", cvssSeverity = "HIGH", scanTimestamp = LocalDateTime.now()))
        em.persist(InstalledProduct(asset = source, name = "Legacy mixed software", crowdStrikeAid = "aid-a-$suffix"))
        val records = listOf("a", "b").mapIndexed { index, label ->
            val aid = "aid-$label-$suffix"
            identities.save(CrowdStrikeAssetIdentity(asset = source, crowdStrikeAid = aid, sourceHostname = source.crowdStrikeHostname!!))
            CrowdStrikeDeviceRecord(aid, source.crowdStrikeHostname!!,
                if (index == 0) source.cloudInstanceId else "i-${suffix.takeLast(17)}", source.cloudAccountId,
                firstSeen = Instant.parse("2026-01-01T00:00:00Z"), lastSeen = Instant.now().minusSeconds(30), productType = "Server")
        }
        em.flush()
        val batches = records.mapIndexed { index, device ->
            CrowdStrikeVulnerabilityBatchDto(device.hostname, null, device.cloudAccountId, device.instanceId, null, null, null,
                vulnerabilities = listOf(VulnerabilityDto("CVE-2026-${1000 + index}", "HIGH", "Product", 10)),
                crowdStrikeAids = setOf(device.aid), deviceSelection = CrowdStrikeDeviceSelection(device))
        }
        Fixture(CrowdStrikeIdentityReconcileRequest(source.id!!, records.map { it.aid }.toSet(), "Verified independent EC2 instances", false),
            batches, LegacyCrowdStrikeAssetState(source.cloudAccountId, source.cloudInstanceId, source.updatedAt))
    }!!

    private fun count(sql: String, id: Long): Long = tx.executeRead {
        (em.createNativeQuery(sql).setParameter("id", id).singleResult as Number).toLong()
    }!!

    @Test
    fun `preview writes nothing and split preserves original metadata grants and manual findings`() {
        val f = fixture()
        val id = f.request.assetId
        val before = assets.findById(id).orElseThrow()
        val preview = writer.preview(f.request, f.batches, f.state)
        assertThat(preview.dryRun).isTrue()
        assertThat(identities.findByAssetIdIn(listOf(id))).hasSize(2)
        assertThat(count("SELECT COUNT(*) FROM crowdstrike_identity_repair WHERE source_asset_id = :id", id)).isZero()
        val result = writer.repair(f.request, f.batches, f.state, "test-admin")
        assertThat(result.devices.map { it.assetId }.distinct()).hasSize(2)
        assertThat(result.devices.first().assetId).isEqualTo(id)
        val after = assets.findById(id).orElseThrow()
        assertThat(after.name).isEqualTo(before.name)
        assertThat(after.owner).isEqualTo("Human owner")
        assertThat(after.cloudInstanceId).isEqualTo(f.state.instance)
        assertThat(count("SELECT COUNT(*) FROM asset_workgroups WHERE asset_id = :id", id)).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM asset_workgroups WHERE asset_id = :id", result.devices.last().assetId!!)).isZero()
        assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id AND source = 'MANUAL'", id)).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id AND vulnerability_id = 'CVE-2026-9999'", id)).isZero()
        assertThat(count("SELECT COUNT(*) FROM installed_product WHERE asset_id = :id", id)).isZero()
        assertThat(count("SELECT COUNT(*) FROM crowdstrike_identity_repair WHERE source_asset_id = :id", id)).isEqualTo(2)
        f.batches.forEach { importer.importVulnerabilitiesForServer(it, "test-admin", true) }
        assertThat(identities.findByCrowdStrikeAidIn(f.request.expectedAids).map { it.asset.id }.distinct()).hasSize(2)
        result.devices.forEachIndexed { index, device ->
            val binding = identities.findByAssetIdIn(listOf(device.assetId!!)).single()
            assertThat(binding.crowdStrikeAid).isEqualTo(device.aid)
            assertThat(binding.falconFirstSeenAt).isNotNull()
            assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id AND vulnerability_id = 'CVE-2026-${1000 + index}'", device.assetId)).isEqualTo(1)
        }
        assertThatThrownBy { writer.repair(f.request, f.batches, f.state, "test-admin") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(count("SELECT COUNT(*) FROM crowdstrike_identity_repair WHERE source_asset_id = :id", id)).isEqualTo(2)
    }

    @Test
    fun `second device failure rolls back first snapshot bindings products and audit`() {
        val f = fixture()
        val invalid = f.batches.last().copy(vulnerabilities = listOf(VulnerabilityDto("CVE-2026-2000", "INVALID", "Product", 0)))
        assertThatThrownBy { writer.repair(f.request, listOf(f.batches.first(), invalid), f.state, "test-admin") }
            .isInstanceOf(CrowdStrikeIdentityConflict::class.java)
        assertThat(identities.findByAssetIdIn(listOf(f.request.assetId))).hasSize(2).allMatch { it.falconFirstSeenAt == null }
        assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id", f.request.assetId)).isEqualTo(2)
        assertThat(count("SELECT COUNT(*) FROM installed_product WHERE asset_id = :id", f.request.assetId)).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM crowdstrike_identity_repair WHERE source_asset_id = :id", f.request.assetId)).isZero()
    }

    @Test
    fun `verified empty retained device clears mixed findings without removing manual findings`() {
        val f = fixture()
        writer.repair(f.request, listOf(f.batches.first().copy(vulnerabilities = emptyList()), f.batches.last()), f.state, "test-admin")
        assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id AND source = 'CROWDSTRIKE'", f.request.assetId)).isZero()
        assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id AND source = 'MANUAL'", f.request.assetId)).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM crowdstrike_snapshot WHERE asset_id = :id AND active = 1", f.request.assetId)).isEqualTo(6)
    }

    @Test
    fun `concurrent state change and verified destination binding reject before repair`() {
        val f = fixture()
        tx.executeWrite {
            val source = assets.findById(f.request.assetId).orElseThrow()
            source.cloudAccountId = "999999999999"
            assets.update(source)
        }
        assertThatThrownBy { writer.repair(f.request, f.batches, f.state, "test-admin") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(identities.findByAssetIdIn(listOf(f.request.assetId))).hasSize(2)
        val other = fixture()
        tx.executeWrite {
            val destination = assets.save(Asset(name = "Existing destination", owner = "Human owner", type = "SERVER",
                cloudAccountId = other.state.account, cloudInstanceId = other.batches.last().cloudInstanceId))
            identities.save(CrowdStrikeAssetIdentity(asset = destination, crowdStrikeAid = "unrelated-${UUID.randomUUID()}", sourceHostname = destination.name))
        }
        assertThatThrownBy { writer.repair(other.request, other.batches, other.state, "test-admin") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Destination")
    }

    @Test
    fun `legacy payload cannot create or silently repair a multi-AID binding`() {
        val f = fixture()
        val legacy = f.batches.first().copy(deviceSelection = null, crowdStrikeAids = f.request.expectedAids)
        assertThatThrownBy { importer.importVulnerabilitiesForServer(legacy, "legacy-client", false) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("multiple Falcon")
        assertThatThrownBy { importer.importVulnerabilitiesForServer(legacy.copy(crowdStrikeAids = setOf(f.request.expectedAids.first())), "legacy-client", false) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("explicit identity reconciliation")
    }

    @Test
    fun `candidate discovery is bounded and keyset paging survives an earlier repair`() {
        val first = fixture()
        val second = fixture()
        val verified = fixture()
        val tooMany = fixture()
        tx.executeWrite {
            val binding = identities.findByAssetIdIn(listOf(verified.request.assetId)).first()
            binding.falconFirstSeenAt = LocalDateTime.now()
            identities.update(binding)
            val source = assets.findById(tooMany.request.assetId).orElseThrow()
            repeat(19) { identities.save(CrowdStrikeAssetIdentity(asset = source,
                crowdStrikeAid = "extra-${UUID.randomUUID()}", sourceHostname = source.name)) }
        }
        val admin = io.micronaut.security.authentication.Authentication.build("test-admin", listOf("ADMIN"))
        val page = discovery.candidates(first.request.assetId - 1, 1, tooMany.request.assetId, admin)
        assertThat(page.candidates.map { it.assetId }).containsExactly(first.request.assetId)
        assertThat(page.candidates.single().expectedAids).isEqualTo(first.request.expectedAids)
        assertThat(page.hasMore).isTrue()
        writer.repair(first.request, first.batches, first.state, "test-admin")
        val next = discovery.candidates(page.nextAfterId, 1, page.throughId, admin)
        assertThat(next.candidates.map { it.assetId }).containsExactly(second.request.assetId)
        assertThat(next.hasMore).isFalse()
        assertThat(next.throughId).isEqualTo(tooMany.request.assetId)
        val empty = discovery.candidates(tooMany.request.assetId, 1, tooMany.request.assetId, admin)
        assertThat(empty.candidates).isEmpty()
        assertThat(empty.hasMore).isFalse()
        assertThatThrownBy { discovery.candidates(0, 101, null, admin) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { discovery.candidates(0, 1, null,
            io.micronaut.security.authentication.Authentication.build("viewer", listOf("SECCHAMPION"))) }
            .isInstanceOf(io.micronaut.http.exceptions.HttpStatusException::class.java)
    }

    @Inject @field:io.micronaut.http.client.annotation.Client("/") lateinit var http: io.micronaut.http.client.HttpClient
    @Inject lateinit var users: UserRepository

    @Test
    fun `HTTP discovery preserves empty arrays validates pages and reports already resolved repairs`() {
        val f = fixture()
        writer.repair(f.request, f.batches, f.state, "test-admin")
        val name = "repair-admin-${UUID.randomUUID()}"
        users.save(com.secman.testutil.TestDataFactory.createAdminUser(username = name, email = "$name@example.test"))
        val token = com.secman.testutil.TestAuthHelper.getAuthToken(http, name)
        val path = "/api/crowdstrike/identity-reconcile/candidates?afterId=${f.request.assetId - 1}&limit=1&throughId=${f.request.assetId}"
        val page = http.toBlocking().retrieve(io.micronaut.http.HttpRequest.GET<Any>(path).bearerAuth(token), Map::class.java)
        assertThat(page["candidates"]).isEqualTo(emptyList<Any>())
        assertThat(page["hasMore"]).isEqualTo(false)
        assertThatThrownBy {
            http.toBlocking().exchange(io.micronaut.http.HttpRequest.GET<Any>("/api/crowdstrike/identity-reconcile/candidates?limit=101")
                .bearerAuth(token), String::class.java)
        }.isInstanceOfSatisfying(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            assertThat(it.status.code).isEqualTo(400)
        }
        assertThatThrownBy {
            http.toBlocking().exchange(io.micronaut.http.HttpRequest.POST("/api/crowdstrike/identity-reconcile", f.request)
                .bearerAuth(token), String::class.java)
        }.isInstanceOfSatisfying(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            assertThat(it.status.code).isEqualTo(409)
            assertThat(it.response.getBody(Map::class.java).orElseThrow()["errorCode"]).isEqualTo("ALREADY_RESOLVED")
        }
    }

    @Test
    fun `HTTP repair endpoint denies non ADMIN callers`() {
        val name = "repair-viewer-${UUID.randomUUID()}"
        users.save(com.secman.testutil.TestDataFactory.createVulnUser(username = name, email = "$name@example.test"))
        val token = com.secman.testutil.TestAuthHelper.getAuthToken(http, name)
        val request = CrowdStrikeIdentityReconcileRequest(1, setOf("aid-a", "aid-b"), "Verified independent instances")
        assertThatThrownBy {
            http.toBlocking().exchange(io.micronaut.http.HttpRequest.POST("/api/crowdstrike/identity-reconcile", request).bearerAuth(token), String::class.java)
        }.isInstanceOfSatisfying(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            assertThat(it.status.code).isEqualTo(403)
        }
        assertThatThrownBy {
            http.toBlocking().exchange(io.micronaut.http.HttpRequest.GET<Any>("/api/crowdstrike/identity-reconcile/candidates").bearerAuth(token), String::class.java)
        }.isInstanceOfSatisfying(io.micronaut.http.client.exceptions.HttpClientResponseException::class.java) {
            assertThat(it.status.code).isEqualTo(403)
        }
    }
}
