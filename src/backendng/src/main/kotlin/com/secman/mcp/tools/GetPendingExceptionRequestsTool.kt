package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityExceptionRequestService
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for getting all pending exception requests awaiting approval.
 * Feature: 062-mcp-vuln-exceptions
 *
 * Access Control:
 * - Requires User Delegation
 * - ADMIN or SECCHAMPION role required
 * - Returns all pending requests system-wide
 *
 * Spec reference: spec.md FR-014 through FR-016
 * User Story: US4 - View Pending Requests (P2)
 */
@Singleton
class GetPendingExceptionRequestsTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "get_pending_exception_requests"
    override val description = "Get all pending exception requests awaiting approval (ADMIN/SECCHAMPION role required, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "page" to mapOf(
                "type" to "number",
                "description" to "Page number (0-indexed, default: 0)"
            ),
            "size" to mapOf(
                "type" to "number",
                "description" to "Page size (default: 20, max: 100)"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // FR-014: Require User Delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // FR-015: Require ADMIN or SECCHAMPION role
        val hasApprovalRole = context.isAdmin || context.delegatedUserRoles?.contains("SECCHAMPION") == true
        if (!hasApprovalRole) {
            return McpToolResult.error(
                "APPROVAL_ROLE_REQUIRED",
                "ADMIN or SECCHAMPION role required to view pending requests"
            )
        }

        try {
            // Parse parameters
            val page = (arguments["page"] as? Number)?.toInt() ?: 0
            val size = ((arguments["size"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

            // Create pageable sorted by createdAt ascending (oldest first - FIFO)
            val pageable = Pageable.from(page, size, Sort.of(Sort.Order.asc("createdAt")))

            // Call service
            val result = exceptionRequestService.getPendingRequests(pageable)

            // Get pending count for badge display
            val pendingCount = exceptionRequestService.getPendingCount()

            // Map results to response format
            val requests = result.content.map { request ->
                mapOf(
                    "id" to request.id,
                    "vulnerabilityId" to request.vulnerabilityId,
                    "vulnerabilityCve" to request.vulnerabilityCve,
                    "assetName" to request.assetName,
                    "assetIp" to request.assetIp,
                    "requestedByUsername" to request.requestedByUsername,
                    "scope" to request.scope.name,
                    "reason" to request.reason,
                    "expirationDate" to request.expirationDate.toString(),
                    "status" to request.status.name,
                    "autoApproved" to request.autoApproved,
                    "createdAt" to request.createdAt.toString(),
                    "updatedAt" to request.updatedAt.toString()
                )
            }

            return McpToolResult.success(
                mapOf(
                    "requests" to requests,
                    "totalElements" to result.totalSize,
                    "totalPages" to result.totalPages,
                    "page" to page,
                    "size" to size,
                    "pendingCount" to pendingCount
                )
            )

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve pending requests: ${e.message}")
        }
    }
}
