package com.secman.repository

import com.secman.domain.RequirementSnapshot
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface RequirementSnapshotRepository : JpaRepository<RequirementSnapshot, Long> {

    fun findByReleaseId(releaseId: Long): List<RequirementSnapshot>

    fun findByOriginalRequirementId(requirementId: Long): List<RequirementSnapshot>

    fun countByReleaseId(releaseId: Long): Long

    fun deleteByReleaseId(releaseId: Long)
}
