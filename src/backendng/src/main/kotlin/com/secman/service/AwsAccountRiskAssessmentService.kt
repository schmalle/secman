package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccountRiskAssessment
import com.secman.domain.RiskAssessment
import com.secman.domain.UseCase
import com.secman.domain.User
import com.secman.dto.AccountRiskAssessmentInfo
import com.secman.dto.NewAccountImportInfo
import com.secman.repository.AssetRepository
import com.secman.repository.AwsAccountRiskAssessmentRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Auto-starts risk assessments for the owners of brand-new AWS accounts
 * detected during a user-mapping import
 * (CLI: `manage-user-mappings import --start-risk-assessment`).
 *
 * For every (new account, mapped owner email) pair:
 * - the assessment basis is an asset representing the AWS account
 *   (type `AWS_ACCOUNT`, `cloudAccountId` = account id) — found by name or created,
 * - the assessor is a user with the SECCHAMPION role (round-robin over all
 *   SECCHAMPION users so the load spreads evenly),
 * - the owner is set as respondent (when a matching user account exists) and
 *   is notified by email,
 * - the deadline (endDate) is today + deadlineDays (CLI default 7).
 *
 * Each created assessment is recorded in [AwsAccountRiskAssessment], which
 * also carries the reminder state consumed by [processDeadlineReminders]
 * (reminders 2 days and 1 day before the deadline).
 */
