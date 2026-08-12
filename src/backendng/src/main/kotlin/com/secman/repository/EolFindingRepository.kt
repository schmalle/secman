package com.secman.repository

import com.secman.domain.EolFinding
import com.secman.domain.EolStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable

/**
 * Reads over [EolFinding].
 *
 * Every asset-scoped method takes an explicit `assetIds` collection — the caller
 * resolves it through `AssetFilterService` first. There is deliberately no
 * "find all findings" read used by a user-facing path: an unscoped variant would
 * be one careless call site away from leaking another tenant's inventory
 * (CLAUDE.md §A01). All values are bound parameters; nothing is concatenated.
 */
@Repository
interface EolFindingRepository : JpaRepository<EolFinding, Long> {

    // ---------------------------------------------------------------- asset scope

    @Query(
        """
        SELECT f FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
          AND f.status IN (:statuses)
          AND (:search IS NULL OR :search = ''
               OR LOWER(f.componentName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(COALESCE(f.componentVendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(COALESCE(f.assetName, '')) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:cloudAccountId IS NULL OR :cloudAccountId = ''
               OR COALESCE(NULLIF(f.cloudAccountId, ''), :unassignedAccountToken) = :cloudAccountId)
        ORDER BY f.eolDate ASC, f.assetName ASC, f.componentName ASC
        """
    )
    fun findForAssets(
        assetIds: Collection<Long>,
        statuses: Collection<EolStatus>,
        search: String?,
        cloudAccountId: String?,
        unassignedAccountToken: String,
        pageable: Pageable
    ): List<EolFinding>

    @Query(
        """
        SELECT COUNT(f) FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
          AND f.status IN (:statuses)
          AND (:search IS NULL OR :search = ''
               OR LOWER(f.componentName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(COALESCE(f.componentVendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(COALESCE(f.assetName, '')) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:cloudAccountId IS NULL OR :cloudAccountId = ''
               OR COALESCE(NULLIF(f.cloudAccountId, ''), :unassignedAccountToken) = :cloudAccountId)
        """
    )
    fun countForAssets(
        assetIds: Collection<Long>,
        statuses: Collection<EolStatus>,
        search: String?,
        cloudAccountId: String?,
        unassignedAccountToken: String
    ): Long

    @Query(
        """
        SELECT f.status, COUNT(f) FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
        GROUP BY f.status
        """
    )
    fun countByStatusForAssets(assetIds: Collection<Long>): List<Array<Any>>

    @Query(
        """
        SELECT COUNT(DISTINCT f.assetId) FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
        """
    )
    fun countDistinctAssets(assetIds: Collection<Long>): Long

    /** Per-account rollup for the "which of my accounts is worst" panel. */
    @Query(
        """
        SELECT COALESCE(f.cloudAccountId, ''), f.status, COUNT(f)
        FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
        GROUP BY COALESCE(f.cloudAccountId, ''), f.status
        """
    )
    fun countByAccountAndStatusForAssets(assetIds: Collection<Long>): List<Array<Any>>

    /** Top EOL components across the caller's accessible assets. */
    @Query(
        """
        SELECT f.componentName, f.eolProductKey, f.eolCycle, f.status, COUNT(DISTINCT f.assetId)
        FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
        GROUP BY f.componentName, f.eolProductKey, f.eolCycle, f.status
        ORDER BY COUNT(DISTINCT f.assetId) DESC, f.componentName ASC
        """
    )
    fun topComponentsForAssets(assetIds: Collection<Long>, pageable: Pageable): List<Array<Any>>

    @Query(
        """
        SELECT f FROM EolFinding f
        WHERE f.assetId = :assetId
        ORDER BY f.eolDate ASC, f.componentName ASC
        """
    )
    fun findByAssetId(assetId: Long, pageable: Pageable): List<EolFinding>

    // ---------------------------------------------------------- repository scope

    /**
     * Repositories ranked by number of distinct EOL/approaching-EOL components.
     * ADMIN / SECCHAMPION only — enforced at the controller, mirroring
     * `GithubRepositoryController`.
     *
     * Row shape: `[githubRepositoryId, repositoryFullName, distinctComponents,
     * eolComponents, approachingComponents]`.
     */
    @Query(
        """
        SELECT f.githubRepositoryId,
               f.repositoryFullName,
               COUNT(DISTINCT CONCAT(f.componentName, '@', f.eolCycle)),
               SUM(CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN 1 ELSE 0 END),
               SUM(CASE WHEN f.status = com.secman.domain.EolStatus.APPROACHING_EOL THEN 1 ELSE 0 END)
        FROM EolFinding f
        WHERE f.subjectType = com.secman.domain.EolSubjectType.REPOSITORY_COMPONENT
          AND f.githubRepositoryId IS NOT NULL
        GROUP BY f.githubRepositoryId, f.repositoryFullName
        ORDER BY COUNT(DISTINCT CONCAT(f.componentName, '@', f.eolCycle)) DESC, f.repositoryFullName ASC
        """
    )
    fun topRepositoriesByEolComponents(pageable: Pageable): List<Array<Any>>

    @Query(
        """
        SELECT f FROM EolFinding f
        WHERE f.githubRepositoryId = :githubRepositoryId
        ORDER BY f.eolDate ASC, f.componentName ASC
        """
    )
    fun findByGithubRepositoryId(githubRepositoryId: Long, pageable: Pageable): List<EolFinding>

    // -------------------------------------------------------------- notification

    /**
     * Findings whose support ends inside the notification horizon, for owner
     * emails. Bounded by `pageable`; the caller pages until exhausted rather
     * than materializing the whole table (CLAUDE.md §A04).
     */
    @Query(
        """
        SELECT f FROM EolFinding f
        WHERE f.subjectType <> com.secman.domain.EolSubjectType.REPOSITORY_COMPONENT
          AND f.eolDate IS NOT NULL
          AND f.eolDate >= :from
          AND f.eolDate <= :to
        ORDER BY f.id ASC
        """
    )
    fun findAssetFindingsWithEolBetween(
        from: java.time.LocalDate,
        to: java.time.LocalDate,
        pageable: Pageable
    ): List<EolFinding>

    // ------------------------------------------------------------------ scan I/O

    @Query("DELETE FROM EolFinding f WHERE f.scanRunId <> :scanRunId")
    fun deleteByScanRunIdNot(scanRunId: String): Int

    @Query("SELECT COUNT(f) FROM EolFinding f")
    fun countAll(): Long
}
