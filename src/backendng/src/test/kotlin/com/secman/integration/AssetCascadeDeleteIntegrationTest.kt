package com.secman.integration

import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeSnapshot
import com.secman.domain.ExceptionRequestStatus
import com.secman.domain.Vulnerability
import com.secman.domain.VulnerabilityException
import com.secman.domain.VulnerabilityExceptionRequest
import com.secman.repository.AssetDeletionAuditLogRepository
import com.secman.service.AssetCascadeDeleteService
import com.secman.testutil.BaseIntegrationTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AssetCascadeDeleteIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var em: EntityManager
    @Inject lateinit var deletion: AssetCascadeDeleteService
    @Inject lateinit var audits: AssetDeletionAuditLogRepository

    @Test
    fun `preflight counts stored findings and requests hidden by visibility filters`() {
        val (assetId, _) = fixture()

        val summary = deletion.getCascadeSummary(assetId)

        assertThat(summary.vulnerabilitiesCount).isEqualTo(3)
        assertThat(summary.exceptionRequestsCount).isEqualTo(1)
    }

    @Test
    fun `cascade removes hidden findings and their requests without touching another asset`() {
        val (assetId, otherId) = fixture()

        val result = deletion.deleteAsset(assetId, "test-admin", forceTimeout = true)
        em.flush()
        em.clear()

        assertThat(result.deletedVulnerabilities).isEqualTo(3)
        assertThat(result.deletedRequests).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM asset WHERE id = :id", assetId)).isZero()
        assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id", assetId)).isZero()
        assertThat(count("SELECT COUNT(*) FROM vulnerability_exception_request WHERE asset_id = :id", assetId)).isZero()
        assertThat(count("SELECT COUNT(*) FROM asset WHERE id = :id", otherId)).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id", otherId)).isEqualTo(1)
        val audit = audits.findById(result.auditLogId).orElseThrow()
        assertThat(audit.vulnerabilitiesCount).isEqualTo(3)
        assertThat(audit.exceptionRequestsCount).isEqualTo(1)
    }

    private fun fixture(): Pair<Long, Long> {
        val asset = Asset(name = "cascade-hidden-${System.nanoTime()}", type = "SERVER", owner = "test")
        val other = Asset(name = "cascade-retained-${System.nanoTime()}", type = "SERVER", owner = "test")
        em.persist(asset)
        em.persist(other)
        val assetId = requireNotNull(asset.id)
        val retired = CrowdStrikeSnapshot(assetId = assetId, crowdStrikeAid = "retired-aid",
            severity = "HIGH", observedAt = LocalDateTime.now(), active = false)
        em.persist(retired)
        val low = Vulnerability(asset = asset, vulnerabilityId = "LOW-HIDDEN", cvssSeverity = "LOW",
            source = "CROWDSTRIKE", scanTimestamp = LocalDateTime.now())
        em.persist(low)
        em.persist(Vulnerability(asset = asset, vulnerabilityId = "RETIRED-HIDDEN", cvssSeverity = "HIGH",
            source = "CROWDSTRIKE", crowdStrikeSnapshotId = retired.id, scanTimestamp = LocalDateTime.now()))
        em.persist(Vulnerability(asset = asset, vulnerabilityId = "VISIBLE", cvssSeverity = "HIGH",
            source = "MANUAL", scanTimestamp = LocalDateTime.now()))
        em.persist(Vulnerability(asset = other, vulnerabilityId = "RETAINED", cvssSeverity = "HIGH",
            source = "MANUAL", scanTimestamp = LocalDateTime.now()))
        em.persist(VulnerabilityExceptionRequest(vulnerability = low, requestedByUsername = "test-admin",
            subject = VulnerabilityException.Subject.CVE, scope = VulnerabilityException.Scope.ASSET,
            reason = "Regression fixture for cascade deletion of hidden vulnerability findings.",
            expirationDate = LocalDateTime.now().plusDays(1), status = ExceptionRequestStatus.PENDING,
            assetId = assetId))
        em.flush()
        em.clear()
        assertThat(count("SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id", assetId)).isEqualTo(3)
        assertThat(em.createQuery("SELECT COUNT(v) FROM Vulnerability v WHERE v.asset.id = :id", Long::class.java)
            .setParameter("id", assetId).singleResult).isEqualTo(1)
        return assetId to requireNotNull(other.id)
    }

    private fun count(sql: String, id: Long): Long =
        (em.createNativeQuery(sql).setParameter("id", id).singleResult as Number).toLong()
}
