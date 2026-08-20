package com.secman.repository

import com.secman.domain.Asset
import com.secman.domain.InstalledProduct
import com.secman.domain.ProductClass
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable

@Repository
interface InstalledProductRepository : JpaRepository<InstalledProduct, Long> {
    fun findByExternalId(externalId: String): InstalledProduct?

    @Query("""
        SELECT p FROM InstalledProduct p
        WHERE p.externalId = :externalId
          AND p.asset.id = :assetId
    """)
    fun findByExternalIdAndAssetId(externalId: String, assetId: Long): InstalledProduct?

    /**
     * Delete all installed products for an asset that were NOT written by the current import run.
     * Used by the clean-state (replace) import: removes stale rows (previous runs, or never stamped)
     * while leaving rows already inserted by this run intact, so multi-batch runs stay idempotent.
     * Returns the number of rows deleted.
     */
    @Query("""
        DELETE FROM InstalledProduct p
        WHERE p.asset.id = :assetId
          AND (p.importRunId IS NULL OR p.importRunId <> :runId)
    """)
    fun deleteByAssetIdAndImportRunIdNot(assetId: Long, runId: String): Int

    /**
     * Delete all installed products for an asset. Fallback for single-shot imports that do not
     * supply an import run id. Returns the number of rows deleted.
     */
    @Query("DELETE FROM InstalledProduct p WHERE p.asset.id = :assetId")
    fun deleteByAssetId(assetId: Long): Int

    @Query("""
        SELECT p FROM InstalledProduct p
        WHERE p.asset.id = :assetId
          AND LOWER(p.name) = LOWER(:name)
          AND COALESCE(LOWER(p.vendor), '') = COALESCE(LOWER(:vendor), '')
          AND COALESCE(LOWER(p.version), '') = COALESCE(LOWER(:version), '')
    """)
    fun findLogicalDuplicate(assetId: Long, name: String, vendor: String?, version: String?): InstalledProduct?

    /**
     * One page of the EOL scan's full-table sweep, resumed by key.
     *
     * ## Why there is no join here
     *
     * This replaced `SELECT p FROM InstalledProduct p JOIN FETCH p.asset ORDER BY
     * p.id ASC` with offset paging. `asset` is small (~2.3k rows) and
     * `installed_product` is large (~500k), so MariaDB planned that query by
     * driving from `asset` with a full table scan and resolving products through
     * `idx_installed_product_asset`. That produces rows in *asset* order, so
     * `ORDER BY p.id` could not be served by an index and every page sorted the
     * entire ~500k-row join through a temporary table (`Using temporary; Using
     * filesort`) only to discard all but 500 rows.
     *
     * Measured on the live estate: ~4.8s per page against 0.03s for this query,
     * i.e. ~1.5 hours for one sweep, growing quadratically with the table. It was
     * reported as "eol-sync hangs". Narrowing the projection does not help and
     * neither does keyset paging on its own — the join is what fixes the join
     * order, so the join has to go. Callers resolve the asset separately with a
     * bounded `AssetRepository.findByIdIn` over the page's ids.
     *
     * `p.asset.id` reads the `asset_id` foreign key directly and does **not**
     * emit a join. Projecting instead of returning entities also keeps the lazy
     * `asset` proxy out of the result: [com.secman.service.EolScanService] is not
     * transactional, so the session is already closed by the time it reads a row.
     *
     * Paging by `p.id > :afterId` rather than by offset keeps the sweep stable
     * while the CrowdStrike import writes to the same table, and stays flat as
     * the table grows. Pass `afterId = 0` for the first page.
     */
    @Query("""
        SELECT new com.secman.repository.InstalledProductScanRow(
            p.id, p.asset.id, p.name, p.vendor, p.version, p.productClass
        )
        FROM InstalledProduct p
        WHERE p.id > :afterId
        ORDER BY p.id ASC
    """)
    fun findScanRowsAfter(afterId: Long, pageable: Pageable): List<InstalledProductScanRow>

    // --- Product classification ---

    /**
     * Id-ordered sweep WITHOUT the asset join, for the reclassify pass. Classification reads
     * only name / vendor / installationPath, so fetching the asset would be pure overhead on a
     * 182k-row table.
     */
    @Query("SELECT p FROM InstalledProduct p WHERE p.id > :afterId ORDER BY p.id ASC")
    fun findForClassification(afterId: Long, pageable: Pageable): List<InstalledProduct>

