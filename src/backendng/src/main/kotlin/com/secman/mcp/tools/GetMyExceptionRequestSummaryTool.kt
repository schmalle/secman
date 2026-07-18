package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityExceptionRequestService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool returning the delegated user's exception-request counts by status.
 *
 * Mirrors GET /api/vulnerability-exception-requests/my/summary.
 *
 * Access Control:
 * - Requires User Delegation
 * - Any authenticated user gets a summary scoped to their own requests
 */
@Singleton
class GetMyExceptionRequestSummaryTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "get_my_exception_request_summary"
    override val description = "Get summary counts of your own exception requests by status (requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        try {
            val summary = exceptionRequestService.getUserRequestSummary(context.delegatedUserId!!)
            return McpToolResult.success(
                mapOf(
                    "totalRequests" to summary.totalRequests,
                    "approvedCount" to summary.approvedCount,
                    "pendingCount" to summary.pendingCount,
                    "rejectedCount" to summary.rejectedCount,
                    "expiredCount" to summary.expiredCount,
                    "cancelledCount" to summary.cancelledCount
                )
            )
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to fetch summary: ${e.message}")
        }
    }
}
