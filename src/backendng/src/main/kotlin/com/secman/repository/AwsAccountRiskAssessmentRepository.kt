package com.secman.repository

import com.secman.domain.AwsAccountRiskAssessment
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDate

@Repository
interface AwsAccountRiskAssessmentRepository : JpaRepository<AwsAccountRiskAssessment, Long> {

    fun findByAwsAccountId(awsAccountId: String): List<AwsAccountRiskAssessment>

    /**
     * Tracked assessments whose deadline (risk_assessment.end_date) lies inside
     * (today, windowEnd] — i.e. 1 or 2 days away for windowEnd = today + 2 —
     * that are still open (status STARTED) and have at least one deadline
     * reminder not yet sent. Used by the daily reminder scheduler.
     */
    @Query(
        """
        SELECT t FROM AwsAccountRiskAssessment t
        JOIN FETCH t.riskAssessment ra
        WHERE ra.endDate > :today
          AND ra.endDate <= :windowEnd
          AND ra.status = 'STARTED'
          AND (t.reminderTwoDaysSentAt IS NULL OR t.reminderOneDaySentAt IS NULL)
        """
    )
    fun findPendingDeadlineReminders(today: LocalDate, windowEnd: LocalDate): List<AwsAccountRiskAssessment>
}
