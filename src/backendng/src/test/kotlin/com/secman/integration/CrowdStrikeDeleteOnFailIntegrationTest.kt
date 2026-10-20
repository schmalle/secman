package com.secman.integration

import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage
import com.secman.domain.Risk
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.ImportStatisticsDto
import com.secman.dto.VulnerabilityDto
import com.secman.repository.AssetDeletionAuditLogRepository
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.RiskRepository
import com.secman.repository.UserRepository
import com.secman.service.CrowdStrikeVulnerabilityImportService
import com.secman.testutil.TestAuthHelper
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/** Real HTTP, AOP transactions and MariaDB; never reuse application rows as fixtures. */
@MicronautTest(environments = ["test"], transactional = false)
class CrowdStrikeDeleteOnFailIntegrationTest {
    @Inject @field:Client("/") lateinit var http: HttpClient
    @Inject lateinit var importer: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var identities: CrowdStrikeAssetIdentityRepository
    @Inject lateinit var users: UserRepository
    @Inject lateinit var audits: AssetDeletionAuditLogRepository
    @Inject lateinit var risks: RiskRepository
    @Inject lateinit var transactions: io.micronaut.transaction.TransactionOperations<java.sql.Connection>

    @Inject lateinit var jpaTransactions: io.micronaut.transaction.TransactionOperations<org.hibernate.Session>
    @Inject lateinit var deletion: com.secman.service.AssetCascadeDeleteService
    private val ownedAids = mutableListOf<String>()
    private val ownedRiskIds = mutableListOf<Long>()
    private val ownedAssetIds = mutableListOf<Long>()

    @AfterEach
    fun removeOwnedFixtures() {
        jpaTransactions.executeWrite {
            ownedRiskIds.forEach(risks::deleteById)
            val bindings = identities.findByCrowdStrikeAidIn(ownedAids)
            (bindings.mapNotNull { it.asset.id } + ownedAssetIds).distinct().filter { assets.findById(it).isPresent }
                .forEach { deletion.deleteAsset(it, "test-admin", true) }
        }
        ownedAids.clear()
        ownedRiskIds.clear()
        ownedAssetIds.clear()
    }

    private fun device() = CrowdStrikeDeviceRecord(UUID.randomUUID().toString().replace("-", ""),
        "recovery-${UUID.randomUUID()}.example.test", instanceId = "i-" + UUID.randomUUID().toString().replace("-", "").take(17),
        cloudAccountId = "034362056090", firstSeen = Instant.parse("2025-01-22T07:55:52Z"),
        lastSeen = Instant.now().minusSeconds(30), productType = "Server").also { ownedAids.add(it.aid) }

    private fun payload(device: CrowdStrikeDeviceRecord, observed: Instant = Instant.now()) = CrowdStrikeVulnerabilityBatchDto(
        device.hostname, null, device.cloudAccountId, device.instanceId, device.adDomain, "Windows Server 2019", null,
        vulnerabilities = listOf(VulnerabilityDto("CVE-2026-1234", "HIGH", "Product", 10)),
        crowdStrikeAids = setOf(device.aid), deviceSelection = CrowdStrikeDeviceSelection(device, observedAt = observed),
        findingCoverage = CrowdStrikeFindingCoverage(1, 1))

    private fun fixture(): Pair<CrowdStrikeDeviceRecord, Long> {
        val device = device()
        // The selected AID owns an asset with contradictory account metadata, exactly the production branch.
        importer.importVulnerabilitiesForServer(payload(device.copy(cloudAccountId = "012345678901"), Instant.now().minusSeconds(5)), "test-admin", true)
        return device to requireNotNull(identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset.id)
    }

    private fun token(admin: Boolean = true): String {
        val name = "recovery-${UUID.randomUUID()}"
        users.save(if (admin) TestDataFactory.createAdminUser(username = name, email = "$name@example.test")
            else TestDataFactory.createVulnUser(username = name, email = "$name@example.test"))
        return TestAuthHelper.getAuthToken(http, name)
    }

