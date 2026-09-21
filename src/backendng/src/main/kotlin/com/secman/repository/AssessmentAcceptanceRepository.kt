package com.secman.repository

import com.secman.domain.AssessmentAcceptance
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/** Assessment-scoped lookup; callers enforce the shared workflow policy. */
@Repository
interface AssessmentAcceptanceRepository : JpaRepository<AssessmentAcceptance, Long> {
    fun findByAssessmentId(assessmentId: Long): List<AssessmentAcceptance>
}
