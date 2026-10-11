package com.secman.integration

import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage
import com.secman.domain.Vulnerability
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.VulnerabilityDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.CrowdStrikeSnapshotRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.CrowdStrikeIdentityConflict
import com.secman.service.CrowdStrikeVulnerabilityImportService
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@MicronautTest(environments = ["test"], transactional = false)
class CrowdStrikeInstanceTransitionIntegrationTest {
    @Inject lateinit var importer: CrowdStrikeVulnerabilityImportService
    @Inject lateinit var identities: CrowdStrikeAssetIdentityRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var findings: VulnerabilityRepository
    @Inject lateinit var snapshots: CrowdStrikeSnapshotRepository
    @Inject lateinit var workgroups: com.secman.repository.WorkgroupRepository
    @Inject lateinit var em: EntityManager
    @Inject lateinit var transactions: io.micronaut.transaction.TransactionOperations<java.sql.Connection>
    @Inject lateinit var jpaTransactions: io.micronaut.transaction.TransactionOperations<org.hibernate.Session>
    @Inject lateinit var deletion: com.secman.service.AssetCascadeDeleteService
    private val deviceAids = mutableListOf<String>()
    private val groupIds = mutableListOf<Long>()

    @AfterEach
    fun removeOwnedFixtures() {
        jpaTransactions.executeWrite {
            val bindings = identities.findByCrowdStrikeAidIn(deviceAids)
            val ids = bindings.mapNotNull { it.asset.id }.distinct()
            // Hibernate's test schema does not reproduce the migration's ON DELETE CASCADE.
            // Delete owned findings before removing bindings used by the visibility filter.
            ids.forEach { id ->
                em.createNativeQuery("DELETE FROM vulnerability WHERE asset_id = :id")
                    .setParameter("id", id).executeUpdate()
            }
            identities.deleteAll(bindings)
            em.flush()
            ids.forEach { deletion.deleteAsset(it, "test-admin") }
        }
        groupIds.forEach(workgroups::deleteById)
    }

    private fun device() = CrowdStrikeDeviceRecord(UUID.randomUUID().toString().replace("-", ""),
        "transition-${UUID.randomUUID()}.example.test", instanceId = "i-" + UUID.randomUUID().toString().replace("-", "").take(17),
        cloudAccountId = "123456789012", adDomain = "example.test", firstSeen = Instant.parse("2026-01-01T00:00:00Z"),
        lastSeen = Instant.now().minusSeconds(30), productType = "Server").also { deviceAids.add(it.aid) }

    private fun batch(device: CrowdStrikeDeviceRecord, observed: Instant = Instant.now(), scope: List<String>? = null,
        rows: List<VulnerabilityDto> = listOf(VulnerabilityDto("CVE-2026-1234", "HIGH", "Product", 10))) =
        CrowdStrikeVulnerabilityBatchDto(device.hostname, null, device.cloudAccountId, device.instanceId, device.adDomain,
            null, null, vulnerabilities = rows, crowdStrikeAids = setOf(device.aid), runSeverities = scope,
            deviceSelection = CrowdStrikeDeviceSelection(device, observedAt = observed))

