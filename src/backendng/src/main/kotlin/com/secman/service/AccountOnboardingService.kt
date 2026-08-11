package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.config.AppConfig
import com.secman.domain.AccountOnboardingInvite
import com.secman.domain.AccountOnboardingMode
import com.secman.domain.AccountOnboardingQuestion
import com.secman.domain.InviteStatus
import com.secman.domain.User
import com.secman.dto.AccountOnboardingInfo
import com.secman.dto.AccountRiskAssessmentInfo
import com.secman.dto.NewAccountImportInfo
import com.secman.dto.OnboardingRuleMatrix
import com.secman.dto.OnboardingRuleSummary
import com.secman.repository.AccountOnboardingInviteRepository
import com.secman.repository.AccountOnboardingQuestionRepository
import com.secman.repository.AccountOnboardingRuleRepository
import com.secman.repository.AwsAccountRiskAssessmentRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UserRepository
import com.secman.util.EmailAddressValidator
import io.micronaut.context.annotation.Value
import jakarta.inject.Provider
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Everything SecMan does for the owner of a brand-new AWS account, in one place.
 *
 * Three modes, all reachable from CLI, MCP and REST, all dry-runnable
 * (see [AccountOnboardingMode]):
 *
 * - [AccountOnboardingMode.WELCOME_ONLY] — a welcome mail.
 * - [AccountOnboardingMode.DIRECT] — welcome mail (opt-in) plus an assessment for a named use
 *   case, delegated **unchanged** to [AwsAccountRiskAssessmentService.startAssessmentsForNewAccounts].
 * - [AccountOnboardingMode.GUIDED] — welcome mail plus a one-time questionnaire link. The
 *   assessment is created only when the owner submits, scoped to the union of the use cases
 *   their answers resolve to.
 *
 * ### Two invariants worth stating out loud
 *
 * **Mail is sent strictly after the transaction that made it truthful has committed.** The
 * discipline is copied from [AwsAccountRiskAssessmentService]: persist in a
 * `REQUIRES_NEW` transaction reached through [selfProvider] so the AOP proxy applies, return,
 * *then* send. A blocking SMTP send must never hold a pooled DB connection, and a mail must
 * never announce a row that a later rollback removes.
 *
 * **A skip is not a failure.** Re-running an import that already onboarded a pair reports
 * `skipped` with a reason, never `error`, so it cannot drive a non-zero CLI exit status.
 */
