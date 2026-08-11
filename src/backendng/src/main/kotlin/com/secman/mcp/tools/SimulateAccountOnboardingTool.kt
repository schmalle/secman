package com.secman.mcp.tools

import com.secman.domain.AccountOnboardingInvite
import com.secman.domain.AccountOnboardingMode
import com.secman.domain.McpOperation
import com.secman.dto.NewAccountImportInfo
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import com.secman.service.AccountOnboardingRateLimiter
import com.secman.service.AccountOnboardingService
import com.secman.service.AwsAccountRiskAssessmentService
import com.secman.util.EmailAddressValidator
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Run the full account-onboarding path against a made-up AWS account id and email address.
 *
 * This is the surface that makes the feature testable without waiting for a real account to
 * show up in a mapping import — and it is not a mock: it calls exactly what a real import
 * calls ([AccountOnboardingService.onboardNewAccounts]), so what it exercises is production
 * behaviour, not a parallel path.
 *
 * That is also why it is guarded like a write tool rather than a test helper. A live run
 * really does send mail to the address given, so:
 * - ADMIN or SECCHAMPION via delegation ([requireAnyRole]),
 * - `awsAccountId` must be exactly 12 digits and `ownerEmail` must pass the same
 *   anti-header-injection boundary every other recipient does,
 * - rate limited per delegated user, with a looser bucket for dry runs,
 * - every simulated mail says so and names the actor, which removes nearly all the phishing
 *   value of an arbitrary-recipient sender,
 * - the invite is stamped `simulated` and the run is audited with actor, target and outcome.
 *
 * With `dryRun` nothing is persisted, no mail is sent and **no token is minted**.
 */