    private fun assetId(device: CrowdStrikeDeviceRecord) = requireNotNull(identities.findByCrowdStrikeAidIn(listOf(device.aid)).single().asset.id)
    private fun scalar(query: String, vararg parameters: Any?): Any = transactions.executeRead { status ->
        status.connection.prepareStatement(query).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result -> check(result.next()); result.getObject(1) }
        }
    }
    private fun transitionCount(id: Long) = (scalar("SELECT COUNT(*) FROM crowdstrike_instance_transition WHERE asset_id = ?", id) as Number).toInt()

    @Test
    fun `instance correction is idempotent preserves manual metadata and records supported CVE coverage`() {
        val original = device()
        val observed = Instant.now().minusSeconds(5)
        importer.importVulnerabilitiesForServer(batch(original, observed), "test-admin", true)
        val id = assetId(original)
        val group = workgroups.save(com.secman.domain.Workgroup(name = "Preserved-${UUID.randomUUID()}"))
        groupIds.add(requireNotNull(group.id))
        val asset = jpaTransactions.executeWrite {
            val stored = assets.findById(id).orElseThrow()
            stored.workgroups.add(group)
            assets.update(stored)
        }
        asset.name = "Human display name"
        asset.nameOverriddenAt = LocalDateTime.now()
        asset.nameOverriddenBy = "test-admin"
        asset.owner = "Human owner"
        assets.update(asset)
        findings.save(Vulnerability(asset = asset, vulnerabilityId = "MANUAL-KEEP", cvssSeverity = "HIGH", source = "MANUAL",
            scanTimestamp = LocalDateTime.now()))
        val firstSeen = scalar("SELECT first_seen_at FROM vulnerability WHERE asset_id = ? AND source = 'CROWDSTRIKE'", id)
        val incoming = original.copy(instanceId = "i-" + UUID.randomUUID().toString().replace("-", "").take(17))
        val payload = batch(incoming, observed.plusSeconds(1)).copy(findingCoverage = CrowdStrikeFindingCoverage(3, 1, missingCve = 2))
        val result = importer.importVulnerabilitiesForServer(payload, "test-admin", true)
        assertThat(result.outcome!!.formerInstanceId).isEqualTo(original.instanceId)
        assertThat(result.vulnerabilitiesSkipped).isEqualTo(2)
        assertThat(assetId(incoming)).isEqualTo(id)
        val stored = assets.findById(id).orElseThrow()
        assertThat(stored.cloudInstanceId).isEqualTo(incoming.instanceId)
        assertThat(stored.name).isEqualTo("Human display name")
        assertThat(stored.owner).isEqualTo("Human owner")
        assertThat((scalar("SELECT COUNT(*) FROM asset_workgroups WHERE asset_id = ? AND workgroup_id = ?", id, group.id) as Number).toInt()).isEqualTo(1)
        assertThat(transitionCount(id)).isEqualTo(1)
        assertThat((scalar("SELECT COUNT(*) FROM vulnerability WHERE asset_id = ? AND source = 'MANUAL'", id) as Number).toInt()).isEqualTo(1)
        assertThat(scalar("SELECT first_seen_at FROM vulnerability WHERE asset_id = ? AND source = 'CROWDSTRIKE'", id)).isEqualTo(firstSeen)
        val high = snapshots.findByAssetIdAndActive(id, true).single { it.severity == "HIGH" }
        assertThat(high.coverageKind).isEqualTo("SUPPORTED_CVE")
        assertThat(high.fetchedCount).isEqualTo(3)
        assertThat(high.missingCveCount).isEqualTo(2)
        importer.importVulnerabilitiesForServer(payload.copy(deviceSelection = CrowdStrikeDeviceSelection(incoming)), "test-admin", true)
        assertThat(transitionCount(id)).isEqualTo(1)
        assertThatThrownBy { importer.importVulnerabilitiesForServer(batch(original, observed), "test-admin", true) }
            .isInstanceOf(CrowdStrikeIdentityConflict::class.java)
        assertThat(assets.findById(id).orElseThrow().cloudInstanceId).isEqualTo(incoming.instanceId)
    }

    @Test
    fun `failure after metadata update rolls back instance identity audit and findings`() {
        val original = device()
        importer.importVulnerabilitiesForServer(batch(original), "test-admin", true)
        val id = assetId(original)
        val incoming = original.copy(instanceId = "i-" + UUID.randomUUID().toString().replace("-", "").take(17))
        // The scope mismatch fails after updateAsset and snapshot creation, before commit.
        assertThatThrownBy { importer.importVulnerabilitiesForServer(batch(incoming, scope = listOf("LOW")), "test-admin", true) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("outside the declared")
        assertThat(assets.findById(id).orElseThrow().cloudInstanceId).isEqualTo(original.instanceId)
        assertThat(transitionCount(id)).isZero()
        assertThat(snapshots.findByAssetIdAndActive(id, true)).hasSize(6)
        assertThat((scalar("SELECT COUNT(*) FROM vulnerability WHERE asset_id = ?", id) as Number).toInt()).isEqualTo(1)
        assertThatThrownBy {
            jpaTransactions.executeWrite<Unit> {
                importer.importVulnerabilitiesForServer(batch(incoming), "test-admin", true)
                em.flush()
                assertThat((em.createNativeQuery("SELECT COUNT(*) FROM crowdstrike_instance_transition WHERE asset_id = :id")
                    .setParameter("id", id).singleResult as Number).toInt()).isEqualTo(1)
                throw IllegalStateException("Simulated failure after audit and findings")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(assets.findById(id).orElseThrow().cloudInstanceId).isEqualTo(original.instanceId)
        assertThat(transitionCount(id)).isZero()
    }

    @Test
    fun `concurrent identities cannot claim the same incoming instance`() {
        val first = device(); val second = device()
        importer.importVulnerabilitiesForServer(batch(first), "test-admin", true)
        importer.importVulnerabilitiesForServer(batch(second), "test-admin", true)
        val shared = "i-" + UUID.randomUUID().toString().replace("-", "").take(17)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { device -> pool.submit<Boolean> {
                try {
                    importer.importVulnerabilitiesForServer(batch(device.copy(instanceId = shared)), "test-admin", true)
                    true
                } catch (e: CrowdStrikeIdentityConflict) {
                    assertThat(e.code).isEqualTo("INSTANCE_ALREADY_BOUND")
                    false
                }
            } }.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(results.count { it }).isEqualTo(1)
            assertThat((scalar("SELECT COUNT(*) FROM asset WHERE cloud_instance_id = ? AND cloud_account_id = ?", shared, first.cloudAccountId) as Number).toInt()).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `incomplete supported coverage rejects before changing instance or findings`() {
        val original = device()
        importer.importVulnerabilitiesForServer(batch(original), "test-admin", true)
        val id = assetId(original)
        val incoming = original.copy(instanceId = "i-" + UUID.randomUUID().toString().replace("-", "").take(17))
        val payload = batch(incoming).copy(findingCoverage = CrowdStrikeFindingCoverage(2, 1, invalid = 1, complete = false))
        assertThatThrownBy { importer.importVulnerabilitiesForServer(payload, "test-admin", true) }
            .isInstanceOf(CrowdStrikeIdentityConflict::class.java)
        assertThat(assets.findById(id).orElseThrow().cloudInstanceId).isEqualTo(original.instanceId)
        assertThat(transitionCount(id)).isZero()
    }

    @Test
    fun `backend supported snapshot counts include its own duplicate collapse`() {
        val device = device()
        val row = VulnerabilityDto("CVE-2026-1234", "HIGH", "Product", 10)
        val result = importer.importVulnerabilitiesForServer(batch(device, rows = listOf(row, row)), "test-admin", true)
        assertThat(result.vulnerabilitiesImported).isEqualTo(1)
        assertThat(result.outcome!!.coverage).isEqualTo(CrowdStrikeFindingCoverage(2, 1, duplicates = 1))
        assertThat(snapshots.findByAssetIdAndActive(assetId(device), true).first().duplicateCount).isEqualTo(1)
    }
}
