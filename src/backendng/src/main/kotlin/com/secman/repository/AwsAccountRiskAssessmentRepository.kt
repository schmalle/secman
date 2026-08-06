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
        LEFT JOIN FETCH ra.lockedRelease
        WHERE ra.endDate > :today
          AND ra.endDate <= :windowEnd
          AND ra.status = 'STARTED'
          AND (t.reminderTwoDaysSentAt IS NULL OR t.reminderOneDaySentAt IS NULL)
        """
    )
    fun findPendingDeadlineReminders(today: LocalDate, windowEnd: LocalDate): List<AwsAccountRiskAssessment>

    /**
     * Tracked assessments matching the optional filters, newest first. Backs the MCP
     * tool `list_aws_account_risk_assessments`.
     *
     * The assessment and its pinned release are fetched eagerly - the association is
     * LAZY, and rendering one row per tracking entry would otherwise be an N+1.
     */
    @Query(
        """
        SELECT t FROM AwsAccountRiskAssessment t
        JOIN FETCH t.riskAssessment ra
        LEFT JOIN FETCH ra.lockedRelease
        LEFT JOIN FETCH ra.assessor
        LEFT JOIN FETCH ra.respondent
        WHERE (:awsAccountId IS NULL OR t.awsAccountId = :awsAccountId)
          AND (:ownerEmail IS NULL OR LOWER(t.ownerEmail) = LOWER(:ownerEmail))
          AND (:status IS NULL OR ra.status = :status)
        ORDER BY t.createdAt DESC, t.id DESC
        """
    )
    fun findByFilters(
        awsAccountId: String?,
        ownerEmail: String?,
        status: String?
    ): List<AwsAccountRiskAssessment>

    /**
     * Atomically claim the 1-day deadline reminder (claim-before-send). Also stamps the
     * 2-day slot when still unset, collapsing a missed 2-day reminder into this send.
     * Returns 1 only for the winning caller - overlapping scheduler runs get 0 and must
     * not email.
     */
    @Query(
        """
        UPDATE AwsAccountRiskAssessment t
        SET t.reminderOneDaySentAt = :now,
            t.reminderTwoDaysSentAt = COALESCE(t.reminderTwoDaysSentAt, :now)
        WHERE t.id = :id AND t.reminderOneDaySentAt IS NULL
        """
    )
    fun claimOneDayReminder(id: Long, now: java.time.LocalDateTime): Int

    /** Atomically claim the 2-day deadline reminder. See [claimOneDayReminder]. */
    @Query("UPDATE AwsAccountRiskAssessment t SET t.reminderTwoDaysSentAt = :now WHERE t.id = :id AND t.reminderTwoDaysSentAt IS NULL")
    fun claimTwoDayReminder(id: Long, now: java.time.LocalDateTime): Int

    /** Best-effort claim release when the reminder email failed after a successful claim. */
    @Query(
        """
        UPDATE AwsAccountRiskAssessment t
        SET t.reminderOneDaySentAt = NULL,
            t.reminderTwoDaysSentAt = CASE WHEN t.reminderTwoDaysSentAt = :claimedAt THEN NULL ELSE t.reminderTwoDaysSentAt END
        WHERE t.id = :id AND t.reminderOneDaySentAt = :claimedAt
        """
    )
    fun releaseOneDayReminderClaim(id: Long, claimedAt: java.time.LocalDateTime): Int

    /** Best-effort claim release for the 2-day reminder. */
    @Query("UPDATE AwsAccountRiskAssessment t SET t.reminderTwoDaysSentAt = NULL WHERE t.id = :id AND t.reminderTwoDaysSentAt = :claimedAt")
    fun releaseTwoDayReminderClaim(id: Long, claimedAt: java.time.LocalDateTime): Int
}
