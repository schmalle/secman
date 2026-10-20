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

    @AfterEach
    fun removeOwnedFixtures() {
        jpaTransactions.executeWrite {
            ownedRiskIds.forEach(risks::deleteById)
            val bindings = identities.findByCrowdStrikeAidIn(ownedAids)
            bindings.mapNotNull { it.asset.id }.distinct().forEach { deletion.deleteAsset(it, "test-admin", true) }
        }
        ownedAids.clear()
        ownedRiskIds.clear()
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
        assertThat(response.headers.get("X-Secman-Deleteonfail")).isEqualTo(if (enabled) "atomic-v1" else "disabled")
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
    fun `missing authoritative account blocks deletion with a reason`() {
        val (device, id) = fixture()
        val result = post(payload(device.copy(cloudAccountId = null)))
        val outcome = result.hostOutcomes.single()
        assertThat(outcome.errorCode).isEqualTo("INCOMPLETE_IDENTITY")
        assertThat(outcome.recoveryStatus).isEqualTo("BLOCKED")
        assertThat(outcome.recoveryReason).isEqualTo("INCOMPLETE_IDENTITY")
        assertThat(outcome.recoveryAttempts).isZero()
        assertThat(assets.findById(id)).isPresent()
        assertThat(audits.countByAssetId(id)).isZero()
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
    fun `proven AID owner recovers without deleting an independent same hostname device`() {
        val (device, id) = fixture()
        val sibling = device.copy(aid = UUID.randomUUID().toString().replace("-", ""),
            instanceId = "i-" + UUID.randomUUID().toString().replace("-", "").take(17))
        ownedAids.add(sibling.aid)
        importer.importVulnerabilitiesForServer(payload(sibling), "test-admin", true)
        val siblingId = identities.findByCrowdStrikeAidIn(listOf(sibling.aid)).single().asset.id!!
        val result = post(payload(device))
        assertThat(result.errors).isEmpty()
        assertThat(assets.findById(id)).isEmpty()
        assertThat(assets.findById(siblingId)).isPresent()
        assertThat(count("vulnerability", siblingId)).isEqualTo(1)
        assertThat(audits.countByAssetId(siblingId)).isZero()
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
