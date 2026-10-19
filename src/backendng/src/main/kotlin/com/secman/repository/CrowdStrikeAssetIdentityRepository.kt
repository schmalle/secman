package com.secman.repository

import com.secman.domain.CrowdStrikeAssetIdentity
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
/** Resolves CrowdStrike agent IDs to their persistent SecMan assets. */
interface CrowdStrikeAssetIdentityRepository : JpaRepository<CrowdStrikeAssetIdentity, Long> {
    @io.micronaut.data.annotation.Query("SELECT i FROM CrowdStrikeAssetIdentity i JOIN FETCH i.asset WHERE i.crowdStrikeAid IN (:crowdStrikeAids)")
    fun findByCrowdStrikeAidIn(crowdStrikeAids: Collection<String>): List<CrowdStrikeAssetIdentity>
    fun findByAssetIdIn(assetIds: Collection<Long>): List<CrowdStrikeAssetIdentity>
}
