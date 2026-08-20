package com.secman.service

import com.secman.dto.InstalledProductListResponse
import com.secman.dto.InstalledProductResponse
import com.secman.repository.InstalledProductRepository
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class InstalledProductListService(
    private val installedProductRepository: InstalledProductRepository,
    private val accessibleAssetIdsCache: AccessibleAssetIdsCache
) {
    @Transactional(readOnly = true)
    open fun list(
        authentication: Authentication,
        search: String?,
        limit: Int?,
        page: Int? = null,
        pageSize: Int? = null
    ): InstalledProductListResponse {
        val normalizedSearch = search?.trim().orEmpty()
        return listMatchingProducts(authentication, normalizedSearch, limit, page = page, pageSize = pageSize)
    }

    @Transactional(readOnly = true)
    open fun listForServer(
        authentication: Authentication,
        server: String?,
        limit: Int?,
        page: Int? = null,
        pageSize: Int? = null
    ): InstalledProductListResponse {
        val normalizedServer = server?.trim().orEmpty()
        require(normalizedServer.isNotEmpty()) { "Server search term is required" }
        return listMatchingProducts(
            authentication, normalizedServer, limit,
            matchServerOnly = true, page = page, pageSize = pageSize
        )
    }

    @Transactional(readOnly = true)
    open fun listDistinctNames(authentication: Authentication): List<String> {
        val isAdmin = authentication.roles.contains("ADMIN")
        val accessibleAssetIds = if (isAdmin) null else accessibleAssetIdsCache.get(authentication)

        if (accessibleAssetIds != null && accessibleAssetIds.isEmpty()) {
            return emptyList()
        }

        val pageable = Pageable.from(0, MAX_DISTINCT_NAMES)
        return if (accessibleAssetIds == null) {
            installedProductRepository.findDistinctNames(pageable)
        } else {
            installedProductRepository.findDistinctNamesForAssets(accessibleAssetIds, pageable)
        }
    }

    private fun listMatchingProducts(
        authentication: Authentication,
        normalizedSearch: String,
        limit: Int?,
        matchServerOnly: Boolean = false,
        page: Int? = null,
        pageSize: Int? = null
    ): InstalledProductListResponse {
        // `limit` predates paging and is kept as an alias for `pageSize` so
        // existing callers keep working. The default is a page, not the old
        // 50,000: unfiltered, that pulled 12.9 MB and locked the browser up
        // rendering 50,000 rows in one pass (§A04 "unbounded is a design bug").
        val effectivePageSize = (pageSize ?: limit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val effectivePage = (page ?: 0).coerceAtLeast(0)
        val isAdmin = authentication.roles.contains("ADMIN")
        val accessibleAssetIds = if (isAdmin) null else accessibleAssetIdsCache.get(authentication)

        if (accessibleAssetIds != null && accessibleAssetIds.isEmpty()) {
            return InstalledProductListResponse(
                products = emptyList(),
                totalProducts = 0,
                totalSystems = 0,
                page = effectivePage,
                pageSize = effectivePageSize
            )
        }

        val pageable = Pageable.from(effectivePage, effectivePageSize)
        val products = if (matchServerOnly) {
            if (accessibleAssetIds == null) {
                installedProductRepository.searchByServerWithAsset(normalizedSearch, pageable)
            } else {
                installedProductRepository.searchByServerForAssetsWithAsset(normalizedSearch, accessibleAssetIds, pageable)
            }
        } else if (normalizedSearch.isEmpty()) {
            // No search term: take the join-free query. The search variants read
            // `p.asset.name`, whose implicit join makes MariaDB sort the entire
            // ~500k-row join per page (~4.8s) even when the term is blank.
            if (accessibleAssetIds == null) {
                installedProductRepository.findOrderedPage(pageable)
            } else {
                installedProductRepository.findOrderedPageForAssets(accessibleAssetIds, pageable)
            }
        } else {
            if (accessibleAssetIds == null) {
                installedProductRepository.searchWithAsset(normalizedSearch, pageable)
            } else {
                installedProductRepository.searchForAssetsWithAsset(normalizedSearch, accessibleAssetIds, pageable)
            }
        }
        val totalProducts = if (matchServerOnly) {
            if (accessibleAssetIds == null) {
                installedProductRepository.countProductsByServer(normalizedSearch)
            } else {
                installedProductRepository.countProductsByServerForAssets(normalizedSearch, accessibleAssetIds)
            }
        } else {
            if (accessibleAssetIds == null) {
                installedProductRepository.countProducts(normalizedSearch)
            } else {
                installedProductRepository.countProductsForAssets(normalizedSearch, accessibleAssetIds)
            }
        }
        val totalSystems = if (matchServerOnly) {
            if (accessibleAssetIds == null) {
                installedProductRepository.countDistinctAssetsByServer(normalizedSearch)
            } else {
                installedProductRepository.countDistinctAssetsByServerForAssets(normalizedSearch, accessibleAssetIds)
            }
        } else {
            if (accessibleAssetIds == null) {
                installedProductRepository.countDistinctAssets(normalizedSearch)
            } else {
                installedProductRepository.countDistinctAssetsForAssets(normalizedSearch, accessibleAssetIds)
            }
        }

        return InstalledProductListResponse(
            products = products.map { product ->
                InstalledProductResponse(
                    id = requireNotNull(product.id),
                    assetId = requireNotNull(product.asset.id),
                    hostname = product.asset.name,
                    cloudAccountId = product.asset.cloudAccountId,
                    name = product.name,
                    vendor = product.vendor,
                    version = product.version,
                    category = product.category,
                    installationPath = null,
                    installedAt = product.installedAt,
                    lastUsedAt = product.lastUsedAt,
                    lastUpdatedAt = product.lastUpdatedAt,
                    importedAt = product.importedAt
                )
            },
            totalProducts = totalProducts,
            totalSystems = totalSystems,
            page = effectivePage,
            pageSize = effectivePageSize
        )
    }

    companion object {
        const val MAX_DISTINCT_NAMES = 5_000

        /** Rows per page when the caller does not ask for a size. */
        const val DEFAULT_PAGE_SIZE = 100

        /**
         * Ceiling on an explicitly requested page. Well under the old 50,000
         * default, which was a cap chosen never to be hit rather than a page.
         */
        const val MAX_PAGE_SIZE = 1_000
    }
}
