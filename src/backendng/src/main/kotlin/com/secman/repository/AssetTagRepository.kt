package com.secman.repository

import com.secman.domain.AssetTag
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface AssetTagRepository : JpaRepository<AssetTag, Long> {

    fun findByAssetId(assetId: Long): List<AssetTag>

    fun findByKey(key: String): List<AssetTag>

    fun findByKeyAndValue(key: String, value: String): List<AssetTag>

    @io.micronaut.data.annotation.Query("""
        SELECT DISTINCT t.key FROM AssetTag t ORDER BY t.key
    """)
    fun findDistinctKeys(): List<String>

    @io.micronaut.data.annotation.Query("""
        SELECT DISTINCT t.value FROM AssetTag t WHERE t.key = :key ORDER BY t.value
    """)
    fun findDistinctValuesByKey(key: String): List<String>
}
