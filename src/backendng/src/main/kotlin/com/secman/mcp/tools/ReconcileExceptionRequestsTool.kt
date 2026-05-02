package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityExceptionRequestService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool to rebuild missing VulnerabilityException rows for APPROVED exception
 * requests. Mirrors POST /api/vulnerability-exception-requests/reconcile.
 *
 * Access Control:
 * - Requires User Delegation
 * - ADMIN role required (operator maintenance only)
 */
@Singleton
class ReconcileExceptionRequestsTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "reconcile_exception_requests"
    override val description = "Rebuild missing VulnerabilityException rows for APPROVED requests (ADMIN-only operator maintenance, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_ROLE_REQUIRED",
                "ADMIN role required to reconcile exception requests"
            )
        }

        try {
            val report = exceptionRequestService.reconcileApprovedRequests()
            return McpToolResult.success(
                mapOf(
                    "scanned" to report.scanned,
                    "alreadyConsistent" to report.alreadyConsistent,
                    "repaired" to report.repaired,
                    "failed" to report.failed,
                    "failureReasons" to report.failureReasons.mapKeys { it.key.toString() }
                )
            )
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Reconcile failed: ${e.message}")
        }
    }
}
