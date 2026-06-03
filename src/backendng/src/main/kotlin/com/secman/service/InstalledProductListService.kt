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
    open fun list(authentication: Authentication, search: String?, limit: Int?): InstalledProductListResponse {
        val effectiveLimit = (limit ?: 500).coerceIn(1, 2000)
        val normalizedSearch = search?.trim().orEmpty()
        val isAdmin = authentication.roles.contains("ADMIN")
        val accessibleAssetIds = if (isAdmin) null else accessibleAssetIdsCache.get(authentication)

        if (accessibleAssetIds != null && accessibleAssetIds.isEmpty()) {
            return InstalledProductListResponse(
                products = emptyList(),
                totalProducts = 0,
                totalSystems = 0
            )
        }

        val pageable = Pageable.from(0, effectiveLimit)
        val products = if (accessibleAssetIds == null) {
            installedProductRepository.searchWithAsset(normalizedSearch, pageable)
        } else {
            installedProductRepository.searchForAssetsWithAsset(normalizedSearch, accessibleAssetIds, pageable)
        }
        val totalSystems = if (accessibleAssetIds == null) {
            installedProductRepository.countDistinctAssets(normalizedSearch)
        } else {
            installedProductRepository.countDistinctAssetsForAssets(normalizedSearch, accessibleAssetIds)
        }

        return InstalledProductListResponse(
            products = products.map { product ->
                InstalledProductResponse(
                    id = requireNotNull(product.id),
                    assetId = requireNotNull(product.asset.id),
                    hostname = product.asset.name,
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
            totalProducts = products.size,
            totalSystems = totalSystems
        )
    }
}
