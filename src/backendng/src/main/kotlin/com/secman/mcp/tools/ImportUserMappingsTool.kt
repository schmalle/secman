package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.BulkUserMappingEntry
import com.secman.dto.BulkUserMappingRequest
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AwsAccountRiskAssessmentService
import com.secman.service.UserMappingBulkImportService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool for bulk importing user mappings.
 * Feature: 064-mcp-cli-user-mapping
 *
 * ADMIN role is required via User Delegation.
 *
 * Delegates to [UserMappingBulkImportService] — the same code path as REST
 * `POST /api/user-mappings/bulk` and CLI `manage-user-mappings import`. That is
 * what gives this tool brand-new-AWS-account detection and the optional
 * auto-started risk assessments; a hand-rolled per-row loop here would silently
 * miss both.
 *
 * Input parameters:
 * - mappings (required): Array of {email (required), awsAccountId, domain}
 * - dryRun: validate without persisting. Default false.
 * - startRiskAssessment: start a risk assessment for the owner of every brand-new
 *   AWS account this import introduces. Requires riskAssessmentUseCase.
 * - riskAssessmentUseCase: name of the use case the assessments are scoped to.
 * - riskAssessmentDeadlineDays: days until the deadline. Default 7, max 3650.
 *
 * Returns totalProcessed, created, createdPending, skipped, errors[], dryRun,
 * newAccounts[] and riskAssessments[]. A riskAssessments[] entry carries either
 * `error` (failed) or `skipped`/`skipReason` (an open assessment already existed —
 * an idempotent no-op, not a failure).
 */
@Singleton
class ImportUserMappingsTool(
    @Inject private val bulkImportService: UserMappingBulkImportService
) : McpTool {

    private val log = LoggerFactory.getLogger(ImportUserMappingsTool::class.java)

    override val name = "import_user_mappings"
    override val description =
        "Bulk import user mappings, optionally starting a risk assessment for the owner of every " +
            "brand-new AWS account (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "mappings" to mapOf(
                "type" to "array",
                "description" to "List of user mapping entries to import",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "email" to mapOf(
                            "type" to "string",
                            "description" to "User email address (required)"
                        ),
                        "awsAccountId" to mapOf(
                            "type" to "string",
                            "description" to "AWS account ID (12 digits, optional)"
                        ),
                        "domain" to mapOf(
                            "type" to "string",
                            "description" to "AD domain (optional)"
                        )
                    ),
                    "required" to listOf("email")
                ),
                "maxItems" to MAX_MAPPINGS
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "If true, validate without creating mappings",
                "default" to false
            ),
            "startRiskAssessment" to mapOf(
                "type" to "boolean",
                "description" to "Start a risk assessment for the owner of every brand-new AWS account " +
                    "introduced by this import. Requires riskAssessmentUseCase. " +
                    "The assessment is pinned to the ACTIVE requirements release.",
                "default" to false
            ),
            "riskAssessmentUseCase" to mapOf(
                "type" to "string",
                "description" to "Name of the use case the auto-started risk assessments are scoped to " +
                    "(required when startRiskAssessment is true)"
            ),
            "riskAssessmentDeadlineDays" to mapOf(
                "type" to "number",
                "description" to "Days from today until the risk assessment deadline. " +
                    "Default: 7, maximum: ${AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS}",
                "minimum" to 1,
                "maximum" to AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS
            )
        ),
        "required" to listOf("mappings")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation
        requireDelegation(context)?.let { return it }

        // Require ADMIN role
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to import user mappings"
            )
        }

        @Suppress("UNCHECKED_CAST")
        val mappings = arguments["mappings"] as? List<Map<String, Any?>>

        if (mappings.isNullOrEmpty()) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "The 'mappings' parameter is required and must be a non-empty array"
            )
        }

        if (mappings.size > MAX_MAPPINGS) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "Maximum $MAX_MAPPINGS mappings allowed per request. Received: ${mappings.size}"
            )
        }

        val request = BulkUserMappingRequest(
            mappings = mappings.map { entry ->
                BulkUserMappingEntry(
                    email = (entry["email"] as? String)?.trim().orEmpty(),
                    awsAccountId = (entry["awsAccountId"] as? String)?.trim()?.takeIf { it.isNotBlank() },
                    domain = (entry["domain"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                )
            },
            dryRun = arguments["dryRun"] as? Boolean ?: false,
            startRiskAssessment = arguments["startRiskAssessment"] as? Boolean ?: false,
            riskAssessmentUseCase = (arguments["riskAssessmentUseCase"] as? String)?.trim()
                ?.takeIf { it.isNotBlank() },
            riskAssessmentDeadlineDays = (arguments["riskAssessmentDeadlineDays"] as? Number)?.toInt()
        )

        bulkImportService.validate(request)?.let { validationError ->
            return McpToolResult.error("VALIDATION_ERROR", validationError)
        }

        return try {
            val result = bulkImportService.execute(request, context.delegatedUserId, "MCP import_user_mappings")

            log.info(
                "AUDIT: operation=MCP_IMPORT_USER_MAPPINGS, actor={}, dryRun={}, " +
                    "processed={}, created={}, pending={}, skipped={}, newAccounts={}, assessments={}",
                context.delegatedUserEmail, request.dryRun, result.totalProcessed, result.created,
                result.createdPending, result.skipped, result.newAccounts.size, result.riskAssessments.size
            )

            McpToolResult.success(
                mapOf(
                    "totalProcessed" to result.totalProcessed,
                    "created" to result.created,
                    "createdPending" to result.createdPending,
                    "skipped" to result.skipped,
                    "errors" to result.errors,
                    "dryRun" to request.dryRun,
                    "newAccounts" to result.newAccounts.map { acct ->
                        mapOf("awsAccountId" to acct.awsAccountId, "emails" to acct.emails)
                    },
                    "riskAssessments" to result.riskAssessments.map { ra ->
                        mapOf(
                            "awsAccountId" to ra.awsAccountId,
                            "ownerEmail" to ra.ownerEmail,
                            "riskAssessmentId" to ra.riskAssessmentId,
                            "assessor" to ra.assessor,
                            "endDate" to ra.endDate,
                            "useCase" to ra.useCase,
                            "releaseVersion" to ra.releaseVersion,
                            "requirementCount" to ra.requirementCount,
                            // A skip is an idempotent no-op, not a failure — kept in its own
                            // field so an agent reading this result does not report it as one.
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
            log.error("MCP import_user_mappings failed", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to import user mappings: ${e.message}")
        }
    }

    companion object {
        private const val MAX_MAPPINGS = 1000
    }
}
