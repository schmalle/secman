package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.Asset
import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccountRiskAssessment
import com.secman.domain.Release
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
import com.secman.util.EmailAddressValidator
import jakarta.inject.Provider
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
 * - the *standard* it is measured against is the current version of the security
 *   requirements, i.e. the single ACTIVE [Release]. The assessment is pinned to it
 *   (`lockedRelease`), so its questionnaire is resolved from that release's frozen
 *   snapshots scoped by the use case tag and cannot drift when requirements are
 *   re-imported while the assessment is open,
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
    private val emailService: EmailService,
    private val appConfig: AppConfig,
    private val releaseRequirementScopeService: ReleaseRequirementScopeService,
    private val templateRenderer: EmailTemplateRenderer,
    // Self-reference so [createAssessment]'s `@Transactional(REQUIRES_NEW)` runs through the AOP
    // proxy (a same-class call would bypass it). Each per-(account, owner) persist thus commits
    // in its own short transaction, letting the caller send the owner email AFTER the connection
    // is returned — never holding a pooled connection across the blocking SMTP send. A Provider
    // (lazy) is used so the bean can depend on a provider of itself without a construction cycle.
    private val selfProvider: Provider<AwsAccountRiskAssessmentService>
) {
    private val log = LoggerFactory.getLogger(AwsAccountRiskAssessmentService::class.java)

    companion object {
        const val DEFAULT_DEADLINE_DAYS = 7

        /**
         * Upper bound for the caller-supplied deadline, 10 years.
         *
         * Not cosmetic. `endDate` is a SQL DATE: past roughly year 9999 the insert fails
         * outright, and the per-pair error arrives only *after* the mappings have already
         * committed. Well short of that, a deadline decades out is indistinguishable from a
         * typo (`--risk-deadline-days 100000` is one keystroke away from `1000`) and produces
         * an assessment the reminder scheduler will never wake up for, because it only looks
         * two days ahead. Rejecting up front, in validateStartRequest, keeps both cases out
         * of the database.
         */
        const val MAX_DEADLINE_DAYS = 3650
        const val ACCOUNT_ASSET_TYPE = "AWS_ACCOUNT"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        // Owner mails are rendered from the shared email-templates/ resources rather than
        // inline HTML, so they carry the SecMan logo and the same layout as every other
        // notification (see AwsAccountSharingNotificationService for the same pattern).
        // Basenames, not paths: EmailTemplateRenderer owns the directory and the allowlist.
        const val STARTED_TEMPLATE = "aws-account-risk-assessment-started"
        const val REMINDER_TEMPLATE = "aws-account-risk-assessment-reminder"
        private const val ASSESSMENTS_PATH = "/risk-assessments"
    }

    /**
     * Fail-fast validation of the risk-assessment parameters of a bulk import
     * request. Returns a human-readable error message, or null when valid.
     *
     * Runs before anything is imported, so an operator who has not yet activated a
     * requirements release is told so instead of silently getting assessments with
     * an empty questionnaire.
     */
    open fun validateStartRequest(useCaseName: String?, deadlineDays: Int?): String? {
        if (useCaseName.isNullOrBlank()) {
            return "riskAssessmentUseCase is required when startRiskAssessment is true"
        }
        if (deadlineDays != null && deadlineDays < 1) {
            return "riskAssessmentDeadlineDays must be at least 1 (got $deadlineDays)"
        }
        if (deadlineDays != null && deadlineDays > MAX_DEADLINE_DAYS) {
            return "riskAssessmentDeadlineDays must be at most $MAX_DEADLINE_DAYS (got $deadlineDays)"
        }
        val useCase = useCaseRepository.findByNameIgnoreCase(useCaseName.trim()).orElse(null)
            ?: return "Use case '${useCaseName.trim()}' not found"
        if (userRepository.findByRolesContaining(User.Role.SECCHAMPION).isEmpty()) {
            return "No user with SECCHAMPION role exists to act as assessor"
        }
        // The assessment is measured against the current version of the security
        // requirements, which in secman is the single ACTIVE release.
        val activeRelease = releaseRequirementScopeService.findActiveRelease()
            ?: return "No ACTIVE release exists to base the risk assessment on - " +
                "activate a requirements release first"
        val useCaseId = useCase.id
            ?: return "Use case '${useCase.name}' has no id"
        if (releaseRequirementScopeService.requirementsForRelease(activeRelease.id!!, useCaseId).isEmpty()) {
            return "ACTIVE release '${activeRelease.version}' contains no requirements " +
                "tagged with use case '${useCase.name}'"
        }
        return null
    }

    /**
     * The same fail-fast checks over the *prerequisites* an assessment needs, without naming a
     * use case: a SECCHAMPION to assess it and an ACTIVE release to measure it against.
     *
     * Guided onboarding cannot check a use case up front — which ones apply is precisely what
     * the owner has not answered yet — but the two environmental preconditions are knowable
     * before the first invite is mailed, and finding out later means the owner clicked a link
     * that could never have worked.
     */
    open fun validateAssessmentPrerequisites(deadlineDays: Int?): String? {
        if (deadlineDays != null && deadlineDays < 1) {
            return "riskAssessmentDeadlineDays must be at least 1 (got $deadlineDays)"
        }
        if (deadlineDays != null && deadlineDays > MAX_DEADLINE_DAYS) {
            return "riskAssessmentDeadlineDays must be at most $MAX_DEADLINE_DAYS (got $deadlineDays)"
        }
        if (userRepository.findByRolesContaining(User.Role.SECCHAMPION).isEmpty()) {
            return "No user with SECCHAMPION role exists to act as assessor"
        }
        if (releaseRequirementScopeService.findActiveRelease() == null) {
            return "No ACTIVE release exists to base the risk assessment on - " +
                "activate a requirements release first"
        }
        return null
    }

    /** The next SECCHAMPION in the round-robin, or null when none exists. Shared with guided onboarding. */
    open fun pickAssessor(rotation: Int): User? {
        val secChampions = userRepository.findByRolesContaining(User.Role.SECCHAMPION).sortedBy { it.id }
        if (secChampions.isEmpty()) return null
        return secChampions[Math.floorMod(rotation, secChampions.size)]
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
    // Deliberately NOT @Transactional: each per-(account, owner) persist runs in its own
    // REQUIRES_NEW transaction (createAssessment via selfProvider) so the blocking owner-email
    // send below never holds a pooled DB connection. A failure on one pair no longer risks the
    // others, and — per the method contract — nothing here can roll back the committed import.
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

        // The "standard" the assessments are measured against: the current version of
        // the security requirements, i.e. the single ACTIVE release. Resolved once so
        // every account in one import pins to the same version even if a release is
        // activated mid-run.
        val activeRelease = releaseRequirementScopeService.findActiveRelease()
            ?: return newAccounts.flatMap { acct ->
                acct.emails.map { email ->
                    AccountRiskAssessmentInfo(
                        acct.awsAccountId, email,
                        error = "No ACTIVE release exists to base the risk assessment on"
                    )
                }
            }
        val requirementCount = useCase.id
            ?.let { releaseRequirementScopeService.requirementsForRelease(activeRelease.id!!, it).size }
            ?: 0

        val requestorUser = requestorUserId?.let { userRepository.findById(it).orElse(null) }

        val results = mutableListOf<AccountRiskAssessmentInfo>()
        var assessorIndex = 0
        // Both entry points run validateStartRequest first, so an out-of-range value can only
        // reach here from a direct service call. Clamp rather than throw: we are past the
        // commit point, and a silently absurd endDate is worse than a corrected one.
        val effectiveDeadlineDays = deadlineDays.coerceIn(1, MAX_DEADLINE_DAYS)
        if (effectiveDeadlineDays != deadlineDays) {
            log.warn(
                "deadlineDays={} is outside 1..{} - clamped to {}",
                deadlineDays, MAX_DEADLINE_DAYS, effectiveDeadlineDays
            )
        }
        // Computed once so the persisted endDate and the emailed deadline can never disagree.
        val endDate = LocalDate.now().plusDays(effectiveDeadlineDays.toLong())

        for (account in newAccounts) {
            for (ownerEmail in account.emails) {
                val assessor = secChampions[assessorIndex % secChampions.size]
                assessorIndex++
                val info = try {
                    selfProvider.get().createAssessment(
                        awsAccountId = account.awsAccountId,
                        ownerEmail = ownerEmail,
                        useCases = setOf(useCase),
                        endDate = endDate,
                        assessor = assessor,
                        requestor = requestorUser ?: assessor,
                        activeRelease = activeRelease,
                        requirementCount = requirementCount
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
                results += info

                // Notify the owner only AFTER the persist transaction has committed, so the
                // blocking SMTP send holds no pooled DB connection. Both `error` and `skipped`
                // suppress the mail — a failure has nothing to announce, and a skip means the
                // owner was already told when the still-open assessment was first created.
                // Load-bearing: `skipped` no longer sets `error`, so testing `error` alone
                // would re-notify on every re-import. Best-effort — a mail failure must not
                // undo the committed assessment.
                if (info.error == null && !info.skipped && info.riskAssessmentId != null) {
                    try {
                        notifyAssessmentStarted(
                            ownerEmail, account.awsAccountId, useCase.name, endDate, assessor, activeRelease,
                            info.riskAssessmentId
                        )
                    } catch (e: Exception) {
                        log.warn("Risk assessment {} created, but owner notification to {} failed: {}",
                            info.riskAssessmentId, ownerEmail, e.message)
                    }
                }
            }
        }
        return results
    }

    // REQUIRES_NEW: each pair persists in its own short, independent transaction so the caller
    // can send the owner email after commit (no connection held across SMTP) and one failure
    // never rolls back another pair. Must be `open` and invoked via selfProvider for the AOP
    // proxy to apply. Detached assessor/requestor/useCase are safe FK references here — those
    // associations declare no cascade (see RiskAssessment).
    //
    // Takes a SET of use cases, not one: guided onboarding
    // ([com.secman.domain.AccountOnboardingMode.GUIDED]) scopes an assessment to the union of
    // every matching rule's use cases, and RiskAssessment.useCases has always been a set.
    // The DIRECT path passes a singleton and is unchanged in behaviour.
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun createAssessment(
        awsAccountId: String,
        ownerEmail: String,
        useCases: Set<UseCase>,
        endDate: LocalDate,
        assessor: User,
        requestor: User,
        activeRelease: Release,
        requirementCount: Int
    ): AccountRiskAssessmentInfo {
        val today = LocalDate.now()
        require(useCases.isNotEmpty()) { "createAssessment requires at least one use case" }
        // Sorted so the persisted name, the notes and the log line are stable across runs and
        // two assessments over the same use cases compare equal by string.
        val useCaseNames = useCases.map { it.name }.sorted()
        val joinedUseCaseNames = useCaseNames.joinToString(", ")

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
            // Reported as `skipped`, NOT as `error`: the import did exactly what it was asked
            // to and the pair already has a live assessment. Callers surface it as a no-op and
            // must not let it drive a non-zero exit status.
            return AccountRiskAssessmentInfo(
                awsAccountId = awsAccountId,
                ownerEmail = ownerEmail,
                riskAssessmentId = existingOpen.riskAssessment.id,
                skipped = true,
                skipReason = "an open risk assessment (id=${existingOpen.riskAssessment.id}) " +
                    "already exists for this account/owner"
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
        assessment.useCases = useCases.toMutableSet()
        // Pin to the current version of the security requirements. The questionnaire is
        // then resolved from that release's frozen snapshots (see
        // ResponseController.getRequirementsForAssessment), so re-importing requirements
        // while the assessment is open cannot change the questions already asked.
        assessment.lockedRelease = activeRelease
        assessment.isReleaseLocked = true
        assessment.contentSnapshotTaken = true
        // notes is capped at 1024 by the column, and the use case list is now unbounded in
        // principle — truncate rather than fail the insert after the import already committed.
        assessment.notes = EmailAddressValidator.sanitizeForEcho(
            "Automatically started by AWS account mapping import for account " +
                "$awsAccountId (owner: $ownerEmail, use case: $joinedUseCaseNames, " +
                "requirements version: ${activeRelease.version})",
            maxLength = 1024
        )

        val saved = riskAssessmentRepository.save(assessment)

        trackingRepository.save(
            AwsAccountRiskAssessment(
                awsAccountId = awsAccountId,
                ownerEmail = ownerEmail,
                riskAssessment = saved,
                // Column widened to 1024 in V253 for exactly this: a union of names, not one.
                useCaseName = joinedUseCaseNames.take(1024)
            )
        )

        // Owner notification is sent by the caller AFTER this transaction commits, so the
        // blocking SMTP send never holds this pooled DB connection.
        log.info(
            "Started risk assessment {} for new AWS account {} (owner={}, assessor={}, useCases={}, " +
                "requirementsVersion={}, requirements={}, deadline={})",
            saved.id, awsAccountId, ownerEmail, assessor.username, joinedUseCaseNames,
            activeRelease.version, requirementCount, endDate
        )

        return AccountRiskAssessmentInfo(
            awsAccountId = awsAccountId,
            ownerEmail = ownerEmail,
            riskAssessmentId = saved.id,
            assessor = assessor.email.ifBlank { assessor.username },
            endDate = endDate.format(DATE_FORMAT),
            useCase = joinedUseCaseNames,
            useCases = useCaseNames,
            releaseVersion = activeRelease.version,
            requirementCount = requirementCount
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
        // Assessments started before release pinning was introduced have no locked release,
        // so the version row is rendered conditionally rather than shown empty.
        val lockedVersion = tracking.riskAssessment.lockedRelease?.version
        val values = mapOf(
            "awsAccountId" to tracking.awsAccountId,
            "useCaseName" to tracking.useCaseName,
            "requirementsVersion" to (lockedVersion ?: ""),
            "dayWord" to dayWord,
            "deadline" to endDate.format(DATE_FORMAT),
            "assessmentsUrl" to assessmentUrl(tracking.riskAssessment.id),
        )
        val htmlTemplate =
            renderConditionalBlock(templateRenderer.readHtml(REMINDER_TEMPLATE), "ifVersion", lockedVersion != null)
        val textTemplate =
            renderConditionalBlock(templateRenderer.readText(REMINDER_TEMPLATE), "ifVersion", lockedVersion != null)

        return try {
            emailService.sendEmailWithInlineImages(
                to = tracking.ownerEmail,
                subject = subject,
                textContent = render(textTemplate, values, escape = false),
                htmlContent = render(htmlTemplate, values, escape = true),
                inlineImages = loadLogoInlineImage(),
            ).get()
        } catch (e: Exception) {
            log.error("Reminder email to {} failed: {}", tracking.ownerEmail, e.message)
            false
        }
    }

    /**
     * The "your risk assessment has started" mail.
     *
     * Public and reused by [AccountOnboardingService] when a guided questionnaire submission
     * creates an assessment, so both paths send the identical mail rather than growing a
     * second near-copy. The subject line is asserted by the `/aws-account-owner-email` E2E
     * skill — changing it breaks that gate, deliberately.
     *
     * @param useCaseNames one name, or several comma-joined for a guided union.
     */
    open fun notifyAssessmentStarted(
        ownerEmail: String,
        awsAccountId: String,
        useCaseNames: String,
        endDate: LocalDate,
        assessor: User,
        release: Release,
        assessmentId: Long?
    ) {
        val subject = "Risk assessment started for your AWS account $awsAccountId"
        val values = mapOf(
            "awsAccountId" to awsAccountId,
            "useCaseName" to useCaseNames,
            "requirementsVersion" to "${release.version} (${release.name})",
            "assessor" to assessor.email.ifBlank { assessor.username },
            "deadline" to endDate.format(DATE_FORMAT),
            "assessmentsUrl" to assessmentUrl(assessmentId),
        )

        emailService.sendEmailWithInlineImages(
            to = ownerEmail,
            subject = subject,
            textContent = render(templateRenderer.readText(STARTED_TEMPLATE), values, escape = false),
            htmlContent = render(templateRenderer.readHtml(STARTED_TEMPLATE), values, escape = true),
            inlineImages = loadLogoInlineImage(),
        ).get()
    }

    /**
     * Deep link that opens *this* assessment, not just the list — the owner should land on
     * the questionnaire they were mailed about rather than hunt for it.
     *
     * Deliberately NOT an `/respond/{token}` link: that route is token-authenticated and
     * would let anyone holding the mail answer on the owner's behalf. This points into the
     * normal authenticated app, so an unauthenticated visitor is bounced to `/login` and
     * returned here afterwards (Layout.astro captures the target, Login.tsx restores it).
     *
     * Sourced from `backend.baseUrl` for the same reason [AwsAccountSharingNotificationService]
     * does: in real deployments nginx fronts both API and UI on one host
     * (`SECMAN_BACKEND_URL`), whereas `frontend.baseUrl` defaults to localhost and has no env
     * override — so this keeps the link on the public host without a second config knob.
     */
    private fun assessmentUrl(assessmentId: Long?): String {
        val base = appConfig.backend.baseUrl.trimEnd('/') + ASSESSMENTS_PATH
        return if (assessmentId != null) "$base?assessmentId=$assessmentId" else base
    }

    // Rendering, HTML escaping, the {ifVersion} conditional and the inline logo all moved to
    // [EmailTemplateRenderer] when guided onboarding needed the same behaviour. Behaviour is
    // unchanged — the mails this service sends must render byte-identically, which is what
    // AwsAccountRiskAssessmentServiceTest asserts.
    private fun render(template: String, values: Map<String, String>, escape: Boolean): String =
        templateRenderer.render(template, values, escape)

    private fun renderConditionalBlock(template: String, name: String, include: Boolean): String =
        templateRenderer.renderConditionalBlock(template, name, include)

    private fun loadLogoInlineImage(): Map<String, Pair<ByteArray, String>> =
        templateRenderer.loadLogoInlineImage()
}
