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

    // --- Product classification ---

    /**
     * Carry the class from the source installed_product row onto ASSET_PRODUCT findings.
     * Cheaper and less error-prone than a full EOL rescan when only the rules changed; OS and
     * repository findings are left at their scan-time value (always INSTALLED).
     */
    @Query(
        value = """
        UPDATE eol_finding f JOIN installed_product p ON f.installed_product_id = p.id
        SET f.product_class = p.product_class
        WHERE f.subject_type = 'ASSET_PRODUCT'
        """,
        nativeQuery = true
    )
    fun syncProductClassFromInstalledProducts(): Long

    @Query(
        value = "SELECT COUNT(*) FROM eol_finding WHERE product_class = 'INSTALLER_ARTIFACT'",
        nativeQuery = true
    )
    fun countInstallerArtifacts(): Long

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
          AND (:includeInstallerFindings = TRUE OR f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT)
        ORDER BY f.eolDate ASC, f.assetName ASC, f.componentName ASC
        """
    )
    fun findForAssets(
        assetIds: Collection<Long>,
        statuses: Collection<EolStatus>,
        search: String?,
        cloudAccountId: String?,
        unassignedAccountToken: String,
        pageable: Pageable,
        includeInstallerFindings: Boolean
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
          AND (:includeInstallerFindings = TRUE OR f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT)
        """
    )
    fun countForAssets(
        assetIds: Collection<Long>,
        statuses: Collection<EolStatus>,
        search: String?,
        cloudAccountId: String?,
        unassignedAccountToken: String,
        includeInstallerFindings: Boolean
    ): Long

    @Query(
        """
        SELECT f.status, COUNT(f) FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        GROUP BY f.status
        """
    )
    fun countByStatusForAssets(assetIds: Collection<Long>): List<Array<Any>>

    @Query(
        """
        SELECT COUNT(DISTINCT f.assetId) FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        """
    )
    fun countDistinctAssets(assetIds: Collection<Long>): Long

    /** Per-account rollup for the "which of my accounts is worst" panel. */
    @Query(
        """
        SELECT COALESCE(f.cloudAccountId, ''), f.status, COUNT(f)
        FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
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
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        GROUP BY f.componentName, f.eolProductKey, f.eolCycle, f.status
        ORDER BY COUNT(DISTINCT f.assetId) DESC, f.componentName ASC
        """
    )
    fun topComponentsForAssets(assetIds: Collection<Long>, pageable: Pageable): List<Array<Any>>

    /**
     * Products ranked by how many distinct assets they are end-of-life on.
     *
     * Deliberately coarser than [topComponentsForAssets], which groups by
     * `componentName + eolCycle + status` and therefore lists one product once per
     * release cycle — "Universal Forwarder" appears three times for 9.0, 9.2 and 8.1.
     * The question this answers is "which product is most often EOL", so the grouping
     * key is the product alone and an asset carrying two EOL cycles of the same
     * product counts once, not twice.
     *
     * Repository components are excluded via `assetId IS NOT NULL`: they belong to a
     * GitHub repository, not an asset, and are ranked by [topRepositoriesByEolComponents].
     *
     * The HAVING clause keeps a product that is only ever *approaching* EOL out of a
     * table titled "most often EOL"; its approaching count is still reported for the
     * products that do qualify.
     *
     * Row shape: `[componentName, eolAssets, approachingAssets, eolCycles]`.
     */
    @Query(
        """
        SELECT f.componentName,
               COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN f.assetId END),
               COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.APPROACHING_EOL THEN f.assetId END),
               COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN f.eolCycle END)
        FROM EolFinding f
        WHERE f.assetId IS NOT NULL
          AND f.assetId IN (:assetIds)
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        GROUP BY f.componentName
        HAVING COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN f.assetId END) > 0
        ORDER BY COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN f.assetId END) DESC,
                 f.componentName ASC
        """
    )
    fun topEolProductsForAssets(assetIds: Collection<Long>, pageable: Pageable): List<Array<Any>>

    /**
     * Unscoped counterpart of [topEolProductsForAssets], for the ADMIN case where
     * `getAccessibleAssetIdsWithDomain` returns null to mean "no restriction".
     *
     * This is the one shape the header note warns about, so it carries the same
     * contract as [topRepositoriesByEolComponents]: **the caller must have already
     * established universal access.** `VulnerabilityStatisticsService` reaches it only
     * on a null id set, which `getAccessibleAssetIds` returns for ADMIN alone — an
     * *empty* set means "this user can see nothing" and must return no rows, never
     * fall through to here.
     */
    @Query(
        """
        SELECT f.componentName,
               COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN f.assetId END),
               COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.APPROACHING_EOL THEN f.assetId END),
               COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN f.eolCycle END)
        FROM EolFinding f
        WHERE f.assetId IS NOT NULL
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        GROUP BY f.componentName
        HAVING COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN f.assetId END) > 0
        ORDER BY COUNT(DISTINCT CASE WHEN f.status = com.secman.domain.EolStatus.EOL THEN f.assetId END) DESC,
                 f.componentName ASC
        """
    )
    fun topEolProductsForAll(pageable: Pageable): List<Array<Any>>

    @Query(
        """
        SELECT f FROM EolFinding f
        WHERE f.assetId = :assetId
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        ORDER BY f.eolDate ASC, f.componentName ASC
        """
    )
    fun findByAssetId(assetId: Long, pageable: Pageable): List<EolFinding>

    /**
     * Findings for one exact product name across the caller's accessible assets —
     * the drilldown behind the "Top 10 Most Often EOL Products" table, which groups
     * by this same `componentName` field (see [topEolProductsForAssets]).
     */
    @Query(
        """
        SELECT f FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
          AND f.assetId IS NOT NULL
          AND f.componentName = :product
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        ORDER BY f.status ASC, f.eolDate ASC, f.assetName ASC
        """
    )
    fun findByComponentNameForAssets(product: String, assetIds: Collection<Long>, pageable: Pageable): List<EolFinding>

    @Query(
        """
        SELECT COUNT(f) FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
          AND f.assetId IS NOT NULL
          AND f.componentName = :product
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        """
    )
    fun countByComponentNameForAssets(product: String, assetIds: Collection<Long>): Long

    /**
     * Distinct asset ids affected by one product, for the "contact affected
     * owners" recipient resolution. Deliberately unbounded within `assetIds` —
     * that collection is already the caller's accessible-asset universe, so this
     * is not a second unbounded read.
     */
    @Query(
        """
        SELECT DISTINCT f.assetId FROM EolFinding f
        WHERE f.assetId IN (:assetIds)
          AND f.assetId IS NOT NULL
          AND f.componentName = :product
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
        """
    )
    fun findAssetIdsByComponentNameForAssets(product: String, assetIds: Collection<Long>): List<Long>

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
          AND f.productClass <> com.secman.domain.ProductClass.INSTALLER_ARTIFACT
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