    @Query(
        value = "UPDATE installed_product SET product_class = :productClass WHERE id IN (:ids)",
        nativeQuery = true
    )
    fun updateProductClass(productClass: String, ids: Collection<Long>): Long

    @Query(
        value = "SELECT COUNT(*) FROM installed_product WHERE product_class = :productClass",
        nativeQuery = true
    )
    fun countByProductClass(productClass: String): Long

    @Query("""
        SELECT p FROM InstalledProduct p
        JOIN FETCH p.asset
        WHERE (:search IS NULL OR :search = ''
            OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.vendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.version, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY p.name ASC, p.vendor ASC, p.version ASC, p.asset.name ASC
    """)
    fun searchWithAsset(search: String?, pageable: Pageable): List<InstalledProduct>

    /**
     * One ordered page of the table with **no search term and no join** — what
     * the Installed products page asks for before anything is typed.
     *
     * [searchWithAsset] cannot serve that case cheaply. Its `WHERE` reads
     * `p.asset.name`, which HQL turns into a join, and MariaDB then plans the
     * whole query by driving from the small `asset` table, so `ORDER BY p.name…`
     * cannot use `idx_installed_product_name` and every page sorts the entire
     * ~500k-row join through a temporary table: ~4.8s per page against ~0.45s
     * here, whatever the page size. Same join-order trap as
     * [findScanRowsAfter] — see that method for the full measurement.
     *
     * The asset is not fetch-joined: callers run inside
     * `@Transactional(readOnly = true)`, so the lazy `asset` proxies resolve
     * through the class-level `@BatchSize` on [Asset] — one IN-list select per
     * page instead of a join or N+1. Same reasoning as
     * `VulnerabilityRepository.findLatestVulnerabilitiesPerAssetWithFilters`.
     *
     * `p.id` is the final sort key so the order is *total*. Without it, rows
     * sharing name+vendor+version have no defined order between them and
     * paging can show the same row twice while skipping another.
     */
    @Query("""
        SELECT p FROM InstalledProduct p
        ORDER BY p.name ASC, p.vendor ASC, p.version ASC, p.id ASC
    """)
    fun findOrderedPage(pageable: Pageable): List<InstalledProduct>

    /** Non-admin counterpart of [findOrderedPage], scoped to accessible assets. */
    @Query("""
        SELECT p FROM InstalledProduct p
        WHERE p.asset.id IN (:assetIds)
        ORDER BY p.name ASC, p.vendor ASC, p.version ASC, p.id ASC
    """)
    fun findOrderedPageForAssets(assetIds: Set<Long>, pageable: Pageable): List<InstalledProduct>