    private fun post(batch: CrowdStrikeVulnerabilityBatchDto, enabled: Boolean = true): ImportStatisticsDto {
        val response = http.toBlocking().exchange(HttpRequest.POST(
            "/api/crowdstrike/servers/import?deleteOnFail=$enabled", listOf(batch)).bearerAuth(token()), ImportStatisticsDto::class.java)
        assertThat(response.headers.get("X-Secman-Deleteonfail")).isEqualTo(if (enabled) "hostname-v2" else "disabled")
        return response.body()!!
    }

    private fun count(table: String, id: Long): Long {
        val sql = when (table) {
            "vulnerability" -> "SELECT COUNT(*) FROM vulnerability WHERE asset_id = ?"
            "crowdstrike_snapshot" -> "SELECT COUNT(*) FROM crowdstrike_snapshot WHERE asset_id = ?"
            else -> error("Unsupported fixture count")
        }
        return transactions.executeRead { state ->
            state.connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
            }
        }
    }

    @Test
    fun `HTTP same instance conflict recovers exact asset and saves 24 supported CVEs`() {
        val (device, id) = fixture()
        val batch = payload(device).copy(
            vulnerabilities = (1..24).map { VulnerabilityDto("CVE-2026-${1000 + it}", "HIGH", "Product", 10) },
            findingCoverage = CrowdStrikeFindingCoverage(35, 24, missingCve = 1, duplicates = 10))
        val result = post(batch)
        assertThat(result.errors).isEmpty()
        assertThat(result.serversProcessed).isEqualTo(1)
        assertThat(result.serversCreated).isEqualTo(1)
        assertThat(result.vulnerabilitiesImported).isEqualTo(24)
        val outcome = result.hostOutcomes.single()
        assertThat(outcome.recoveryAttempts).isEqualTo(1)
        assertThat(outcome.recoveryStatus).isEqualTo("COMMITTED")
        assertThat(outcome.recoveryAssetId).isEqualTo(id)
        assertThat(outcome.recoveryDeletedAssetIds).containsExactly(id)
        assertThat(assets.findById(id)).isEmpty()
        val replacement = identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset
        assertThat(replacement.id).isNotEqualTo(id)
        assertThat(replacement.cloudAccountId).isEqualTo("034362056090")
        assertThat(count("vulnerability", requireNotNull(replacement.id))).isEqualTo(24)
        assertThat(audits.countByAssetId(id)).isEqualTo(1)
    }

    @Test
    fun `without flag same instance conflict retains original asset`() {
        val (device, id) = fixture()
        val result = post(payload(device), false)
        val outcome = result.hostOutcomes.single()
        assertThat(outcome.errorCode).isEqualTo("CLOUD_ACCOUNT_CONFLICT")
        assertThat(outcome.assetId).isEqualTo(id)
        assertThat(outcome.recoveryAttempts).isZero()
        assertThat(assets.findById(id)).isPresent()
        assertThat(audits.countByAssetId(id)).isZero()
    }

    @Test
    fun `explicit hostname cleanup can reset an asset when incoming account metadata is missing`() {
        val (device, id) = fixture()
        val result = post(payload(device.copy(cloudAccountId = null)))
        val outcome = result.hostOutcomes.single()
        assertThat(result.errors).isEmpty()
        assertThat(outcome.recoveryStatus).isEqualTo("COMMITTED")
        assertThat(outcome.recoveryDeletedAssetIds).containsExactly(id)
        assertThat(assets.findById(id)).isEmpty()
        assertThat(identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset.cloudAccountId).isNull()
        assertThat(audits.countByAssetId(id)).isEqualTo(1)
    }

    @Test
    fun `two failed replacement saves roll back asset findings bindings snapshots and deletion audits`() {
        val (device, id) = fixture()
        val beforeSnapshots = count("crowdstrike_snapshot", id)
        // Rejection happens after replacement asset/snapshots were written, inside the recovery transaction.
        val result = post(payload(device).copy(runSeverities = listOf("LOW")))
        assertThat(result.errors).hasSize(1)
        val outcome = result.hostOutcomes.single()
        assertThat(outcome.recoveryAttempts).isEqualTo(2)
        assertThat(outcome.recoveryStatus).isEqualTo("ROLLED_BACK")
        assertThat(outcome.recoveryReason).isEqualTo("RETRY_EXHAUSTED")
        assertThat(assets.findById(id).orElseThrow().cloudAccountId).isEqualTo("012345678901")
        assertThat(identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset.id).isEqualTo(id)
        assertThat(count("vulnerability", id)).isEqualTo(1)
        assertThat(count("crowdstrike_snapshot", id)).isEqualTo(beforeSnapshots)
        assertThat(audits.countByAssetId(id)).isZero()
    }

    @Test
    fun `hostname cleanup deletes every exact match and leaves similar hostnames untouched`() {
        val (device, id) = fixture()
        val sibling = device.copy(aid = UUID.randomUUID().toString().replace("-", ""),
            instanceId = "i-" + UUID.randomUUID().toString().replace("-", "").take(17))
        ownedAids.add(sibling.aid)
        importer.importVulnerabilitiesForServer(payload(sibling), "test-admin", true)
        val siblingId = identities.findByCrowdStrikeAidIn(listOf(sibling.aid)).single().asset.id!!
        val outside = device().copy(hostname = device.hostname + ".example.test")
        importer.importVulnerabilitiesForServer(payload(outside), "test-admin", true)
        val outsideId = identities.findByCrowdStrikeAidIn(listOf(outside.aid)).single().asset.id!!
        val result = post(payload(device))
        assertThat(result.errors).isEmpty()
        assertThat(result.hostOutcomes.single().recoveryDeletedAssetIds).containsExactlyInAnyOrder(id, siblingId)
        assertThat(assets.findById(id)).isEmpty()
        assertThat(assets.findById(siblingId)).isEmpty()
        assertThat(audits.countByAssetId(id)).isEqualTo(1)
        assertThat(audits.countByAssetId(siblingId)).isEqualTo(1)
        assertThat(assets.findById(outsideId)).isPresent()
        assertThat(count("vulnerability", outsideId)).isEqualTo(1)
        assertThat(audits.countByAssetId(outsideId)).isZero()
    }

    @Test
    fun `production multiple AID binding no longer vetoes hostname deletion`() {
        val (device, id) = fixture()
        val otherAid = UUID.randomUUID().toString().replace("-", "")
        ownedAids.add(otherAid)
        identities.save(com.secman.domain.CrowdStrikeAssetIdentity(asset = assets.findById(id).orElseThrow(),
            crowdStrikeAid = otherAid, sourceHostname = device.hostname))
        val result = post(payload(device))
        assertThat(result.errors).isEmpty()
        assertThat(result.hostOutcomes.single().recoveryDeletedAssetIds).containsExactly(id)
        assertThat(assets.findById(id)).isEmpty()
        assertThat(identities.findByCrowdStrikeAidIn(listOf(otherAid))).isEmpty()
        assertThat(audits.countByAssetId(id)).isEqualTo(1)
    }

    @Test
    fun `unexplained binding rejection without a conflict asset ID recovers by explicit hostname only`() {
        val device = device()
        importer.importVulnerabilitiesForServer(payload(device, Instant.now().minusSeconds(5)), "test-admin", true)
        val id = identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset.id!!
        val otherAid = UUID.randomUUID().toString().replace("-", "")
        ownedAids.add(otherAid)
        identities.save(com.secman.domain.CrowdStrikeAssetIdentity(asset = assets.findById(id).orElseThrow(),
            crowdStrikeAid = otherAid, sourceHostname = device.hostname))
        val ordinary = post(payload(device), false)
        assertThat(ordinary.hostOutcomes.single().errorCode).isEqualTo("UNEXPLAINED_AID")
        assertThat(assets.findById(id)).isPresent()
        assertThat(identities.findByCrowdStrikeAidIn(listOf(otherAid))).hasSize(1)
        val recovered = post(payload(device))
        assertThat(recovered.errors).isEmpty()
        assertThat(recovered.hostOutcomes.single().recoveryDeletedAssetIds).containsExactly(id)
        assertThat(assets.findById(id)).isEmpty()
        assertThat(identities.findByCrowdStrikeAidIn(listOf(otherAid))).isEmpty()
    }

    @Test
    fun `failure after multiple hostname deletions restores every asset binding finding and audit`() {
        val (device, id) = fixture()
        val sibling = device().copy(hostname = device.hostname, lastSeen = device.lastSeen!!.minusSeconds(1))
        importer.importVulnerabilitiesForServer(payload(sibling), "test-admin", true)
        val siblingId = identities.findByCrowdStrikeAidIn(listOf(sibling.aid)).single().asset.id!!
        val result = post(payload(device).copy(runSeverities = listOf("LOW")))
        assertThat(result.errors).hasSize(1)
        assertThat(result.hostOutcomes.single().recoveryAttempts).isEqualTo(2)
        assertThat(result.hostOutcomes.single().recoveryDeletedAssetIds).isEmpty()
        for (assetId in listOf(id, siblingId)) {
            assertThat(assets.findById(assetId)).isPresent()
            assertThat(count("vulnerability", assetId)).isEqualTo(1)
            assertThat(audits.countByAssetId(assetId)).isZero()
        }
        assertThat(identities.findByCrowdStrikeAidIn(listOf(device.aid, sibling.aid))).hasSize(2)
    }

    private fun legacyAsset(device: CrowdStrikeDeviceRecord, hostname: String = device.hostname): Long {
        val asset = assets.save(com.secman.domain.Asset(name = hostname, crowdStrikeHostname = hostname,
            type = "SERVER", owner = "Legacy import", cloudInstanceId = device.instanceId, cloudAccountId = device.cloudAccountId))
        return requireNotNull(asset.id).also { ownedAssetIds.add(it) }
    }

    @Test
    fun `unproven duplicate assets enter hostname recovery and preserve normal rejection without flag`() {
        val device = device()
        val ids = listOf(legacyAsset(device), legacyAsset(device))
        val ordinary = post(payload(device), false)
        assertThat(ordinary.hostOutcomes.single().errorCode).isEqualTo("DUPLICATE_ASSET_CONFLICT")
        ids.forEach { assertThat(assets.findById(it)).isPresent() }
        val recovered = post(payload(device))
        assertThat(recovered.errors).isEmpty()
        assertThat(recovered.hostOutcomes.single().recoveryAttempts).isEqualTo(1)
        assertThat(recovered.hostOutcomes.single().recoveryDeletedAssetIds).containsExactlyElementsOf(ids)
        ids.forEach { id ->
            assertThat(assets.findById(id)).isEmpty()
            assertThat(audits.countByAssetId(id)).isEqualTo(1)
        }
    }

    @Test
    fun `retry does not rediscover a different hostname by shared cloud instance and unknown AID`() {
        val device = device()
        val id = legacyAsset(device)
        val outsideId = legacyAsset(device, device.hostname + ".retained.test")
        val outsideAid = UUID.randomUUID().toString().replace("-", "")
        ownedAids.add(outsideAid)
        identities.save(com.secman.domain.CrowdStrikeAssetIdentity(asset = assets.findById(outsideId).orElseThrow(),
            crowdStrikeAid = outsideAid, sourceHostname = device.hostname + ".retained.test"))
        val ordinary = post(payload(device), false)
        assertThat(ordinary.hostOutcomes.single().errorCode).isEqualTo("UNEXPLAINED_AID")
        val recovered = post(payload(device))
        assertThat(recovered.errors).isEmpty()
        assertThat(recovered.hostOutcomes.single().recoveryAttempts).isEqualTo(1)
        assertThat(recovered.hostOutcomes.single().recoveryDeletedAssetIds).containsExactly(id)
        val replacementId = identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset.id!!
        assertThat(replacementId).isNotIn(id, outsideId)
        assertThat(assets.findById(id)).isEmpty()
        assertThat(assets.findById(outsideId).orElseThrow().crowdStrikeHostname).endsWith(".retained.test")
        assertThat(identities.findByCrowdStrikeAidIn(listOf(outsideAid)).single().asset.id).isEqualTo(outsideId)
        assertThat(audits.countByAssetId(outsideId)).isZero()
    }

    @Test
    fun `failed retry of unproven duplicates restores both legacy rows without deletion audits`() {
        val device = device()
        val ids = listOf(legacyAsset(device), legacyAsset(device))
        val result = post(payload(device).copy(runSeverities = listOf("LOW")))
        assertThat(result.hostOutcomes.single().recoveryAttempts).isEqualTo(2)
        assertThat(result.hostOutcomes.single().recoveryStatus).isEqualTo("ROLLED_BACK")
        assertThat(result.hostOutcomes.single().recoveryDeletedAssetIds).isEmpty()
        ids.forEach { id ->
            assertThat(assets.findById(id)).isPresent()
            assertThat(audits.countByAssetId(id)).isZero()
        }
    }

    @Test
    fun `cleanup cannot steal selected AID from a different hostname`() {
        val device = device()
        val id = legacyAsset(device)
        val outsideId = legacyAsset(device, device.hostname + ".retained.test")
        identities.save(com.secman.domain.CrowdStrikeAssetIdentity(asset = assets.findById(outsideId).orElseThrow(),
            crowdStrikeAid = device.aid, sourceHostname = device.hostname + ".retained.test"))
        val other = UUID.randomUUID().toString().replace("-", "")
        ownedAids.add(other)
        identities.save(com.secman.domain.CrowdStrikeAssetIdentity(asset = assets.findById(outsideId).orElseThrow(),
            crowdStrikeAid = other, sourceHostname = device.hostname + ".retained.test"))
        val result = post(payload(device))
        assertThat(result.errors).hasSize(1)
        assertThat(result.hostOutcomes.single().recoveryReason).isEqualTo("CONFLICT_OUTSIDE_HOSTNAME")
        assertThat(assets.findById(id)).isPresent()
        assertThat(assets.findById(outsideId)).isPresent()
        assertThat(identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset.id).isEqualTo(outsideId)
        assertThat(audits.countByAssetId(id)).isZero()
        assertThat(audits.countByAssetId(outsideId)).isZero()
    }

    @Test
    fun `protected risk blocks recovery before deletion`() {
        val (device, id) = fixture()
        ownedRiskIds.add(requireNotNull(risks.save(Risk(name = "Retained recovery fixture", asset = assets.findById(id).orElseThrow())).id))
        val result = post(payload(device))
        assertThat(result.hostOutcomes.single().recoveryReason).isEqualTo("PROTECTED_REFERENCES")
        assertThat(result.hostOutcomes.single().recoveryAttempts).isEqualTo(1)
        assertThat(assets.findById(id)).isPresent()
        assertThat(audits.countByAssetId(id)).isZero()
    }

    @Test
    fun `older evidence cannot trigger recovery`() {
        val (device, id) = fixture()
        val result = post(payload(device, Instant.now().minusSeconds(300)))
        assertThat(result.hostOutcomes.single().errorCode).isEqualTo("STALE_IDENTITY_OBSERVATION")
        assertThat(result.hostOutcomes.single().recoveryAttempts).isZero()
        assertThat(assets.findById(id)).isPresent()
    }

    @Test
    fun `VULN user cannot request destructive recovery through HTTP`() {
        val error = assertThrows<HttpClientResponseException> {
            http.toBlocking().exchange(HttpRequest.POST("/api/crowdstrike/servers/import?deleteOnFail=true",
                emptyList<CrowdStrikeVulnerabilityBatchDto>()).bearerAuth(token(false)), ImportStatisticsDto::class.java)
        }
        assertThat(error.status.code).isEqualTo(403)
    }
}
