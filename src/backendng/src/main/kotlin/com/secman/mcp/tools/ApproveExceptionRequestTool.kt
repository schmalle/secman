package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.ReviewExceptionRequestDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.exception.ConcurrentApprovalException
import com.secman.service.VulnerabilityExceptionRequestService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for approving pending exception requests.
 * Feature: 062-mcp-vuln-exceptions
 *
 * Access Control:
 * - Requires User Delegation
 * - ADMIN or SECCHAMPION role required
 * - Uses optimistic locking for concurrent approval handling
 *
 * Spec reference: spec.md FR-017, FR-020, FR-021
 * User Story: US5 - Approve Exception Request (P2)
 */
@Singleton
class ApproveExceptionRequestTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "approve_exception_request"
    override val description = "Approve a pending exception request (ADMIN/SECCHAMPION role required, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("requestId"),
        "properties" to mapOf(
            "requestId" to mapOf(
                "type" to "number",
                "description" to "ID of the exception request to approve"
            ),
            "comment" to mapOf(
                "type" to "string",
                "description" to "Optional approval comment (max 1024 characters)",
                "maxLength" to 1024
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // FR-017: Require User Delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN or SECCHAMPION role
        val hasApprovalRole = context.isAdmin || context.delegatedUserRoles?.contains("SECCHAMPION") == true
        if (!hasApprovalRole) {
            return McpToolResult.error(
                "APPROVAL_ROLE_REQUIRED",
                "ADMIN or SECCHAMPION role required to approve requests"
            )
        }

        try {
            // Parse requestId
            val requestId = (arguments["requestId"] as? Number)?.toLong()
                ?: return McpToolResult.error("VALIDATION_ERROR", "requestId is required")

            // Parse optional comment
            val comment = arguments["comment"] as? String
            if (comment != null && comment.length > 1024) {
                return McpToolResult.error("VALIDATION_ERROR", "Comment must not exceed 1024 characters")
            }

            // Create review DTO
            val reviewDto = if (comment != null) ReviewExceptionRequestDto(reviewComment = comment) else null

            // Call service
            val result = exceptionRequestService.approveRequest(
                requestId = requestId,
                reviewerUserId = context.delegatedUserId!!,
                reviewDto = reviewDto,
                clientIp = null
            )

            return McpToolResult.success(
                mapOf(
                    "request" to mapOf(
                        "id" to result.id,
                        "vulnerabilityId" to result.vulnerabilityId,
                        "vulnerabilityCve" to result.vulnerabilityCve,
                        "assetName" to result.assetName,
                        "assetIp" to result.assetIp,
                        "requestedByUsername" to result.requestedByUsername,
                        "scope" to result.scope.name,
                        "reason" to result.reason,
                        "expirationDate" to result.expirationDate.toString(),
                        "status" to result.status.name,
                        "autoApproved" to result.autoApproved,
                        "reviewedByUsername" to result.reviewedByUsername,
                        "reviewDate" to result.reviewDate?.toString(),
                        "reviewComment" to result.reviewComment,
                        "createdAt" to result.createdAt.toString(),
                        "updatedAt" to result.updatedAt.toString()
                    ),
                    "message" to "Exception request approved successfully"
                )
            )

        } catch (e: ConcurrentApprovalException) {
            // FR-020: Concurrent approval handling
            return McpToolResult.error(
                "CONCURRENT_MODIFICATION",
                "Request was already reviewed by ${e.reviewedBy} at ${e.reviewedAt}"
            )
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: "Validation failed"
            return when {
                message.contains("not found") -> McpToolResult.error("NOT_FOUND", message)
                else -> McpToolResult.error("VALIDATION_ERROR", message)
            }
        } catch (e: IllegalStateException) {
            // FR-021: Invalid state transition
            return McpToolResult.error("INVALID_STATE", e.message ?: "Invalid state transition")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to approve request: ${e.message}")
        }
    }
}
