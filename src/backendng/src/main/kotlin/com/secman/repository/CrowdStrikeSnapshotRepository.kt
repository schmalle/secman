package com.secman.repository

import com.secman.domain.CrowdStrikeSnapshot
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface CrowdStrikeSnapshotRepository : JpaRepository<CrowdStrikeSnapshot, Long> {
    @io.micronaut.data.annotation.Query("""
        SELECT s FROM CrowdStrikeSnapshot s WHERE s.assetId = :assetId AND s.active = true
          AND EXISTS (SELECT i.id FROM CrowdStrikeAssetIdentity i
                      WHERE i.asset.id = s.assetId AND i.crowdStrikeAid = s.crowdStrikeAid)
    """)
    fun findCurrentByAssetId(assetId: Long): List<CrowdStrikeSnapshot>

    fun findByAssetIdAndActive(assetId: Long, active: Boolean): List<CrowdStrikeSnapshot>
}
