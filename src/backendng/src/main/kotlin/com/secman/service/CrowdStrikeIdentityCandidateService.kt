package com.secman.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional

@Serdeable
data class CrowdStrikeIdentityCandidate(val assetId: Long, val hostname: String, val expectedAids: Set<String>)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CrowdStrikeIdentityCandidatePage(val candidates: List<CrowdStrikeIdentityCandidate>,
    val nextAfterId: Long, val throughId: Long, val hasMore: Boolean)

/** Local discovery only. Eligibility for an actual split still requires fresh Falcon evidence. */
@Singleton
open class CrowdStrikeIdentityCandidateService(
    private val em: EntityManager,
    private val identities: CrowdStrikeAssetIdentityRepository,
    private val access: AssetFilterService
) {
    @Transactional
    open fun candidates(afterId: Long, limit: Int, throughId: Long?, auth: Authentication): CrowdStrikeIdentityCandidatePage {
        if ("ADMIN" !in auth.roles) throw HttpStatusException(HttpStatus.FORBIDDEN, "Identity discovery requires ADMIN")
        require(afterId >= 0 && limit in 1..100 && (throughId == null || throughId >= 0)) { "Invalid candidate page" }
        val upper = throughId ?: (em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM asset").singleResult as Number).toLong()
        val rows = em.createNativeQuery("""
            SELECT a.id, COALESCE(a.crowdstrike_hostname, a.name)
            FROM asset a JOIN crowdstrike_asset_identity i ON i.asset_id = a.id
            WHERE a.id > :afterId AND a.id <= :throughId
            GROUP BY a.id, a.crowdstrike_hostname, a.name
            HAVING COUNT(*) BETWEEN 2 AND 100
               AND SUM(CASE WHEN i.falcon_first_seen_at IS NOT NULL OR i.falcon_last_seen_at IS NOT NULL
                    OR i.identity_observed_at IS NOT NULL THEN 1 ELSE 0 END) = 0
            ORDER BY a.id
        """).setParameter("afterId", afterId).setParameter("throughId", upper).setMaxResults(limit + 1)
            .resultList.map { it as Array<*> }
        val page = rows.take(limit)
        val ids = page.map { (it[0] as Number).toLong() }
        val bindings = if (ids.isEmpty()) emptyMap() else identities.findByAssetIdIn(ids).groupBy { it.asset.id }
        val candidates = page.mapNotNull { row ->
            val id = (row[0] as Number).toLong()
            val bound = bindings[id].orEmpty()
            // An import may finish between the SQL aggregate and binding read. Advance
            // by the scanned IDs even if every row in this page has just resolved.
            if (!access.canAccessAsset(id, auth) || bound.size !in 2..100 || bound.any {
                it.falconFirstSeenAt != null || it.falconLastSeenAt != null || it.identityObservedAt != null
            }) null else CrowdStrikeIdentityCandidate(id, row[1].toString(), bound.map { it.crowdStrikeAid }.toSortedSet())
        }
        return CrowdStrikeIdentityCandidatePage(candidates, ids.lastOrNull() ?: afterId, upper, rows.size > limit)
    }
}
