package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AwsAccountRiskAssessmentRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter

/**
 * MCP tool listing the risk assessments that were auto-started for the owners of
 * brand-new AWS accounts during a user-mapping import.
 *
 * Read-only counterpart to `import_user_mappings --startRiskAssessment`: it is how
 * an operator (or an E2E test) confirms what an import actually produced, including
 * which version of the security requirements each assessment is pinned to.
 *
 * ADMIN role is required via User Delegation. Only import-triggered assessments are
 * tracked, so manually created risk assessments never appear here.
 */
@Singleton
class ListAwsAccountRiskAssessmentsTool(
    @Inject private val trackingRepository: AwsAccountRiskAssessmentRepository
) : McpTool {

    private val log = LoggerFactory.getLogger(ListAwsAccountRiskAssessmentsTool::class.java)

    override val name = "list_aws_account_risk_assessments"
    override val description =
        "List risk assessments auto-started for owners of newly discovered AWS accounts " +
            "(ADMIN only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "awsAccountId" to mapOf(
                "type" to "string",
                "description" to "Filter by AWS account ID (12 digits)"
            ),
            "ownerEmail" to mapOf(
                "type" to "string",
                "description" to "Filter by the mapped owner email (case-insensitive)"
            ),
            "status" to mapOf(
                "type" to "string",
                "description" to "Filter by risk assessment status, e.g. STARTED or COMPLETED"
            ),
            "limit" to mapOf(
                "type" to "number",
                "description" to "Maximum rows to return (1-$MAX_LIMIT). Default: $DEFAULT_LIMIT",
                "minimum" to 1,
                "maximum" to MAX_LIMIT
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to list AWS account risk assessments"
            )
        }

        val limit = (arguments["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT
        if (limit < 1 || limit > MAX_LIMIT) {
            return McpToolResult.error("INVALID_ARGUMENT", "limit must be between 1 and $MAX_LIMIT")
        }

        val awsAccountId = (arguments["awsAccountId"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val ownerEmail = (arguments["ownerEmail"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val status = (arguments["status"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        return try {
            val rows = trackingRepository.findByFilters(awsAccountId, ownerEmail, status).take(limit)

            McpToolResult.success(
                mapOf(
                    "assessments" to rows.map { tracking ->
                        val assessment = tracking.riskAssessment
                        mapOf(
                            "riskAssessmentId" to assessment.id,
                            "awsAccountId" to tracking.awsAccountId,
                            "ownerEmail" to tracking.ownerEmail,
                            "useCase" to tracking.useCaseName,
                            // The "standard" the assessment is measured against. Null for
                            // assessments started before release pinning was introduced.
                            "releaseVersion" to assessment.lockedRelease?.version,
                            "releaseName" to assessment.lockedRelease?.name,
                            "assessor" to assessment.assessor.let { it.email.ifBlank { it.username } },
                            "respondent" to assessment.respondent?.let { it.email.ifBlank { it.username } },
                            "startDate" to assessment.startDate.format(DATE_FORMAT),
                            "endDate" to assessment.endDate.format(DATE_FORMAT),
                            "status" to assessment.status,
                            "reminderTwoDaysSentAt" to tracking.reminderTwoDaysSentAt?.toString(),
                            "reminderOneDaySentAt" to tracking.reminderOneDaySentAt?.toString(),
                            "createdAt" to tracking.createdAt?.toString()
                        )
                    },
                    "count" to rows.size
                )
            )
        } catch (e: Exception) {
            log.error("MCP list_aws_account_risk_assessments failed", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to list risk assessments: ${e.message}")
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
