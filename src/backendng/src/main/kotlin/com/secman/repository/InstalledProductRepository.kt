package com.secman.repository

import com.secman.domain.Asset
import com.secman.domain.InstalledProduct
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface InstalledProductRepository : JpaRepository<InstalledProduct, Long> {
    fun findByExternalId(externalId: String): InstalledProduct?

    @Query("""
        SELECT p FROM InstalledProduct p
        WHERE p.externalId = :externalId
          AND p.asset.id = :assetId
    """)
    fun findByExternalIdAndAssetId(externalId: String, assetId: Long): InstalledProduct?

    @Query("""
        SELECT p FROM InstalledProduct p
        WHERE p.asset.id = :assetId
          AND LOWER(p.name) = LOWER(:name)
          AND COALESCE(LOWER(p.vendor), '') = COALESCE(LOWER(:vendor), '')
          AND COALESCE(LOWER(p.version), '') = COALESCE(LOWER(:version), '')
    """)
    fun findLogicalDuplicate(assetId: Long, name: String, vendor: String?, version: String?): InstalledProduct?

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
    fun search(search: String?): List<InstalledProduct>

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
        WHERE p.asset.id IN (:assetIds)
          AND (:search IS NULL OR :search = ''
            OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.vendor, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(COALESCE(p.version, '')) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(p.asset.name) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY p.name ASC, p.vendor ASC, p.version ASC, p.asset.name ASC
    """)
    fun searchForAssets(search: String?, assetIds: Set<Long>): List<InstalledProduct>

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
}