@Singleton
open class AccountOnboardingService(
    private val awsAccountRiskAssessmentService: AwsAccountRiskAssessmentService,
    private val ruleMatcher: AccountOnboardingRuleMatcher,
    private val questionRepository: AccountOnboardingQuestionRepository,
    private val ruleRepository: AccountOnboardingRuleRepository,
    private val inviteRepository: AccountOnboardingInviteRepository,
    private val trackingRepository: AwsAccountRiskAssessmentRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val templateRenderer: EmailTemplateRenderer,
    private val releaseRequirementScopeService: ReleaseRequirementScopeService,
    private val objectMapper: ObjectMapper,
    private val appConfig: AppConfig,
    // Lazy self-reference so createInvite's REQUIRES_NEW runs through the proxy — a same-class
    // call would bypass it and the invite would still be uncommitted when the mail goes out.
    private val selfProvider: Provider<AccountOnboardingService>,
    @Value("\${secman.account-onboarding.max-accounts-per-run:200}")
    private val maxAccountsPerRun: Int,
    @Value("\${secman.account-onboarding.invite-expiry-days:14}")
    private val defaultExpiryDays: Int,
    @Value("\${secman.account-onboarding.welcome-template:account-welcome}")
    private val welcomeTemplate: String,
    @Value("\${secman.account-onboarding.questionnaire-template:account-onboarding-questionnaire}")
    private val questionnaireTemplate: String,
    @Value("\${secman.account-onboarding.reminder-template:account-onboarding-reminder}")
    private val reminderTemplate: String,
    @Value("\${secman.account-onboarding.reminder-days-before:3}")
    private val reminderDaysBefore: Int
) {
    private val log = LoggerFactory.getLogger(AccountOnboardingService::class.java)

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private const val QUESTIONNAIRE_PATH = "/onboarding/"
        private const val PORTAL_PATH = "/"

        /**
         * Ceiling on how many choices one submission may carry, across all questions.
         *
         * The endpoint is unauthenticated, so the body is untrusted input: without a bound, a
         * caller holding one valid token could post an arbitrarily large answer array and make
         * the server do arbitrarily much work resolving it.
         */
        const val MAX_TOTAL_SELECTIONS = 200
    }

    /**
     * The resolved intent of one onboarding run — what every surface reduces to before this
     * service does anything, so CLI, REST and MCP cannot diverge in interpretation.
     */
    data class OnboardingPlan(
        val mode: AccountOnboardingMode,
        val sendWelcomeEmail: Boolean,
        /** Required for [AccountOnboardingMode.DIRECT], ignored otherwise. */
        val useCaseName: String? = null,
        val deadlineDays: Int = AwsAccountRiskAssessmentService.DEFAULT_DEADLINE_DAYS,
        val expiryDays: Int = AccountOnboardingInvite.DEFAULT_EXPIRY_DAYS,
        /** True when minted by the simulate surface; stamps the invite and the mail. */
        val simulated: Boolean = false,
        /** Who to name in a simulated mail, so the recipient knows it is a test. */
        val simulatedBy: String? = null
    )

    /** One onboarding run's results, split so the legacy `riskAssessments` list keeps its meaning. */
    data class OnboardingOutcome(
        val onboarding: List<AccountOnboardingInfo>,
        val riskAssessments: List<AccountRiskAssessmentInfo>
    )

    // --- Planning and validation -------------------------------------------

    /**
     * Build a plan from the raw request fields, applying the backward-compatibility rules in
     * one place.
     *
     * @return the plan, or null when nothing was requested at all (no mode, no legacy flag) —
     *         which is an ordinary import and must stay one.
     */
    open fun planFrom(
        explicitMode: AccountOnboardingMode?,
        startRiskAssessment: Boolean,
        sendWelcomeEmail: Boolean?,
        useCaseName: String?,
        deadlineDays: Int?,
        expiryDays: Int?,
        simulated: Boolean = false,
        simulatedBy: String? = null
    ): OnboardingPlan? {
        val mode = AccountOnboardingMode.resolve(explicitMode, startRiskAssessment) ?: return null
        return OnboardingPlan(
            mode = mode,
            // The compatibility carve-out: a caller sending only startRiskAssessment=true gets
            // exactly what it got before onboarding modes existed, welcome mail included (i.e.
            // absent). Naming a mode is the opt-in.
            sendWelcomeEmail = sendWelcomeEmail ?: (explicitMode != null),
            useCaseName = useCaseName?.trim(),
            deadlineDays = deadlineDays ?: AwsAccountRiskAssessmentService.DEFAULT_DEADLINE_DAYS,
            expiryDays = expiryDays ?: defaultExpiryDays,
            simulated = simulated,
            simulatedBy = simulatedBy
        )
    }

    /**
     * Fail-fast validation, run before anything is imported or mailed. Returns a
     * human-readable message (HTTP 400 / MCP `VALIDATION_ERROR`), or null when valid.
     *
     * Everything checkable up front is checked up front. Being told after the import committed
     * that no ACTIVE release exists is exactly the failure mode this prevents.
     */
    open fun validateRequest(plan: OnboardingPlan, newAccountCount: Int = 0): String? {
        if (plan.expiryDays < AccountOnboardingInvite.MIN_EXPIRY_DAYS ||
            plan.expiryDays > AccountOnboardingInvite.MAX_EXPIRY_DAYS
        ) {
            return "questionnaireExpiryDays must be between ${AccountOnboardingInvite.MIN_EXPIRY_DAYS} " +
                "and ${AccountOnboardingInvite.MAX_EXPIRY_DAYS} (got ${plan.expiryDays})"
        }
        // GUIDED sends two mails per pair; an import introducing hundreds of accounts would
        // otherwise fire a mail storm nobody asked for. Refuse rather than throttle: the
        // operator can split the file or raise the limit deliberately.
        if (newAccountCount > maxAccountsPerRun) {
            return "This import would onboard $newAccountCount account/owner pairs, over the limit " +
                "of $maxAccountsPerRun (secman.account-onboarding.max-accounts-per-run)"
        }
        return when (plan.mode) {
            AccountOnboardingMode.WELCOME_ONLY -> null

            AccountOnboardingMode.DIRECT ->
                awsAccountRiskAssessmentService.validateStartRequest(plan.useCaseName, plan.deadlineDays)

            AccountOnboardingMode.GUIDED -> {
                // Which use cases apply is what the owner has not answered yet, so it cannot be
                // checked here. The environment can be: an assessor, an ACTIVE release, and a
                // rule set capable of resolving to something.
                awsAccountRiskAssessmentService.validateAssessmentPrerequisites(plan.deadlineDays)?.let { return it }
                if (questionRepository.countByActiveTrue() == 0L) {
                    return "No active onboarding questions are configured - configure them under " +
                        "Account Onboarding before using onboardingMode=GUIDED"
                }
                if (!ruleMatcher.hasUsableRules()) {
                    return "No active onboarding rule resolves to a use case - an owner answering the " +
                        "questionnaire could not be given an assessment"
                }
                null
            }
        }
    }

    // --- Running -----------------------------------------------------------

    /**
     * Onboard every (new account, owner email) pair.
     *
     * Called after the mapping import has committed, so nothing here can roll back persisted
     * mappings — same contract as [AwsAccountRiskAssessmentService.startAssessmentsForNewAccounts].
     *
     * @param dryRun when true nothing is persisted, no mail is sent, and **no token is minted**.
     *        The report describes what would have happened.
     */
    open fun onboardNewAccounts(
        newAccounts: List<NewAccountImportInfo>,
        plan: OnboardingPlan,
        requestorUserId: Long?,
        dryRun: Boolean
    ): OnboardingOutcome {
        if (newAccounts.isEmpty()) return OnboardingOutcome(emptyList(), emptyList())

        val requestor = requestorUserId?.let { userRepository.findById(it).orElse(null) }
        val onboarding = mutableListOf<AccountOnboardingInfo>()
        var riskAssessments: List<AccountRiskAssessmentInfo> = emptyList()

        // DIRECT delegates wholesale to the existing service so its behaviour — round-robin
        // assessor, release pinning, idempotency guard, the exact start mail — is reused, not
        // reimplemented. That is what keeps the legacy path byte-identical.
        if (plan.mode == AccountOnboardingMode.DIRECT && !dryRun) {
            riskAssessments = awsAccountRiskAssessmentService.startAssessmentsForNewAccounts(
                newAccounts = newAccounts,
                useCaseName = plan.useCaseName!!,
                deadlineDays = plan.deadlineDays,
                requestorUserId = requestorUserId
            )
        }
        val assessmentByPair = riskAssessments.associateBy { it.awsAccountId to it.ownerEmail.lowercase() }

        for (account in newAccounts) {
            for (ownerEmail in account.emails) {
                onboarding += onboardOnePair(
                    awsAccountId = account.awsAccountId,
                    ownerEmail = ownerEmail,
                    plan = plan,
                    requestor = requestor,
                    dryRun = dryRun,
                    directResult = assessmentByPair[account.awsAccountId to ownerEmail.lowercase()]
                )
            }
        }

        log.info(
            "Account onboarding complete: mode={}, pairs={}, dryRun={}, invites={}, welcomeMails={}, failures={}",
            plan.mode, onboarding.size, dryRun,
            onboarding.count { it.questionnaireInviteId != null },
            onboarding.count { it.welcomeEmailSent },
            onboarding.count { it.error != null }
        )
        return OnboardingOutcome(onboarding, riskAssessments)
    }

    private fun onboardOnePair(
        awsAccountId: String,
        ownerEmail: String,
        plan: OnboardingPlan,
        requestor: User?,
        dryRun: Boolean,
        directResult: AccountRiskAssessmentInfo?
    ): AccountOnboardingInfo {
        val base = AccountOnboardingInfo(
            awsAccountId = awsAccountId,
            ownerEmail = ownerEmail,
            mode = plan.mode.name,
            dryRun = dryRun
        )

        // A malformed address cannot be mailed and must not reach InternetAddress.parse.
        // Reported per pair rather than aborting the run: one bad row should not deny the rest.
        if (!EmailAddressValidator.isValidRecipient(ownerEmail)) {
            return base.copy(error = "Owner address is not a valid single recipient")
        }

        return try {
            when (plan.mode) {
                AccountOnboardingMode.WELCOME_ONLY -> {
                    val sent = if (dryRun) false else sendWelcomeEmail(awsAccountId, ownerEmail, plan)
                    base.copy(welcomeEmailSent = sent)
                }

                AccountOnboardingMode.DIRECT -> {
                    // The assessment itself was created (or skipped) by the delegated call
                    // above; here we only add the welcome mail and mirror the outcome.
                    val sent = if (dryRun || !plan.sendWelcomeEmail) false
                    else sendWelcomeEmail(awsAccountId, ownerEmail, plan)
                    base.copy(
                        welcomeEmailSent = sent,
                        riskAssessmentId = directResult?.riskAssessmentId,
                        skipped = directResult?.skipped ?: false,
                        skipReason = directResult?.skipReason,
                        error = directResult?.error
                    )
                }

                AccountOnboardingMode.GUIDED -> onboardGuided(awsAccountId, ownerEmail, plan, requestor, dryRun, base)
            }
        } catch (e: Exception) {
            log.error(
                "Onboarding failed for AWS account {} / owner {}: {}",
                awsAccountId, EmailAddressValidator.sanitizeForEcho(ownerEmail), e.message, e
            )
            base.copy(error = "Onboarding failed: ${e.message}")
        }
    }

    private fun onboardGuided(
        awsAccountId: String,
        ownerEmail: String,
        plan: OnboardingPlan,
        requestor: User?,
        dryRun: Boolean,
        base: AccountOnboardingInfo
    ): AccountOnboardingInfo {
        // Idempotency, mirroring the assessment guard: a live invite for this pair means the
        // owner already holds a working link. Minting a second would let two assessments be
        // created for one account and would mail them twice.
        val existing = inviteRepository
            .findByAwsAccountIdAndOwnerEmailIgnoreCaseAndStatus(awsAccountId, ownerEmail, InviteStatus.PENDING)
            .firstOrNull { it.isUsable() }
        if (existing != null) {
            return base.copy(
                skipped = true,
                questionnaireInviteId = existing.id,
                questionnaireExpiresAt = existing.expiresAt.format(DATE_TIME_FORMAT),
                skipReason = "a pending questionnaire invite (id=${existing.id}, expires " +
                    "${existing.expiresAt.format(DATE_TIME_FORMAT)}) already exists for this account/owner"
            )
        }
        // The same guard the assessment path applies: an open assessment means this pair is
        // already being asked, just through the other mode. An id projection, not entities —
        // this method runs outside a transaction and must not walk a lazy association.
        val openAssessmentId = trackingRepository.findOpenAssessmentIds(awsAccountId, ownerEmail).firstOrNull()
        if (openAssessmentId != null) {
            return base.copy(
                skipped = true,
                riskAssessmentId = openAssessmentId,
                skipReason = "an open risk assessment (id=$openAssessmentId) already exists for this account/owner"
            )
        }

        if (dryRun) {
            // Deliberately no token: a dry run that minted one would leave a live credential
            // behind and, worse, print it into the run log.
            return base.copy(
                questionnaireExpiresAt = LocalDateTime.now().plusDays(plan.expiryDays.toLong())
                    .format(DATE_TIME_FORMAT)
            )
        }

        val invite = selfProvider.get().createInvite(awsAccountId, ownerEmail, plan, requestor)
        // Mail only now, after createInvite's own transaction committed.
        val sentWelcome = if (plan.sendWelcomeEmail) sendWelcomeEmail(awsAccountId, ownerEmail, plan) else false
        sendQuestionnaireEmail(invite, plan)

        return base.copy(
            welcomeEmailSent = sentWelcome,
            questionnaireInviteId = invite.id,
            questionnaireExpiresAt = invite.expiresAt.format(DATE_TIME_FORMAT)
        )
    }

    /**
     * Persist the invite in its own short transaction, so the caller can mail the link only
     * once the row is durable. REQUIRES_NEW and reached via [selfProvider] for the same reason
     * [AwsAccountRiskAssessmentService.createAssessment] is.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun createInvite(
        awsAccountId: String,
        ownerEmail: String,
        plan: OnboardingPlan,
        requestor: User?
    ): AccountOnboardingInvite {
        val invite = AccountOnboardingInvite(
            token = AccountOnboardingInvite.generateToken(),
            awsAccountId = awsAccountId,
            ownerEmail = ownerEmail,
            expiresAt = LocalDateTime.now().plusDays(plan.expiryDays.toLong()),
            deadlineDays = plan.deadlineDays,
            simulated = plan.simulated,
            requestor = requestor
        )
        val saved = inviteRepository.save(invite)
        log.info(
            "AUDIT: operation=ACCOUNT_ONBOARDING_INVITE_CREATED, actor={}, awsAccountId={}, ownerEmail={}, " +
                "inviteId={}, token={}, expiresAt={}, simulated={}, outcome=SUCCESS",
            requestor?.username ?: "system", awsAccountId,
            EmailAddressValidator.sanitizeForEcho(ownerEmail), saved.id,
            // Never the full token: a log line carrying one lets whoever reads the log create
            // a risk assessment as this account's owner.
            AccountOnboardingInvite.redact(saved.token), saved.expiresAt, plan.simulated
        )
        return saved
    }

    // --- Mail --------------------------------------------------------------

    /** @return true when SMTP accepted the message. Best-effort: a failure never undoes a persisted row. */
    private fun sendWelcomeEmail(awsAccountId: String, ownerEmail: String, plan: OnboardingPlan): Boolean =
        try {
            val release = releaseRequirementScopeService.findActiveRelease()
            val values = mapOf(
                "awsAccountId" to awsAccountId,
                "ownerEmail" to ownerEmail,
                "portalUrl" to absoluteUrl(PORTAL_PATH),
                "requirementsVersion" to (release?.let { "${it.version} (${it.name})" } ?: ""),
                "simulatedBy" to (plan.simulatedBy ?: "")
            )
            sendTemplated(
                to = ownerEmail,
                subject = "Welcome - your AWS account $awsAccountId is registered in SecMan",
                basename = templateRenderer.requireAllowed(welcomeTemplate),
                values = values,
                hasVersion = release != null,
                simulated = plan.simulated
            )
        } catch (e: Exception) {
            log.warn("Welcome mail to {} failed: {}", EmailAddressValidator.sanitizeForEcho(ownerEmail), e.message)
            false
        }

    private fun sendQuestionnaireEmail(invite: AccountOnboardingInvite, plan: OnboardingPlan): Boolean =
        try {
            val release = releaseRequirementScopeService.findActiveRelease()
            val values = mapOf(
                "awsAccountId" to invite.awsAccountId,
                "questionnaireUrl" to absoluteUrl(QUESTIONNAIRE_PATH + invite.token),
                "expiresAt" to invite.expiresAt.format(DATE_TIME_FORMAT),
                "expiryDays" to plan.expiryDays.toString(),
                "deadlineDays" to plan.deadlineDays.toString(),
                "requirementsVersion" to (release?.let { "${it.version} (${it.name})" } ?: ""),
                "simulatedBy" to (plan.simulatedBy ?: "")
            )
            sendTemplated(
                to = invite.ownerEmail,
                subject = "Action required: tell us how you use AWS account ${invite.awsAccountId}",
                basename = templateRenderer.requireAllowed(questionnaireTemplate),
                values = values,
                hasVersion = release != null,
                simulated = plan.simulated
            )
        } catch (e: Exception) {
            log.warn(
                "Questionnaire mail for invite {} failed: {}",
                AccountOnboardingInvite.redact(invite.token), e.message
            )
            false
        }

    /** Render both parts of a template pair and send. Values are HTML-escaped in the HTML part only. */
    internal fun sendTemplated(
        to: String,
        subject: String,
        basename: String,
        values: Map<String, String>,
        hasVersion: Boolean,
        simulated: Boolean
    ): Boolean {
        fun prepare(raw: String): String =
            templateRenderer.renderConditionalBlock(
                templateRenderer.renderConditionalBlock(raw, "ifVersion", hasVersion),
                "ifSimulated", simulated
            )

        return emailService.sendEmailWithInlineImages(
            to = to,
            subject = subject,
            textContent = templateRenderer.render(prepare(templateRenderer.readText(basename)), values, escape = false),
            htmlContent = templateRenderer.render(prepare(templateRenderer.readHtml(basename)), values, escape = true),
            inlineImages = templateRenderer.loadLogoInlineImage()
        ).get()
    }

    /**
     * Absolute link into the app.
     *
     * Sourced from `backend.baseUrl` for the same reason
     * [AwsAccountRiskAssessmentService.assessmentUrl] is: in real deployments nginx fronts
     * both API and UI on one host (`SECMAN_BACKEND_URL`), whereas `frontend.baseUrl` defaults
     * to localhost and has no env override.
     */
    private fun absoluteUrl(path: String): String =
        appConfig.backend.baseUrl.trimEnd('/') + path

    // --- The owner's submission --------------------------------------------

    /** Outcome of a questionnaire submission. Shapes map 1:1 onto the public endpoint's responses. */
    sealed class SubmissionResult {
        /** The assessment exists. */
        data class Created(
            val riskAssessmentId: Long?,
            val useCases: List<String>,
            val requirementCount: Int,
            val deadline: String,
            val matchedRules: List<String>
        ) : SubmissionResult()

        /**
         * The token is unusable — unknown, malformed, expired, already used or cancelled.
         *
         * Deliberately one case for all five. The caller renders one message for all of them,
         * so the endpoint cannot be used to tell an existing token from a guessed one.
         */
        object NotUsable : SubmissionResult()

        /** The answers themselves were malformed. The caller already proved they hold a valid token. */
        data class InvalidAnswers(val message: String) : SubmissionResult()

        /**
         * The answers were recorded but resolve to nothing usable. Not a client error to hide:
         * the owner did their part and a human has to finish it.
         */
        data class Unresolved(
            val failure: AccountOnboardingRuleMatcher.ResolutionFailure,
            val matchedRules: List<String>
        ) : SubmissionResult()
    }

    /** The questionnaire as the owner sees it, or null when the token is unusable. */
    open fun questionnaireFor(token: String): AccountOnboardingInvite? =
        inviteRepository.findByToken(token).orElse(null)?.takeIf { it.isUsable() }

    /**
     * Record the owner's answers and, if they resolve, create the risk assessment.
     *
     * Order is load-bearing and worth reading as a sequence:
     *
     * 1. Load and validate the answers — cheap, and a malformed submission should not consume
     *    the single-use token.
     * 2. Resolve the rules and confirm the questionnaire would not be empty. A submission that
     *    resolves to nothing **does not claim** the token: the answers are kept, the link keeps
     *    working, and an admin can add the missing rule.
     * 3. **Claim** the token — a guarded UPDATE, so of two concurrent submissions exactly one
     *    proceeds.
     * 4. Only then create the assessment, and only then mail.
     *
     * Claiming before creating is the whole single-use control. Creating first and marking used
     * afterwards would let a double-submit create two assessments.
     */
    open fun submitAnswers(token: String, answers: List<Pair<String, List<String>>>): SubmissionResult {
        val invite = inviteRepository.findByToken(token).orElse(null)
            ?: return SubmissionResult.NotUsable
        if (!invite.isUsable()) return SubmissionResult.NotUsable

        val questions = questionRepository.findActiveWithChoices()
        val selection = resolveSelection(questions, answers)
            ?: return SubmissionResult.InvalidAnswers("Answers reference an unknown question or choice")
        validateSelection(questions, selection)?.let { return SubmissionResult.InvalidAnswers(it) }

        val resolution = ruleMatcher.resolve(selection.values.flatten().mapNotNull { it }.toSet())
        val answersJson = serializeAnswers(answers)

        if (!resolution.isUsable) {
            // Keep the answers. Discarding them would make the owner redo the work once the
            // rule set is fixed, and the link would be spent for nothing.
            selfProvider.get().recordUnresolved(invite.id!!, answersJson, resolution.matchedRuleNames)
            log.warn(
                "AUDIT: operation=ACCOUNT_ONBOARDING_SUBMIT, token={}, awsAccountId={}, outcome={}",
                AccountOnboardingInvite.redact(token), invite.awsAccountId,
                resolution.failure ?: "UNRESOLVED"
            )
            return SubmissionResult.Unresolved(
                resolution.failure ?: AccountOnboardingRuleMatcher.ResolutionFailure.NO_RULE_MATCHED,
                resolution.matchedRuleNames
            )
        }

        val activeRelease = releaseRequirementScopeService.findActiveRelease()
            ?: return SubmissionResult.Unresolved(
                AccountOnboardingRuleMatcher.ResolutionFailure.NO_ACTIVE_RELEASE, resolution.matchedRuleNames
            )
        val assessor = awsAccountRiskAssessmentService.pickAssessor(invite.id!!.toInt())
            ?: return SubmissionResult.Unresolved(
                AccountOnboardingRuleMatcher.ResolutionFailure.NO_ACTIVE_RELEASE, resolution.matchedRuleNames
            )

        // Single use, decided by the database. 0 rows means someone else won the race — answer
        // exactly as an unknown token would.
        val claimed = inviteRepository.claim(token, LocalDateTime.now(), InviteStatus.PENDING, InviteStatus.SUBMITTED)
        if (claimed != 1) {
            log.info(
                "Onboarding submission lost the claim race for token {} (account {})",
                AccountOnboardingInvite.redact(token), invite.awsAccountId
            )
            return SubmissionResult.NotUsable
        }

        val endDate = deadlineFrom(invite.deadlineDays)
        val info = try {
            awsAccountRiskAssessmentService.createAssessment(
                awsAccountId = invite.awsAccountId,
                ownerEmail = invite.ownerEmail,
                useCases = resolution.useCases,
                endDate = endDate,
                assessor = assessor,
                requestor = invite.requestor ?: assessor,
                activeRelease = activeRelease,
                requirementCount = resolution.requirementCount
            )
        } catch (e: Exception) {
            // Give the link back rather than stranding the owner with a spent token and no
            // assessment. Best-effort, exactly like the reminder claim release.
            log.error(
                "Assessment creation failed after claiming invite {}: {}",
                invite.id, e.message, e
            )
            inviteRepository.releaseClaim(
                invite.id!!, LocalDateTime.now(), InviteStatus.PENDING, InviteStatus.SUBMITTED
            )
            throw e
        }

        selfProvider.get().recordSubmission(
            invite.id!!, answersJson, resolution.matchedRuleNames, info.riskAssessmentId
        )

        log.info(
            "AUDIT: operation=ACCOUNT_ONBOARDING_SUBMIT, token={}, awsAccountId={}, inviteId={}, " +
                "rules={}, useCases={}, riskAssessmentId={}, outcome=SUCCESS",
            AccountOnboardingInvite.redact(token), invite.awsAccountId, invite.id,
            resolution.matchedRuleNames, resolution.useCases.map { it.name }, info.riskAssessmentId
        )

        // After the assessment committed, never before — the same rule the import path follows.
        if (info.error == null && !info.skipped && info.riskAssessmentId != null) {
            try {
                awsAccountRiskAssessmentService.notifyAssessmentStarted(
                    invite.ownerEmail, invite.awsAccountId,
                    resolution.useCases.map { it.name }.sorted().joinToString(", "),
                    endDate, assessor, activeRelease, info.riskAssessmentId
                )
            } catch (e: Exception) {
                log.warn(
                    "Assessment {} created from invite {}, but the owner notification failed: {}",
                    info.riskAssessmentId, invite.id, e.message
                )
            }
        }

        return SubmissionResult.Created(
            riskAssessmentId = info.riskAssessmentId,
            useCases = resolution.useCases.map { it.name }.sorted(),
            requirementCount = resolution.requirementCount,
            deadline = formatDate(endDate),
            matchedRules = resolution.matchedRuleNames
        )
    }

    /**
     * Map submitted `(questionKey, choiceKeys)` onto choice ids.
     *
     * @return question id → chosen choice ids, or null when anything referenced does not exist.
     *         Unknown keys are refused rather than ignored: silently dropping one would resolve
     *         the submission against a different combination than the owner answered.
     */
    private fun resolveSelection(
        questions: List<AccountOnboardingQuestion>,
        answers: List<Pair<String, List<String>>>
    ): Map<Long, List<Long?>>? {
        val byKey = questions.associateBy { it.questionKey.lowercase() }
        val selection = mutableMapOf<Long, List<Long?>>()
        for ((questionKey, choiceKeys) in answers) {
            val question = byKey[questionKey.trim().lowercase()] ?: return null
            val choicesByKey = question.choices.filter { it.active }.associateBy { it.choiceKey.lowercase() }
            val ids = choiceKeys.map { key -> choicesByKey[key.trim().lowercase()]?.id ?: return null }
            selection[question.id!!] = ids
        }
        return selection
    }

    /** Cardinality and completeness, in the owner's vocabulary. Returns a message or null. */
    private fun validateSelection(
        questions: List<AccountOnboardingQuestion>,
        selection: Map<Long, List<Long?>>
    ): String? {
        val totalSelected = selection.values.sumOf { it.size }
        if (totalSelected > MAX_TOTAL_SELECTIONS) {
            return "Too many selections (max $MAX_TOTAL_SELECTIONS)"
        }
        for (question in questions) {
            val chosen = selection[question.id] ?: emptyList()
            if (question.required && chosen.isEmpty()) {
                return "Question '${question.label}' must be answered"
            }
            if (!question.inputType.allowsMultiple() && chosen.size > 1) {
                return "Question '${question.label}' accepts only one answer"
            }
        }
        return null
    }

    /** Persist a submission that produced an assessment. Its own transaction; called post-claim. */
    @Transactional
    open fun recordSubmission(inviteId: Long, answersJson: String?, matchedRules: List<String>, assessmentId: Long?) {
        val invite = inviteRepository.findById(inviteId).orElse(null) ?: return
        invite.answersJson = answersJson
        invite.resolvedRules = matchedRules.joinToString(", ").take(1024)
        invite.status = InviteStatus.SUBMITTED
        if (assessmentId != null) {
            invite.riskAssessment = riskAssessmentRef(assessmentId)
        }
        inviteRepository.update(invite)
    }

    /** Persist answers that resolved to nothing. The invite stays PENDING — the link still works. */
    @Transactional
    open fun recordUnresolved(inviteId: Long, answersJson: String?, matchedRules: List<String>) {
        val invite = inviteRepository.findById(inviteId).orElse(null) ?: return
        invite.answersJson = answersJson
        invite.resolvedRules = matchedRules.joinToString(", ").take(1024)
        inviteRepository.update(invite)
    }

    private fun riskAssessmentRef(assessmentId: Long) =
        riskAssessmentRepository.findById(assessmentId).orElse(null)

    // --- Invite reminders and expiry ---------------------------------------

    /**
     * Nudge owners whose questionnaire link is about to lapse, then mark lapsed links expired.
     *
     * **Nothing is ever auto-created.** An owner who does not answer gets one reminder and then
     * their link expires, surfacing as pending work for a security champion. Falling back to
     * some default use case would be worse than doing nothing: an assessment scoped by a guess
     * looks authoritative and is not.
     *
     * Idempotent across restarts *and* across concurrent runs: each nudge is claimed by an
     * atomic guarded UPDATE committed per row, exactly as
     * [AwsAccountRiskAssessmentService.processDeadlineReminders] claims its deadline reminders.
     * A failed send releases the claim so the next run retries.
     *
     * Deliberately NOT `@Transactional`: the claims must commit per row to be visible to a
     * concurrent run, and a method-wide transaction rolling back after mail went out would
     * un-stamp reminders that were actually sent.
     *
     * @return number of reminder emails sent
     */
    open fun processInviteReminders(now: LocalDateTime = LocalDateTime.now()): Int {
        // Expire first, so a link that lapsed since the last run is never nudged.
        val expired = inviteRepository.expireLapsed(now, InviteStatus.PENDING, InviteStatus.EXPIRED)
        if (expired > 0) {
            log.info("Marked {} onboarding invite(s) EXPIRED", expired)
        }

        val windowEnd = now.plusDays(reminderDaysBefore.toLong())
        val pending = inviteRepository.findPendingReminders(now, windowEnd, InviteStatus.PENDING)
        if (pending.isEmpty()) return 0

        var sent = 0
        for (invite in pending) {
            try {
                val claimedAt = LocalDateTime.now()
                if (inviteRepository.claimReminder(invite.id!!, claimedAt) != 1) continue

                if (sendInviteReminder(invite, now)) {
                    sent++
                } else {
                    log.warn("Invite reminder send failed for invite {} - releasing claim for retry", invite.id)
                    inviteRepository.releaseReminderClaim(invite.id!!, claimedAt, LocalDateTime.now())
                }
            } catch (e: Exception) {
                log.error("Failed to send invite reminder for invite {}: {}", invite.id, e.message, e)
            }
        }
        log.info("Onboarding invite reminder run completed: {} reminder(s) sent", sent)
        return sent
    }

    private fun sendInviteReminder(invite: AccountOnboardingInvite, now: LocalDateTime): Boolean =
        try {
            val daysLeft = java.time.Duration.between(now, invite.expiresAt).toDays().coerceAtLeast(0)
            val dayWord = if (daysLeft == 1L) "1 day" else "$daysLeft days"
            val release = releaseRequirementScopeService.findActiveRelease()
            sendTemplated(
                to = invite.ownerEmail,
                subject = "Reminder: $dayWord left to tell us about AWS account ${invite.awsAccountId}",
                basename = templateRenderer.requireAllowed(reminderTemplate),
                values = mapOf(
                    "awsAccountId" to invite.awsAccountId,
                    "questionnaireUrl" to absoluteUrl(QUESTIONNAIRE_PATH + invite.token),
                    "expiresAt" to invite.expiresAt.format(DATE_TIME_FORMAT),
                    "daysLeft" to dayWord,
                    "requirementsVersion" to (release?.let { "${it.version} (${it.name})" } ?: ""),
                    "simulatedBy" to ""
                ),
                hasVersion = release != null,
                simulated = invite.simulated
            )
        } catch (e: Exception) {
            log.error("Invite reminder email for invite {} failed: {}", invite.id, e.message)
            false
        }

    // --- Reporting ---------------------------------------------------------

    /**
     * The rule set as a dry run and the CLI/MCP report render it.
     *
     * This is what a GUIDED dry run prints *instead of* minting a token: the operator sees
     * every combination that will be honoured, and which use cases each resolves to, before
     * an owner is ever mailed.
     */
    open fun describeRules(): OnboardingRuleMatrix {
        val questions: List<AccountOnboardingQuestion> = questionRepository.findActiveWithChoices()
        val rules = ruleRepository.findByActiveTrueOrderByPriorityOrderAscIdAsc()
        val reachable = ruleMatcher.reachableUseCases()
        val release = releaseRequirementScopeService.findActiveRelease()
        val reachableRequirementCount = release?.let {
            releaseRequirementScopeService.requirementsForRelease(it.id!!, reachable.mapNotNull { uc -> uc.id }).size
        } ?: 0

        return OnboardingRuleMatrix(
            questionCount = questions.size,
            choiceCount = questions.sumOf { q -> q.choices.count { it.active } },
            activeRuleCount = rules.size,
            hasDefaultRule = rules.any { it.isDefault },
            rules = rules.map { toSummary(it) },
            reachableUseCases = reachable.map { it.name }.sorted(),
            reachableRequirementCount = reachableRequirementCount,
            releaseVersion = release?.version
        )
    }

    internal fun toSummary(rule: com.secman.domain.AccountOnboardingRule): OnboardingRuleSummary =
        OnboardingRuleSummary(
            id = rule.id,
            name = rule.name,
            description = rule.description,
            isDefault = rule.isDefault,
            active = rule.active,
            combination = rule.choices
                .map { "${it.question.questionKey}=${it.choiceKey}" }
                .sorted(),
            useCases = rule.useCases.map { it.name }.sorted()
        )

    /** Serialize the owner's answers for the audit trail. Never fails the submission. */
    internal fun serializeAnswers(answers: List<Pair<String, List<String>>>): String? =
        try {
            objectMapper.writeValueAsString(
                answers.map { (questionKey, choiceKeys) ->
                    mapOf("questionKey" to questionKey, "choiceKeys" to choiceKeys)
                }
            )
        } catch (e: Exception) {
            log.warn("Could not serialize onboarding answers: {}", e.message)
            null
        }

    /** Today plus the plan's deadline, clamped exactly as the assessment service clamps it. */
    internal fun deadlineFrom(deadlineDays: Int): LocalDate =
        LocalDate.now().plusDays(
            deadlineDays.coerceIn(1, AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS).toLong()
        )

    internal fun formatDate(date: LocalDate): String = date.format(DATE_FORMAT)
}
