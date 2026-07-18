package com.secman.repository

import com.secman.domain.AssetTag
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface AssetTagRepository : JpaRepository<AssetTag, Long> {

    fun findByAssetId(assetId: Long): List<AssetTag>

    fun findByAssetIdAndKey(assetId: Long, key: String): List<AssetTag>
}
