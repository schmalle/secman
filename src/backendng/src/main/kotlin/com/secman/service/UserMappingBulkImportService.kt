package com.secman.service

import com.secman.domain.AccountOnboardingMode
import com.secman.dto.BulkUserMappingRequest
import com.secman.dto.BulkUserMappingResponse
import com.secman.dto.WorkgroupAccountLinkSummary
import com.secman.util.EmailAddressValidator
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * The one implementation of "bulk import user mappings, then do the post-commit work".
 *
 * Both entry points use it, so they can never drift:
 * - REST  `POST /api/user-mappings/bulk` ([com.secman.controller.UserMappingController])
 * - MCP   `import_user_mappings` ([com.secman.mcp.tools.ImportUserMappingsTool])
 *
 * Ordering is load-bearing. The operator notification and the risk assessments run
 * only AFTER [UserMappingService.bulkCreateMappings] has committed, so a slow or
 * failing email — or a failure while starting an assessment — can never roll back
 * mappings that were already persisted.
 */
@Singleton
open class UserMappingBulkImportService(
    private val userMappingService: UserMappingService,
    private val newAccountNotificationService: NewAccountNotificationService,
    private val accountOnboardingService: AccountOnboardingService,
    private val importCompletionNotifier: ImportCompletionNotifier,
    private val workgroupAccountLinkService: WorkgroupAccountLinkService
) {
    private val logger = LoggerFactory.getLogger(UserMappingBulkImportService::class.java)

    /**
     * Fail-fast validation of the request's side-effect options. Returns a
     * human-readable message (HTTP 400 / MCP `VALIDATION_ERROR`), or null when valid.
     *
     * Deliberately runs before any import so an operator with a bad notify address,
     * an unknown use case, or no ACTIVE requirements release is told about it
     * instead of getting a half-done import.
     */
    open fun validate(request: BulkUserMappingRequest): String? {
        if (request.notifyNewAccounts) {
            // notifyAddress is handed to InternetAddress.parse, where a comma would silently
            // split one recipient into two and a CR/LF would reach a mail header. One shared
            // boundary check — see EmailAddressValidator for what it rejects and why.
            if (!EmailAddressValidator.isValidRecipient(request.notifyAddress)) {
                return "notifyAddress must be a valid email when notifyNewAccounts is true"
            }
        }
        // Reject the one combination that cannot be honoured rather than guessing which half
        // the caller meant.
        AccountOnboardingMode.validateCompatibility(request.onboardingMode, request.startRiskAssessment)
            ?.let { return it }

        // planFrom returns null only when neither the mode nor the legacy flag was set, which
        // is an ordinary import with no side effects to validate.
        val plan = accountOnboardingService.planFrom(
            explicitMode = request.onboardingMode,
            startRiskAssessment = request.startRiskAssessment,
            sendWelcomeEmail = request.sendWelcomeEmail,
            useCaseName = request.riskAssessmentUseCase,
            deadlineDays = request.riskAssessmentDeadlineDays,
            expiryDays = request.questionnaireExpiryDays
        ) ?: return null

        return accountOnboardingService.validateRequest(plan)
    }

    /**
     * Import the mappings, then run the post-commit side effects.
     *
     * @param requestorUserId the ADMIN who triggered the import; becomes the
     *        requestor of any risk assessment started here.
     */
    open fun execute(
        request: BulkUserMappingRequest,
        requestorUserId: Long?,
        source: String = "Bulk import"
    ): BulkUserMappingResponse {
        val result = userMappingService.bulkCreateMappings(request)

        // Link every account whose entry carried a display name to the workgroup named
        // after it (`aws-<display_name>`), creating that workgroup when it is missing.
        // AFTER the import committed, like every other side effect below: a workgroup
        // that cannot be created must not roll back mappings that already persisted.
        val workgroupLinks = linkWorkgroups(request, requestorUserId)

        // Send the operator email AFTER the transaction has committed, so a
        // slow/failed send never rolls back the persisted mappings.
        var finalResult = if (request.notifyNewAccounts && !request.dryRun && result.newAccounts.isNotEmpty()) {
            val recipient = request.notifyAddress!!.trim()
            val sent = newAccountNotificationService.sendImportNotification(recipient, result.newAccounts)
            result.copy(
                notificationRecipient = recipient,
                notificationSent = sent,
                notificationError = if (sent) null else "Email send failed (check email configuration / logs)"
            )
        } else {
            result
        }
        finalResult = finalResult.copy(workgroupLinks = workgroupLinks)

        // Onboard the owners of brand-new AWS accounts — welcome mail, and depending on the
        // mode an immediate assessment or a questionnaire invite. Also AFTER the import
        // committed so a failure here never rolls back the persisted mappings.
        //
        // Unlike the two side effects above this runs on a dry run too: it persists and sends
        // nothing, but reports what it *would* do, which is the point of asking for a preview.
        val plan = accountOnboardingService.planFrom(
            explicitMode = request.onboardingMode,
            startRiskAssessment = request.startRiskAssessment,
            sendWelcomeEmail = request.sendWelcomeEmail,
            useCaseName = request.riskAssessmentUseCase,
            deadlineDays = request.riskAssessmentDeadlineDays,
            expiryDays = request.questionnaireExpiryDays
        )
        if (plan != null && result.newAccounts.isNotEmpty()) {
            val outcome = accountOnboardingService.onboardNewAccounts(
                newAccounts = result.newAccounts,
                plan = plan,
                requestorUserId = requestorUserId,
                dryRun = request.dryRun
            )
            finalResult = finalResult.copy(
                riskAssessments = outcome.riskAssessments,
                onboarding = outcome.onboarding
            )
        }

        logger.info(
            "Bulk import complete: processed={}, created={}, pending={}, skipped={}, newAccounts={}, " +
                "assessments={}, onboarded={}, workgroupsCreated={}, accountsLinked={}",
            finalResult.totalProcessed, finalResult.created, finalResult.createdPending,
            finalResult.skipped, finalResult.newAccounts.size, finalResult.riskAssessments.size,
            finalResult.onboarding.size, workgroupLinks?.workgroupsCreated ?: 0,
            workgroupLinks?.linked ?: 0
        )

        // Chat fan-out (Slack/Telegram), last and best-effort: a dry run changed nothing worth announcing,
        // and like the two side effects above this runs only after the import committed.
        if (!request.dryRun) {
            importCompletionNotifier.awsAccountImportCompleted(
                source = source,
                triggeredBy = null,
                processed = finalResult.totalProcessed,
                imported = finalResult.created + finalResult.createdPending,
                skipped = finalResult.skipped,
                errorCount = finalResult.errors.size,
                newAccountIds = finalResult.newAccounts.map { it.awsAccountId }
            )
        }

        return finalResult
    }

    /**
     * Derive the (account, display name) pairs from the request and hand them to the one
     * implementation of the naming rule.
     *
     * Returns null when the file carried no display name at all, which is what keeps
     * every pre-existing caller byte-identical. Never throws: the mappings are already
     * committed by the time this runs, so a linking failure is reported, not raised.
     */
    private fun linkWorkgroups(
        request: BulkUserMappingRequest,
        requestorUserId: Long?
    ): WorkgroupAccountLinkSummary? {
        val pairs = request.mappings.mapNotNull { entry ->
            val accountId = entry.awsAccountId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val displayName = entry.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            WorkgroupAccountLinkService.AccountDisplayName(accountId, displayName)
        }
        if (pairs.isEmpty()) return null

        return try {
            workgroupAccountLinkService.link(pairs, requestorUserId, request.dryRun)
        } catch (e: Exception) {
            logger.error("Workgroup linking failed after a successful mapping import", e)
            WorkgroupAccountLinkSummary(
                processed = 0,
                failed = pairs.size,
                dryRun = request.dryRun,
                links = emptyList()
            )
        }
    }
}
