package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.AccountFindingAgeDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AccountFindingAgeService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool: top AWS accounts ranked by the age of their oldest still-open finding.
 *
 * ADMIN role is required via User Delegation. Mirrors the guard order used by
 * SendAdminSummaryTool: delegation first, then the role check.
 */
@Singleton
class GetTopAccountsByFindingAgeTool(
    @Inject private val accountFindingAgeService: AccountFindingAgeService
) : McpTool {

    private val logger = LoggerFactory.getLogger(GetTopAccountsByFindingAgeTool::class.java)

    override val name = "get_top_accounts_by_finding_age"
    override val description =
        "Get the AWS accounts whose oldest still-open vulnerability has been open the longest, " +
            "including the account name, days open, CVE and affected asset (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "limit" to mapOf(
                "type" to "integer",
                "description" to "Number of accounts to return (1-50). Default: 10"
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to read the account finding-age report")
        }

        val limit = (arguments["limit"] as? Number)?.toInt() ?: AccountFindingAgeService.DEFAULT_LIMIT

        return try {
            val rows = accountFindingAgeService.getTopAccountsByOldestFinding(limit)
            logger.info("Account finding-age report via MCP: {} accounts returned", rows.size)

            McpToolResult.success(
                mapOf(
                    "accounts" to rows.map { row ->
                        val dto = AccountFindingAgeDto.from(row)
                        mapOf(
                            "awsAccountId" to dto.awsAccountId,
                            "accountName" to dto.accountName,
                            "oldestFindingFirstSeenAt" to dto.oldestFindingFirstSeenAt,
                            "oldestFindingDaysOpen" to dto.oldestFindingDaysOpen,
                            "oldestFindingCve" to dto.oldestFindingCve,
                            "oldestFindingSeverity" to dto.oldestFindingSeverity,
                            "oldestFindingAssetName" to dto.oldestFindingAssetName,
                            "oldestFindingAssetInstanceId" to dto.oldestFindingAssetInstanceId,
                            "openFindingCount" to dto.openFindingCount,
                            "affectedAssetCount" to dto.affectedAssetCount
                        )
                    },
                    "count" to rows.size
                )
            )
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("INVALID_ARGUMENT", e.message ?: "Invalid limit")
        } catch (e: Exception) {
            logger.error("Failed to build account finding-age report via MCP", e)
            McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve report: ${e.message}")
        }
    }
}
