package com.secman.repository

import com.secman.domain.RequirementSnapshot
import com.secman.domain.Release
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface RequirementSnapshotRepository : JpaRepository<RequirementSnapshot, Long> {

    fun findByRelease(release: Release): List<RequirementSnapshot>

    fun findByReleaseId(releaseId: Long): List<RequirementSnapshot>

    fun findByOriginalRequirementId(requirementId: Long): List<RequirementSnapshot>

    fun countByReleaseId(releaseId: Long): Long

    fun deleteByReleaseId(releaseId: Long)
}
