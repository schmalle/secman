package com.secman.repository

import com.secman.domain.CrowdStrikeAssetIdentity
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
/** Resolves CrowdStrike agent IDs to their persistent SecMan assets. */
interface CrowdStrikeAssetIdentityRepository : JpaRepository<CrowdStrikeAssetIdentity, Long> {
    fun findByCrowdStrikeAidIn(crowdStrikeAids: Collection<String>): List<CrowdStrikeAssetIdentity>
}
