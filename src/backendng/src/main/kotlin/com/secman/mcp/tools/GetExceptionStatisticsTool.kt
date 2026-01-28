package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ExceptionRequestStatisticsService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for retrieving exception request statistics.
 *
 * Returns exception request metrics including:
 * - Approval rate percentage
 * - Average (median) approval time in hours
 * - Request counts by status (PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED)
 * - Top requesters with counts
 * - Top CVEs with counts
 *
 * Feature: 069-mcp-lense-reports
 * Task: T018
 * User Story: US4 - Exception Statistics (P3)
 * Spec reference: FR-004, FR-005, FR-006, FR-007
 *
 * Access Control:
 * - Uses VULNERABILITIES_READ permission
 * - Statistics are aggregated across all requests (no row-level filtering)
 */
@Singleton
class GetExceptionStatisticsTool(
    @Inject private val exceptionRequestStatisticsService: ExceptionRequestStatisticsService
) : McpTool {

    override val name = "get_exception_statistics"
    override val description = "Retrieve exception request statistics including approval rate, average approval time, requests by status, top requesters, and top CVEs"
    override val operation = McpOperation.READ

    companion object {
        private val VALID_DATE_RANGES = listOf("7days", "30days", "90days", "alltime")
    }

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "date_range" to mapOf(
                "type" to "string",
                "description" to "Date range filter: 7days, 30days, 90days, or alltime (default: 30days)",
                "enum" to VALID_DATE_RANGES
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Extract and validate date range filter
        val dateRange = arguments["date_range"] as? String ?: "30days"
        if (dateRange !in VALID_DATE_RANGES) {
            return McpToolResult.error(
                "INVALID_DATE_RANGE",
                "Invalid date range: '$dateRange'. Must be one of: ${VALID_DATE_RANGES.joinToString(", ")}"
            )
        }

        try {
            // Get all statistics from service
            val approvalRate = exceptionRequestStatisticsService.getApprovalRate(dateRange)
            val averageApprovalTime = exceptionRequestStatisticsService.getAverageApprovalTime(dateRange)
            val requestsByStatus = exceptionRequestStatisticsService.getRequestsByStatus(dateRange)
            val topRequesters = exceptionRequestStatisticsService.getTopRequesters(10, dateRange)
            val topCVEs = exceptionRequestStatisticsService.getTopCVEs(10, dateRange)

            // Map to response format
            val result = mapOf(
                "dateRange" to dateRange,
                "approvalRate" to approvalRate,
                "averageApprovalTimeHours" to averageApprovalTime,
                "requestsByStatus" to requestsByStatus.map { (status, count) ->
                    mapOf(
                        "status" to status.name,
                        "count" to count
                    )
                },
                "topRequesters" to topRequesters.map { (username, count) ->
                    mapOf(
                        "username" to username,
                        "requestCount" to count
                    )
                },
                "topCVEs" to topCVEs.map { (cveId, count) ->
                    mapOf(
                        "cveId" to cveId,
                        "requestCount" to count
                    )
                }
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve exception statistics: ${e.message}")
        }
    }
}
