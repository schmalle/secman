package com.secman.repository

import com.secman.domain.SupplierAssessmentInvitation
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable
import java.time.Instant

@Repository
interface SupplierAssessmentInvitationRepository : JpaRepository<SupplierAssessmentInvitation, Long> {
    fun findFirstByAssessmentIdOrderByGenerationDesc(assessmentId: Long): SupplierAssessmentInvitation?
    fun existsByAssessmentId(assessmentId: Long): Boolean
    fun existsByReleaseId(releaseId: Long): Boolean
    fun existsByReleaseIdIn(releaseIds: Collection<Long>): Boolean
    fun findByDeliveryDueAtLessThanEqualOrderByDeliveryDueAt(due: Instant, pageable: Pageable): List<SupplierAssessmentInvitation>
    fun findByPollDueAtLessThanEqualOrderByPollDueAt(due: Instant, pageable: Pageable): List<SupplierAssessmentInvitation>
}
