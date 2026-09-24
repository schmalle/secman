package com.secman.service

import com.secman.domain.Asset
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager

/** Executes one bounded inventory query without initializing asset collections. */
@Singleton
open class AssetOverviewQueryService(private val entityManager: EntityManager) {
    /** Optional filters accepted by the overview endpoint. */
    data class Filters(
        val name: String?,
        val ipAddress: String?,
        val owner: String?,
        val adDomain: String?,
        val accountId: String?,
        val workgroupId: Long?
    )

    /** A page of assets plus the exact matching row count. */
    data class Result(val assets: List<Asset>, val total: Long)

    /** Applies access IDs before database pagination; null IDs mean a globally authorized caller. */
    @Suppress("UNCHECKED_CAST")
    open fun search(ids: Set<Long>?, filters: Filters, page: Int, pageSize: Int): Result {
        if (ids != null && ids.isEmpty()) return Result(emptyList(), 0)

        val rows = entityManager.createNativeQuery(ROW_QUERY, Asset::class.java)
        val count = entityManager.createNativeQuery(COUNT_QUERY)
        bind(rows, ids, filters)
        bind(count, ids, filters)
        rows.firstResult = page * pageSize
        rows.maxResults = pageSize
        return Result(rows.resultList as List<Asset>, (count.singleResult as Number).toLong())
    }

    private fun bind(query: jakarta.persistence.Query, ids: Set<Long>?, filters: Filters) {
        query.setParameter("restrictIds", ids != null)
        query.setParameter("ids", ids ?: setOf(-1L))
        query.setParameter("name", filters.name)
        query.setParameter("ip", filters.ipAddress)
        query.setParameter("owner", filters.owner)
        query.setParameter("adDomain", filters.adDomain)
        query.setParameter("accountId", filters.accountId)
        query.setParameter("workgroupId", filters.workgroupId)
    }

    companion object {
        private const val ROW_QUERY = """
            SELECT a.* FROM asset a
            WHERE (:restrictIds = FALSE OR a.id IN (:ids))
              AND (:name IS NULL OR LOCATE(LOWER(:name), LOWER(a.name)) > 0)
              AND (:ip IS NULL OR LOCATE(LOWER(:ip), LOWER(a.ip)) > 0)
              AND (:owner IS NULL OR LOCATE(LOWER(:owner), LOWER(a.owner)) > 0)
              AND (:adDomain IS NULL OR LOCATE(LOWER(:adDomain), LOWER(a.ad_domain)) > 0)
              AND (:accountId IS NULL OR LOCATE(:accountId, a.cloud_account_id) > 0)
              AND (:workgroupId IS NULL OR EXISTS (
                  SELECT 1 FROM asset_workgroups aw
                  JOIN workgroup w ON w.id = aw.workgroup_id
                  WHERE aw.asset_id = a.id AND aw.workgroup_id = :workgroupId AND w.enabled = TRUE
              ))
            ORDER BY a.created_at DESC, a.id DESC
        """
        private const val COUNT_QUERY = """
            SELECT COUNT(*) FROM asset a
            WHERE (:restrictIds = FALSE OR a.id IN (:ids))
              AND (:name IS NULL OR LOCATE(LOWER(:name), LOWER(a.name)) > 0)
              AND (:ip IS NULL OR LOCATE(LOWER(:ip), LOWER(a.ip)) > 0)
              AND (:owner IS NULL OR LOCATE(LOWER(:owner), LOWER(a.owner)) > 0)
              AND (:adDomain IS NULL OR LOCATE(LOWER(:adDomain), LOWER(a.ad_domain)) > 0)
              AND (:accountId IS NULL OR LOCATE(:accountId, a.cloud_account_id) > 0)
              AND (:workgroupId IS NULL OR EXISTS (
                  SELECT 1 FROM asset_workgroups aw
                  JOIN workgroup w ON w.id = aw.workgroup_id
                  WHERE aw.asset_id = a.id AND aw.workgroup_id = :workgroupId AND w.enabled = TRUE
              ))
        """
    }
}
