package com.secman.repository

import com.secman.domain.RequirementComment
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable

@Repository
interface RequirementCommentRepository : JpaRepository<RequirementComment, Long> {
    fun findByRequirementId(requirementId: Long, pageable: Pageable): Page<RequirementComment>
    fun findByReleaseId(releaseId: Long, pageable: Pageable): Page<RequirementComment>
    fun findBySnapshotId(snapshotId: Long, pageable: Pageable): Page<RequirementComment>
    fun findByRequirementIdAndAuthorId(requirementId: Long, authorId: Long, pageable: Pageable): Page<RequirementComment>
    fun findByReleaseIdAndAuthorId(releaseId: Long, authorId: Long, pageable: Pageable): Page<RequirementComment>
    fun findBySnapshotIdAndAuthorId(snapshotId: Long, authorId: Long, pageable: Pageable): Page<RequirementComment>
}
