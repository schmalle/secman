package com.secman.repository

import com.secman.domain.AssessmentAssignment
import io.micronaut.data.annotation.Repository
import io.micronaut.data.annotation.Query
import java.time.LocalDateTime
import io.micronaut.data.jpa.repository.JpaRepository

/** Assessment-scoped lookup; callers enforce the shared workflow policy. */
@Repository
interface AssessmentAssignmentRepository : JpaRepository<AssessmentAssignment, Long> {
    fun findByAssessmentId(assessmentId: Long): List<AssessmentAssignment>
    @Query("UPDATE AssessmentAssignment a SET a.reminderSentAt = :now WHERE a.id = :id AND a.version = :version AND a.revoked = false AND a.submitted = false AND (a.reminderSentAt IS NULL OR a.reminderSentAt < :cutoff)")
    fun claimReminder(id: Long, version: Long, now: LocalDateTime, cutoff: LocalDateTime): Int

    @Query("UPDATE AssessmentAssignment a SET a.reminderSentAt = NULL WHERE a.id = :id AND a.reminderSentAt = :claimedAt")
    fun releaseReminder(id: Long, claimedAt: LocalDateTime): Int
}
