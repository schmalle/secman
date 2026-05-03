package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ExceptionRequestStatisticsService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool returning analytics for vulnerability exception requests.
 *
 * Mirrors GET /api/vulnerability-exception-requests/statistics.
 *
 * Access Control:
 * - Requires User Delegation
 * - ADMIN or SECCHAMPION role required
 */
@Singleton
class GetExceptionRequestStatisticsTool(
    @Inject private val statisticsService: ExceptionRequestStatisticsService
) : McpTool {

    override val name = "get_exception_request_statistics"
    override val description = "Get exception-request analytics: approval rate, median approval time, status counts, top requesters/CVEs (ADMIN/SECCHAMPION, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "dateRange" to mapOf(
                "type" to "string",
                "enum" to listOf("7days", "30days", "90days", "alltime"),
                "description" to "Date range to aggregate over (default: 30days)"
            ),
            "topLimit" to mapOf(
                "type" to "number",
                "description" to "Maximum number of top requesters/CVEs to return (default: 10, max: 50)"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        val hasApprovalRole = context.isAdmin ||
            context.delegatedUserRoles?.contains("SECCHAMPION") == true
        if (!hasApprovalRole) {
            return McpToolResult.error(
                "APPROVAL_ROLE_REQUIRED",
                "ADMIN or SECCHAMPION role required to view exception statistics"
            )
        }

        try {
            val dateRange = (arguments["dateRange"] as? String) ?: "30days"
            if (dateRange !in listOf("7days", "30days", "90days", "alltime")) {
                return McpToolResult.error(
                    "VALIDATION_ERROR",
                    "Invalid dateRange. Must be one of: 7days, 30days, 90days, alltime"
                )
            }
            val topLimit = ((arguments["topLimit"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)

            val approvalRate = statisticsService.getApprovalRate(dateRange)
            val avgApprovalTime = statisticsService.getAverageApprovalTime(dateRange)
            val byStatus = statisticsService.getRequestsByStatus(dateRange)
            val topRequesters = statisticsService.getTopRequesters(topLimit, dateRange)
            val topCves = statisticsService.getTopCVEs(topLimit, dateRange)
            val totalRequests = byStatus.values.sum()

            return McpToolResult.success(
                mapOf(
                    "dateRange" to dateRange,
                    "totalRequests" to totalRequests,
                    "approvalRatePercent" to approvalRate,
                    "averageApprovalTimeHours" to avgApprovalTime,
                    "requestsByStatus" to byStatus.mapKeys { it.key.name },
                    "topRequesters" to topRequesters.map { (username, count) ->
                        mapOf("username" to username, "count" to count)
                    },
                    "topCVEs" to topCves.map { (cveId, count) ->
                        mapOf("cveId" to cveId, "count" to count)
                    }
                )
            )
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to fetch statistics: ${e.message}")
        }
    }
}
