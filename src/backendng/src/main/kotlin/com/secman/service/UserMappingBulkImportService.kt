package com.secman.service

import com.secman.dto.BulkUserMappingRequest
import com.secman.dto.BulkUserMappingResponse
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
    private val awsAccountRiskAssessmentService: AwsAccountRiskAssessmentService,
    private val importCompletionNotifier: ImportCompletionNotifier
) {
    private val logger = LoggerFactory.getLogger(UserMappingBulkImportService::class.java)
    private val emailRegex = Regex("^[^@]+@[^@]+\\.[^@]+$")

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
            val addr = request.notifyAddress?.trim()
            if (addr.isNullOrBlank() || !emailRegex.matches(addr)) {
                return "notifyAddress must be a valid email when notifyNewAccounts is true"
            }
        }
        if (request.startRiskAssessment) {
            awsAccountRiskAssessmentService.validateStartRequest(
                request.riskAssessmentUseCase,
                request.riskAssessmentDeadlineDays
            )?.let { return it }
        }
        return null
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

        // Auto-start risk assessments for owners of brand-new AWS accounts, also
        // AFTER the import committed so a failure here never rolls back the
        // persisted mappings.
        if (request.startRiskAssessment && !request.dryRun && result.newAccounts.isNotEmpty()) {
            val assessments = awsAccountRiskAssessmentService.startAssessmentsForNewAccounts(
                newAccounts = result.newAccounts,
                useCaseName = request.riskAssessmentUseCase!!.trim(),
                deadlineDays = request.riskAssessmentDeadlineDays
                    ?: AwsAccountRiskAssessmentService.DEFAULT_DEADLINE_DAYS,
                requestorUserId = requestorUserId
            )
            finalResult = finalResult.copy(riskAssessments = assessments)
        }

        logger.info(
            "Bulk import complete: processed={}, created={}, pending={}, skipped={}, newAccounts={}, assessments={}",
            finalResult.totalProcessed, finalResult.created, finalResult.createdPending,
            finalResult.skipped, finalResult.newAccounts.size, finalResult.riskAssessments.size
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
}
