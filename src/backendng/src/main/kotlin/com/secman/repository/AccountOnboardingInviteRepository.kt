package com.secman.repository

import com.secman.domain.AccountOnboardingInvite
import com.secman.domain.InviteStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface AccountOnboardingInviteRepository : JpaRepository<AccountOnboardingInvite, Long> {

    /**
     * The public questionnaire's only lookup. Exact match on the full token — there is no
     * prefix search and no listing endpoint, so a token cannot be discovered, only presented.
     */
    fun findByToken(token: String): Optional<AccountOnboardingInvite>

    fun findByAwsAccountIdAndOwnerEmailIgnoreCaseAndStatus(
        awsAccountId: String,
        ownerEmail: String,
        status: InviteStatus
    ): List<AccountOnboardingInvite>

    fun findByAwsAccountIdOrderByCreatedAtDesc(awsAccountId: String): List<AccountOnboardingInvite>

    fun countByStatus(status: InviteStatus): Long

    /**
     * Atomically claim the invite for a submission (claim-before-create).
     *
     * This is the single-use control. It is a guarded UPDATE rather than a read-then-write,
     * so two submissions arriving together cannot both see PENDING and both create an
     * assessment. Returns 1 for exactly one caller; every other caller gets 0 and must
     * answer with the same generic not-found an unknown token gets.
     *
     * The expiry is part of the guard, not a separate check, so a token cannot be used in
     * the window between an expiry check and the update.
     */
    @Query(
        """
        UPDATE AccountOnboardingInvite i
        SET i.status = :submitted, i.usedAt = :now, i.updatedAt = :now
        WHERE i.token = :token AND i.status = :pending AND i.expiresAt > :now
        """
    )
    fun claim(token: String, now: LocalDateTime, pending: InviteStatus, submitted: InviteStatus): Int

    /**
     * Release a claim when assessment creation failed after a successful claim, so the owner
     * can retry with the link they already have. Best-effort, mirroring
     * `AwsAccountRiskAssessmentRepository.releaseOneDayReminderClaim`.
     */
    @Query(
        """
        UPDATE AccountOnboardingInvite i
        SET i.status = :pending, i.usedAt = NULL, i.updatedAt = :now
        WHERE i.id = :id AND i.status = :submitted AND i.riskAssessment IS NULL
        """
    )
    fun releaseClaim(id: Long, now: LocalDateTime, pending: InviteStatus, submitted: InviteStatus): Int

    /**
     * Invites whose link expires inside (now, windowEnd] and that have not been nudged yet.
     * Feeds the daily reminder run.
     */
    @Query(
        """
        SELECT i FROM AccountOnboardingInvite i
        WHERE i.status = :pending
          AND i.expiresAt > :now
          AND i.expiresAt <= :windowEnd
          AND i.reminderSentAt IS NULL
        ORDER BY i.expiresAt ASC, i.id ASC
        """
    )
    fun findPendingReminders(
        now: LocalDateTime,
        windowEnd: LocalDateTime,
        pending: InviteStatus
    ): List<AccountOnboardingInvite>

    /** Atomically claim the expiry nudge, so overlapping scheduler runs cannot double-send. */
    @Query(
        """
        UPDATE AccountOnboardingInvite i
        SET i.reminderSentAt = :now, i.updatedAt = :now
        WHERE i.id = :id AND i.reminderSentAt IS NULL
        """
    )
    fun claimReminder(id: Long, now: LocalDateTime): Int

    /** Best-effort release when the nudge failed after a successful claim. */
    @Query(
        """
        UPDATE AccountOnboardingInvite i
        SET i.reminderSentAt = NULL, i.updatedAt = :now
        WHERE i.id = :id AND i.reminderSentAt = :claimedAt
        """
    )
    fun releaseReminderClaim(id: Long, claimedAt: LocalDateTime, now: LocalDateTime): Int

    /**
     * Mark lapsed invites EXPIRED in one statement. Terminal and idempotent: the WHERE clause
     * excludes anything already moved on, so a re-run changes nothing.
     */
    @Query(
        """
        UPDATE AccountOnboardingInvite i
        SET i.status = :expired, i.updatedAt = :now
        WHERE i.status = :pending AND i.expiresAt <= :now
        """
    )
    fun expireLapsed(now: LocalDateTime, pending: InviteStatus, expired: InviteStatus): Int
}