@Singleton
open class AwsAccountRiskAssessmentService(
    private val userRepository: UserRepository,
    private val useCaseRepository: UseCaseRepository,
    private val assetRepository: AssetRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val trackingRepository: AwsAccountRiskAssessmentRepository,
    private val emailService: EmailService
) {
    private val log = LoggerFactory.getLogger(AwsAccountRiskAssessmentService::class.java)

    companion object {
        const val DEFAULT_DEADLINE_DAYS = 7
        const val ACCOUNT_ASSET_TYPE = "AWS_ACCOUNT"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }

    /**
     * Fail-fast validation of the risk-assessment parameters of a bulk import
     * request. Returns a human-readable error message, or null when valid.
     */
    open fun validateStartRequest(useCaseName: String?, deadlineDays: Int?): String? {
        if (useCaseName.isNullOrBlank()) {
            return "riskAssessmentUseCase is required when startRiskAssessment is true"
        }
        if (deadlineDays != null && deadlineDays < 1) {
            return "riskAssessmentDeadlineDays must be at least 1 (got $deadlineDays)"
        }
        if (useCaseRepository.findByNameIgnoreCase(useCaseName.trim()).isEmpty) {
            return "Use case '${useCaseName.trim()}' not found"
        }
        if (userRepository.findByRolesContaining(User.Role.SECCHAMPION).isEmpty()) {
            return "No user with SECCHAMPION role exists to act as assessor"
        }
        return null
    }

    /**
     * Start one risk assessment per (new AWS account, owner email) pair.
     * Called by the controller AFTER the mapping import transaction committed,
     * so a failure here never rolls back the imported mappings.
     *
     * @param requestorUserId the ADMIN user who ran the import (requestor of
     *        the assessments); falls back to the assessor when unresolvable.
     * @return one result entry per pair — id on success, error message on failure.
     */
    @Transactional
    open fun startAssessmentsForNewAccounts(
        newAccounts: List<NewAccountImportInfo>,
        useCaseName: String,
        deadlineDays: Int,
        requestorUserId: Long?
    ): List<AccountRiskAssessmentInfo> {
        if (newAccounts.isEmpty()) return emptyList()

        val useCase = useCaseRepository.findByNameIgnoreCase(useCaseName.trim()).orElse(null)
            ?: return newAccounts.flatMap { acct ->
                acct.emails.map { email ->
                    AccountRiskAssessmentInfo(acct.awsAccountId, email, error = "Use case '$useCaseName' not found")
                }
            }

        val secChampions = userRepository.findByRolesContaining(User.Role.SECCHAMPION).sortedBy { it.id }
        if (secChampions.isEmpty()) {
            return newAccounts.flatMap { acct ->
                acct.emails.map { email ->
                    AccountRiskAssessmentInfo(acct.awsAccountId, email, error = "No user with SECCHAMPION role exists")
                }
            }
        }

        val requestorUser = requestorUserId?.let { userRepository.findById(it).orElse(null) }

        val results = mutableListOf<AccountRiskAssessmentInfo>()
        var assessorIndex = 0

        for (account in newAccounts) {
            for (ownerEmail in account.emails) {
                val assessor = secChampions[assessorIndex % secChampions.size]
                assessorIndex++
                results += try {
                    createAssessment(
                        awsAccountId = account.awsAccountId,
                        ownerEmail = ownerEmail,
                        useCase = useCase,
                        deadlineDays = deadlineDays,
                        assessor = assessor,
                        requestor = requestorUser ?: assessor
                    )
                } catch (e: Exception) {
                    log.error(
                        "Failed to start risk assessment for AWS account {} / owner {}: {}",
                        account.awsAccountId, ownerEmail, e.message, e
                    )
                    AccountRiskAssessmentInfo(
                        awsAccountId = account.awsAccountId,
                        ownerEmail = ownerEmail,
                        error = "Failed to start risk assessment: ${e.message}"
                    )
                }
            }
        }
        return results
    }

    private fun createAssessment(
        awsAccountId: String,
        ownerEmail: String,
        useCase: UseCase,
        deadlineDays: Int,
        assessor: User,
        requestor: User
    ): AccountRiskAssessmentInfo {
        val today = LocalDate.now()
        val endDate = today.plusDays(deadlineDays.toLong())

        // Idempotency guard: re-running an import (or two overlapping imports) must not
        // create a second assessment + tracking row + reminder stream for the same
        // (account, owner) while one is still open. No DB unique constraint is used here
        // because a NEW assessment for the same pair is legitimate once the previous one
        // is completed; only concurrent/repeated creation of OPEN ones is a defect.
        val existingOpen = trackingRepository.findByAwsAccountId(awsAccountId)
            .filter { it.ownerEmail.equals(ownerEmail, ignoreCase = true) }
            .firstOrNull { it.riskAssessment.status == "STARTED" }
        if (existingOpen != null) {
            log.info(
                "Skipping risk assessment creation for AWS account {} / owner {}: open assessment {} already tracked",
                awsAccountId, ownerEmail, existingOpen.riskAssessment.id
            )
            return AccountRiskAssessmentInfo(
                awsAccountId = awsAccountId,
                ownerEmail = ownerEmail,
                riskAssessmentId = existingOpen.riskAssessment.id,
                error = "Skipped: an open risk assessment (id=${existingOpen.riskAssessment.id}) already exists for this account/owner"
            )
        }

        val asset = findOrCreateAccountAsset(awsAccountId, ownerEmail)
        val ownerUser = userRepository.findByEmailIgnoreCase(ownerEmail).orElse(null)

        val assessment = RiskAssessment(
            startDate = today,
            endDate = endDate,
            assessmentBasisType = AssessmentBasisType.ASSET,
            assessmentBasisId = asset.id!!,
            assessor = assessor,
            requestor = requestor,
            asset = asset
        )
        assessment.respondent = ownerUser
        assessment.useCases = mutableSetOf(useCase)
        assessment.notes = "Automatically started by AWS account mapping import for account " +
            "$awsAccountId (owner: $ownerEmail, use case: ${useCase.name})"

        val saved = riskAssessmentRepository.save(assessment)

        trackingRepository.save(
            AwsAccountRiskAssessment(
                awsAccountId = awsAccountId,
                ownerEmail = ownerEmail,
                riskAssessment = saved,
                useCaseName = useCase.name
            )
        )

        // Best-effort owner notification — a mail failure must not undo the assessment.
        try {
            sendStartNotification(ownerEmail, awsAccountId, useCase.name, endDate, assessor)
        } catch (e: Exception) {
            log.warn("Risk assessment {} created, but owner notification to {} failed: {}",
                saved.id, ownerEmail, e.message)
        }

        log.info(
            "Started risk assessment {} for new AWS account {} (owner={}, assessor={}, useCase={}, deadline={})",
            saved.id, awsAccountId, ownerEmail, assessor.username, useCase.name, endDate
        )

        return AccountRiskAssessmentInfo(
            awsAccountId = awsAccountId,
            ownerEmail = ownerEmail,
            riskAssessmentId = saved.id,
            assessor = assessor.email.ifBlank { assessor.username },
            endDate = endDate.format(DATE_FORMAT)
        )
    }

    /**
     * Resolve the asset representing an AWS account, creating it when absent.
     * The owner email is stored as asset owner and the account id as
     * cloudAccountId, so the owner reaches the asset through the unified
     * access rules (owner match / UserMapping cloud-account match).
     */
    private fun findOrCreateAccountAsset(awsAccountId: String, ownerEmail: String): Asset {
        val assetName = "AWS Account $awsAccountId"
        val existing = assetRepository.findByName(assetName).orElse(null)
        if (existing != null) return existing

        // Best-effort duplicate mitigation: find-then-save is racy (asset.name has no DB
        // unique constraint yet - see docs/RACE_CONDITIONS.md), so if a concurrent import
        // created the asset between the check and the save, fall back to the winner's row.
        return try {
            assetRepository.save(
                Asset(
                    name = assetName,
                    type = ACCOUNT_ASSET_TYPE,
                    owner = ownerEmail,
                    description = "Automatically created for the risk assessment of AWS account " +
                        "$awsAccountId (user-mapping import)",
                    cloudAccountId = awsAccountId
                )
            )
        } catch (e: Exception) {
            assetRepository.findByName(assetName).orElseThrow { e }
        }
    }

    // --- Deadline reminders -------------------------------------------------

    /**
     * Send the deadline reminders for tracked assessments: one 2 days and one
     * 1 day before the risk assessment's endDate. Invoked daily by
     * [com.secman.scheduler.AwsAccountRiskAssessmentReminderScheduler];
     * [today] is injectable for tests.
     *
     * Idempotent across restarts AND across concurrent runs: each reminder slot is
     * claimed via an atomic guarded UPDATE (claim-before-send) committed per row, so
     * overlapping scheduler runs (multi-instance deploys, a run overlapping a slow
     * previous one) can never double-send. If the 2-day reminder was missed (e.g.
     * downtime), the 1-day claim stamps both slots and a single catch-up reminder is
     * sent. When a send fails after a successful claim, the claim is released
     * (best-effort) so the next run retries. Only open assessments (status STARTED)
     * are reminded.
     *
     * Deliberately NOT @Transactional: the claims must commit per row to be visible
     * to concurrent runs, and a method-wide transaction rolling back after emails
     * went out would un-stamp already-sent reminders.
     *
     * @return number of reminder emails sent
     */
    open fun processDeadlineReminders(today: LocalDate = LocalDate.now()): Int {
        val pending = trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2))
        if (pending.isEmpty()) return 0

        var sent = 0
        for (tracking in pending) {
            val endDate = tracking.riskAssessment.endDate
            val daysLeft = ChronoUnit.DAYS.between(today, endDate)
            try {
                val claimedAt = LocalDateTime.now()
                val isOneDaySlot = daysLeft <= 1 && tracking.reminderOneDaySentAt == null
                val isTwoDaySlot = !isOneDaySlot && daysLeft == 2L && tracking.reminderTwoDaysSentAt == null

                val claimed = when {
                    isOneDaySlot -> trackingRepository.claimOneDayReminder(tracking.id!!, claimedAt) == 1
                    isTwoDaySlot -> trackingRepository.claimTwoDayReminder(tracking.id!!, claimedAt) == 1
                    else -> false
                }
                if (!claimed) continue

                if (sendReminder(tracking, daysLeft, endDate)) {
                    sent++
                } else {
                    log.warn("Deadline reminder send failed for tracking {} - releasing claim for retry", tracking.id)
                    if (isOneDaySlot) trackingRepository.releaseOneDayReminderClaim(tracking.id!!, claimedAt)
                    else trackingRepository.releaseTwoDayReminderClaim(tracking.id!!, claimedAt)
                }
            } catch (e: Exception) {
                log.error("Failed to send deadline reminder for tracking {} (assessment {}): {}",
                    tracking.id, tracking.riskAssessment.id, e.message, e)
            }
        }

        log.info("Risk assessment deadline reminder run completed: {} reminder(s) sent", sent)
        return sent
    }

    private fun sendReminder(tracking: AwsAccountRiskAssessment, daysLeft: Long, endDate: LocalDate): Boolean {
        val dayWord = if (daysLeft == 1L) "1 day" else "$daysLeft days"
        val subject = "Reminder: risk assessment for AWS account ${tracking.awsAccountId} due in $dayWord"
        val deadline = endDate.format(DATE_FORMAT)
        val textBody = """
            |This is a reminder that the risk assessment for AWS account ${tracking.awsAccountId}
            |(use case: ${tracking.useCaseName}) is due in $dayWord, on $deadline.
            |
            |Please log in to SecMan and complete the assessment before the deadline.
            |
            |-- SecMan
        """.trimMargin()
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <body style="font-family:Arial,sans-serif;color:#333;max-width:600px;margin:0 auto;padding:20px;">
              <h2 style="color:#2c3e50;">Risk assessment deadline reminder</h2>
              <p>The risk assessment for AWS account
                 <strong style="font-family:monospace;">${escapeHtml(tracking.awsAccountId)}</strong>
                 (use case: ${escapeHtml(tracking.useCaseName)}) is due in <strong>$dayWord</strong>,
                 on <strong>$deadline</strong>.</p>
              <p>Please log in to SecMan and complete the assessment before the deadline.</p>
              <hr style="margin:30px 0;border:none;border-top:1px solid #dee2e6;">
              <p style="font-size:0.85em;color:#6c757d;">This message was sent by SecMan.</p>
            </body>
            </html>
        """.trimIndent()

        return try {
            emailService.sendEmail(tracking.ownerEmail, subject, textBody, htmlBody).get()
        } catch (e: Exception) {
            log.error("Reminder email to {} failed: {}", tracking.ownerEmail, e.message)
            false
        }
    }

    private fun sendStartNotification(
        ownerEmail: String,
        awsAccountId: String,
        useCaseName: String,
        endDate: LocalDate,
        assessor: User
    ) {
        val subject = "Risk assessment started for your AWS account $awsAccountId"
        val deadline = endDate.format(DATE_FORMAT)
        val assessorDisplay = assessor.email.ifBlank { assessor.username }
        val textBody = """
            |A risk assessment has been started for AWS account $awsAccountId, which was
            |mapped to you in SecMan.
            |
            |Use case:  $useCaseName
            |Assessor:  $assessorDisplay
            |Deadline:  $deadline
            |
            |Please log in to SecMan and complete the assessment before the deadline.
            |You will receive reminders 2 days and 1 day before the deadline.
            |
            |-- SecMan
        """.trimMargin()
        val htmlBody = """
            <!DOCTYPE html>
            <html>
            <body style="font-family:Arial,sans-serif;color:#333;max-width:600px;margin:0 auto;padding:20px;">
              <h2 style="color:#2c3e50;">Risk assessment started</h2>
              <p>A risk assessment has been started for AWS account
                 <strong style="font-family:monospace;">${escapeHtml(awsAccountId)}</strong>,
                 which was mapped to you in SecMan.</p>
              <table style="border-collapse:collapse;margin-top:8px;">
                <tr><td style="padding:4px 8px;color:#495057;">Use case</td><td style="padding:4px 8px;">${escapeHtml(useCaseName)}</td></tr>
                <tr><td style="padding:4px 8px;color:#495057;">Assessor</td><td style="padding:4px 8px;">${escapeHtml(assessorDisplay)}</td></tr>
                <tr><td style="padding:4px 8px;color:#495057;">Deadline</td><td style="padding:4px 8px;"><strong>$deadline</strong></td></tr>
              </table>
              <p>Please log in to SecMan and complete the assessment before the deadline.
                 You will receive reminders 2 days and 1 day before the deadline.</p>
              <hr style="margin:30px 0;border:none;border-top:1px solid #dee2e6;">
              <p style="font-size:0.85em;color:#6c757d;">This message was sent by SecMan.</p>
            </body>
            </html>
        """.trimIndent()

        emailService.sendEmail(ownerEmail, subject, textBody, htmlBody).get()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
