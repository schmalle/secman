package com.secman.repository

import com.secman.domain.AssessmentContribution
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/** Assessment-scoped lookup; callers enforce the shared workflow policy. */
@Repository
interface AssessmentContributionRepository : JpaRepository<AssessmentContribution, Long> {
    fun findByAssessmentId(assessmentId: Long): List<AssessmentContribution>
}
