package com.secman.mcp.tools

import com.secman.domain.ExceptionRequestStatus
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityExceptionRequestService
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for getting the current user's own exception requests.
 * Feature: 062-mcp-vuln-exceptions
 *
 * Access Control:
 * - Requires User Delegation
 * - Any authenticated user can view their own requests
 * - Only returns requests created by the delegated user
 *
 * Spec reference: spec.md FR-011 through FR-013
 * User Story: US3 - View My Exception Requests (P2)
 */
@Singleton
class GetMyExceptionRequestsTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "get_my_exception_requests"
    override val description = "Get your own exception requests (requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "status" to mapOf(
                "type" to "string",
                "enum" to listOf("PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED"),
                "description" to "Filter by request status"
            ),
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
        // FR-011: Require User Delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        try {
            // Parse parameters
            val page = (arguments["page"] as? Number)?.toInt() ?: 0
            val size = ((arguments["size"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

            // Parse optional status filter
            val statusStr = arguments["status"] as? String
            val status = statusStr?.let {
                try {
                    ExceptionRequestStatus.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    return McpToolResult.error(
                        "VALIDATION_ERROR",
                        "Invalid status. Must be one of: PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED"
                    )
                }
            }

            // Create pageable sorted by createdAt descending (newest first)
            val pageable = Pageable.from(page, size, Sort.of(Sort.Order.desc("createdAt")))

            // Call service - filtered by delegated user ID
            val result = exceptionRequestService.getUserRequests(
                userId = context.delegatedUserId!!,
                status = status,
                pageable = pageable
            )

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
                    "reviewedByUsername" to request.reviewedByUsername,
                    "reviewDate" to request.reviewDate?.toString(),
                    "reviewComment" to request.reviewComment,
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
                    "size" to size
                )
            )

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve exception requests: ${e.message}")
        }
    }
}