@Singleton
class SimulateAccountOnboardingTool(
    @Inject private val onboardingService: AccountOnboardingService,
    @Inject private val userRepository: UserRepository,
    @Inject private val rateLimiter: AccountOnboardingRateLimiter
) : McpTool {

    private val log = LoggerFactory.getLogger(SimulateAccountOnboardingTool::class.java)

    override val name = "simulate_account_onboarding"
    override val description =
        "Run account onboarding (welcome mail, direct or guided risk assessment) against a test " +
            "AWS account id and email address (ADMIN or SECCHAMPION, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "awsAccountId" to mapOf(
                "type" to "string",
                "description" to "The AWS account id to onboard, exactly 12 digits. May be fictitious."
            ),
            "ownerEmail" to mapOf(
                "type" to "string",
                "description" to "Where the mail goes. A live run really sends to this address."
            ),
            "mode" to mapOf(
                "type" to "string",
                "description" to "WELCOME_ONLY sends a welcome mail only. DIRECT also starts a risk " +
                    "assessment for riskAssessmentUseCase. GUIDED mails a one-time questionnaire link " +
                    "and creates the assessment from the answers.",
                "enum" to listOf("WELCOME_ONLY", "DIRECT", "GUIDED")
            ),
            "riskAssessmentUseCase" to mapOf(
                "type" to "string",
                "description" to "Use case the assessment is scoped to (required for mode=DIRECT)"
            ),
            "riskAssessmentDeadlineDays" to mapOf(
                "type" to "number",
                "description" to "Days until the assessment deadline. Default 7.",
                "minimum" to 1,
                "maximum" to AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS
            ),
            "questionnaireExpiryDays" to mapOf(
                "type" to "number",
                "description" to "Days the GUIDED link stays valid. Default 14.",
                "minimum" to AccountOnboardingInvite.MIN_EXPIRY_DAYS,
                "maximum" to AccountOnboardingInvite.MAX_EXPIRY_DAYS
            ),
            "sendWelcomeEmail" to mapOf(
                "type" to "boolean",
                "description" to "Override the welcome mail. Defaults to true."
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Report what would happen without persisting, sending or minting a token",
                "default" to false
            )
        ),
        "required" to listOf("awsAccountId", "ownerEmail", "mode")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context, "ADMIN", "SECCHAMPION",
            code = "FORBIDDEN",
            message = "ADMIN or SECCHAMPION role required to simulate account onboarding"
        )?.let { return it }

        val awsAccountId = (arguments["awsAccountId"] as? String)?.trim().orEmpty()
        if (!ACCOUNT_ID_PATTERN.matches(awsAccountId)) {
            return McpToolResult.error("VALIDATION_ERROR", "awsAccountId must be exactly 12 digits")
        }
        val ownerEmail = (arguments["ownerEmail"] as? String)?.trim().orEmpty()
        if (!EmailAddressValidator.isValidRecipient(ownerEmail)) {
            return McpToolResult.error("VALIDATION_ERROR", "ownerEmail must be a valid single email address")
        }
        val modeRaw = (arguments["mode"] as? String)?.trim().orEmpty()
        val mode = runCatching { AccountOnboardingMode.valueOf(modeRaw.uppercase()) }.getOrNull()
            ?: return McpToolResult.error(
                "VALIDATION_ERROR",
                "mode must be one of ${AccountOnboardingMode.entries.joinToString(", ") { it.name }}"
            )

        val dryRun = arguments["dryRun"] as? Boolean ?: false
        val actorKey = context.delegatedUserEmail ?: "mcp-admin-key"
        val bucket = if (dryRun) AccountOnboardingRateLimiter.Bucket.SIMULATE_DRY
        else AccountOnboardingRateLimiter.Bucket.SIMULATE
        if (!rateLimiter.tryAcquire(bucket, actorKey)) {
            return McpToolResult.error(
                "RATE_LIMITED",
                "Too many simulations. Try again later, or pass dryRun=true."
            )
        }

        val actor = context.delegatedUserEmail?.let { userRepository.findByEmailIgnoreCase(it).orElse(null) }
        val plan = onboardingService.planFrom(
            explicitMode = mode,
            startRiskAssessment = false,
            sendWelcomeEmail = arguments["sendWelcomeEmail"] as? Boolean,
            useCaseName = (arguments["riskAssessmentUseCase"] as? String)?.trim()?.takeIf { it.isNotBlank() },
            deadlineDays = (arguments["riskAssessmentDeadlineDays"] as? Number)?.toInt(),
            expiryDays = (arguments["questionnaireExpiryDays"] as? Number)?.toInt(),
            simulated = true,
            simulatedBy = actor?.email?.ifBlank { actor.username } ?: actorKey
        ) ?: return McpToolResult.error("VALIDATION_ERROR", "mode is required")

        onboardingService.validateRequest(plan, newAccountCount = 1)?.let {
            return McpToolResult.error("VALIDATION_ERROR", it)
        }

        return try {
            val outcome = onboardingService.onboardNewAccounts(
                newAccounts = listOf(NewAccountImportInfo(awsAccountId, listOf(ownerEmail))),
                plan = plan,
                requestorUserId = actor?.id,
                dryRun = dryRun
            )

            log.info(
                "AUDIT: operation=MCP_SIMULATE_ACCOUNT_ONBOARDING, actor={}, awsAccountId={}, ownerEmail={}, " +
                    "mode={}, dryRun={}, outcome={}",
                actorKey, awsAccountId, EmailAddressValidator.sanitizeForEcho(ownerEmail), mode, dryRun,
                if (outcome.onboarding.any { it.error != null }) "PARTIAL" else "SUCCESS"
            )

            McpToolResult.success(
                mapOf(
                    "awsAccountId" to awsAccountId,
                    "ownerEmail" to ownerEmail,
                    "mode" to mode.name,
                    "dryRun" to dryRun,
                    "onboarding" to outcome.onboarding.map { ob ->
                        mapOf(
                            "awsAccountId" to ob.awsAccountId,
                            "ownerEmail" to ob.ownerEmail,
                            "mode" to ob.mode,
                            "welcomeEmailSent" to ob.welcomeEmailSent,
                            // The invite id, never the token: an MCP result travels into an
                            // agent transcript, which is not a place for a live credential.
                            "questionnaireInviteId" to ob.questionnaireInviteId,
                            "questionnaireExpiresAt" to ob.questionnaireExpiresAt,
                            "riskAssessmentId" to ob.riskAssessmentId,
                            "dryRun" to ob.dryRun,
                            "skipped" to ob.skipped,
                            "skipReason" to ob.skipReason,
                            "error" to ob.error
                        )
                    },
                    "riskAssessments" to outcome.riskAssessments.map { ra ->
                        mapOf(
                            "awsAccountId" to ra.awsAccountId,
                            "ownerEmail" to ra.ownerEmail,
                            "riskAssessmentId" to ra.riskAssessmentId,
                            "assessor" to ra.assessor,
                            "endDate" to ra.endDate,
                            "useCase" to ra.useCase,
                            "useCases" to ra.useCases,
                            "releaseVersion" to ra.releaseVersion,
                            "requirementCount" to ra.requirementCount,
                            "skipped" to ra.skipped,
                            "skipReason" to ra.skipReason,
                            "error" to ra.error
                        )
                    }
                )
            )
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid request")
        } catch (e: Exception) {
            log.error("MCP simulate_account_onboarding failed", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to simulate account onboarding: ${e.message}")
        }
    }

    companion object {
        private val ACCOUNT_ID_PATTERN = Regex("^\\d{12}$")
    }
}