    /**
     * How many products match, for the pager. Distinct from
     * [countDistinctAssets], which counts *systems* — the page shows both.
     */
    @Query("""
        SELECT COUNT(p) FROM InstalledProduct p
        WHERE (:search IS NULL OR :search = ''
            OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.vendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.version, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    fun countProducts(search: String?): Long

    /** Non-admin counterpart of [countProducts]. */
    @Query("""
        SELECT COUNT(p) FROM InstalledProduct p
        WHERE p.asset.id IN (:assetIds)
          AND (:search IS NULL OR :search = ''
            OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.vendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.version, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    fun countProductsForAssets(search: String?, assetIds: Set<Long>): Long

    /** [countProducts] for the "search by server" box. */
    @Query("""
        SELECT COUNT(p) FROM InstalledProduct p
        WHERE (:server IS NULL OR :server = ''
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :server, '%'))
            OR LOWER(COALESCE(p.asset.ip, '')) LIKE LOWER(CONCAT('%', :server, '%')))
    """)
    fun countProductsByServer(server: String?): Long

    /** Non-admin counterpart of [countProductsByServer]. */
    @Query("""
        SELECT COUNT(p) FROM InstalledProduct p
        WHERE p.asset.id IN (:assetIds)
          AND (:server IS NULL OR :server = ''
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :server, '%'))
            OR LOWER(COALESCE(p.asset.ip, '')) LIKE LOWER(CONCAT('%', :server, '%')))
    """)
    fun countProductsByServerForAssets(server: String?, assetIds: Set<Long>): Long

    @Query("""
        SELECT p FROM InstalledProduct p
        JOIN FETCH p.asset
        WHERE (:server IS NULL OR :server = ''
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :server, '%'))
            OR LOWER(COALESCE(p.asset.ip, '')) LIKE LOWER(CONCAT('%', :server, '%')))
        ORDER BY p.asset.name ASC, p.name ASC, p.vendor ASC, p.version ASC
    """)
    fun searchByServerWithAsset(server: String?, pageable: Pageable): List<InstalledProduct>

    @Query("""
        SELECT p FROM InstalledProduct p
        JOIN FETCH p.asset
        WHERE p.asset.id IN (:assetIds)
          AND (:server IS NULL OR :server = ''
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :server, '%'))
            OR LOWER(COALESCE(p.asset.ip, '')) LIKE LOWER(CONCAT('%', :server, '%')))
        ORDER BY p.asset.name ASC, p.name ASC, p.vendor ASC, p.version ASC
    """)
    fun searchByServerForAssetsWithAsset(server: String?, assetIds: Set<Long>, pageable: Pageable): List<InstalledProduct>

    @Query("""
        SELECT COUNT(DISTINCT p.asset.id) FROM InstalledProduct p
        WHERE (:server IS NULL OR :server = ''
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :server, '%'))
            OR LOWER(COALESCE(p.asset.ip, '')) LIKE LOWER(CONCAT('%', :server, '%')))
    """)
    fun countDistinctAssetsByServer(server: String?): Long

    @Query("""
        SELECT COUNT(DISTINCT p.asset.id) FROM InstalledProduct p
        WHERE p.asset.id IN (:assetIds)
          AND (:server IS NULL OR :server = ''
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :server, '%'))
            OR LOWER(COALESCE(p.asset.ip, '')) LIKE LOWER(CONCAT('%', :server, '%')))
    """)
    fun countDistinctAssetsByServerForAssets(server: String?, assetIds: Set<Long>): Long

    @Query("""
        SELECT DISTINCT asset FROM InstalledProduct p
        JOIN p.asset asset
        WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :productName, '%'))
        ORDER BY asset.name ASC
    """)
    fun findAssetsByProductName(productName: String): List<Asset>

    @Query("""
        SELECT DISTINCT asset FROM InstalledProduct p
        JOIN p.asset asset
        WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :productName, '%'))
          AND asset.id IN (:assetIds)
        ORDER BY asset.name ASC
    """)
    fun findAssetsByProductNameForAssets(productName: String, assetIds: Set<Long>): List<Asset>

    @Query("""
        SELECT p FROM InstalledProduct p
        JOIN FETCH p.asset
        WHERE p.asset.id IN (:assetIds)
          AND (:search IS NULL OR :search = ''
            OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.vendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.version, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY p.name ASC, p.vendor ASC, p.version ASC, p.asset.name ASC
    """)
    fun searchForAssetsWithAsset(search: String?, assetIds: Set<Long>, pageable: Pageable): List<InstalledProduct>

    @Query("""
        SELECT COUNT(DISTINCT p.asset.id) FROM InstalledProduct p
        WHERE (:search IS NULL OR :search = ''
            OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.vendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.version, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    fun countDistinctAssets(search: String?): Long

    @Query("""
        SELECT COUNT(DISTINCT p.asset.id) FROM InstalledProduct p
        WHERE p.asset.id IN (:assetIds)
          AND (:search IS NULL OR :search = ''
            OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.vendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.version, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    fun countDistinctAssetsForAssets(search: String?, assetIds: Set<Long>): Long

    @Query("""
        SELECT DISTINCT p.name FROM InstalledProduct p
        ORDER BY p.name ASC
    """)
    fun findDistinctNames(pageable: Pageable): List<String>

    @Query("""
        SELECT DISTINCT p.name FROM InstalledProduct p
        WHERE p.asset.id IN (:assetIds)
        ORDER BY p.name ASC
    """)
    fun findDistinctNamesForAssets(assetIds: Set<Long>, pageable: Pageable): List<String>
}

/**
 * One installed-product row as the EOL scan reads it, from
 * [InstalledProductRepository.findScanRowsAfter].
 *
 * Carries the `asset_id` foreign key rather than the [Asset] itself so the
 * paging query needs no join — see that method for why the join was the whole
 * problem. The scan resolves the asset per page through
 * [AssetRepository.findByIdIn].
 */
data class InstalledProductScanRow(
    val productId: Long,
    val assetId: Long,
    val name: String,
    val vendor: String?,
    val version: String?,
    val productClass: ProductClass
)
