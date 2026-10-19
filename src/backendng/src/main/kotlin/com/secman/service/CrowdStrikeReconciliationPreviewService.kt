package com.secman.service

import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional

@Serdeable
data class CrowdStrikeReconciliationCandidate(val assetId: Long, val hostname: String, val instanceId: String?,
    val cloudAccountId: String?, val currentBindings: Long, val unverifiedFindings: Long, val verifiedScopes: Long)

/** Local evidence only. A preview never infers retirement from a missing or filtered provider record. */
@Singleton
open class CrowdStrikeReconciliationPreviewService(private val em: EntityManager) {
    @Transactional
    open fun preview(afterId: Long, limit: Int): List<CrowdStrikeReconciliationCandidate> {
        require(afterId >= 0 && limit in 1..100) { "Invalid reconciliation page" }
        val rows = em.createNativeQuery("""
            SELECT a.id, COALESCE(a.crowdstrike_hostname, a.name), a.cloud_instance_id, a.cloud_account_id,
                   (SELECT COUNT(*) FROM crowdstrike_asset_identity i WHERE i.asset_id = a.id),
                   (SELECT COUNT(*) FROM vulnerability v WHERE v.asset_id = a.id AND v.source = :source AND v.crowdstrike_snapshot_id IS NULL),
                   (SELECT COUNT(DISTINCT s.severity) FROM crowdstrike_snapshot s
                    JOIN crowdstrike_asset_identity i ON i.asset_id = s.asset_id AND i.crowdstrike_aid = s.crowdstrike_aid
                    WHERE s.asset_id = a.id AND s.active = 1 AND s.complete = 1)
            FROM asset a WHERE a.id > :afterId AND (
                EXISTS (SELECT 1 FROM crowdstrike_asset_identity i WHERE i.asset_id = a.id) OR
                EXISTS (SELECT 1 FROM vulnerability v WHERE v.asset_id = a.id AND v.source = :source))
            ORDER BY a.id
        """).setParameter("source", com.secman.constants.VulnerabilitySources.CROWDSTRIKE)
            .setParameter("afterId", afterId).setMaxResults(limit).resultList
        return rows.map { value ->
            val row = value as Array<*>
            CrowdStrikeReconciliationCandidate((row[0] as Number).toLong(), row[1].toString(), row[2] as? String,
                row[3] as? String, (row[4] as Number).toLong(), (row[5] as Number).toLong(), (row[6] as Number).toLong())
        }
    }
}
