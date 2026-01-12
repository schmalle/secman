package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.ReviewExceptionRequestDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.exception.ConcurrentApprovalException
import com.secman.service.VulnerabilityExceptionRequestService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for rejecting pending exception requests.
 * Feature: 062-mcp-vuln-exceptions
 *
 * Access Control:
 * - Requires User Delegation
 * - ADMIN or SECCHAMPION role required
 * - Rejection comment is REQUIRED (minimum 10 characters)
 * - Uses optimistic locking for concurrent review handling
 *
 * Spec reference: spec.md FR-018, FR-019, FR-020
 * User Story: US6 - Reject Exception Request (P2)
 */
@Singleton
class RejectExceptionRequestTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "reject_exception_request"
    override val description = "Reject a pending exception request (ADMIN/SECCHAMPION role required, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("requestId", "comment"),
        "properties" to mapOf(
            "requestId" to mapOf(
                "type" to "number",
                "description" to "ID of the exception request to reject"
            ),
            "comment" to mapOf(
                "type" to "string",
                "description" to "Required rejection reason (10-1024 characters)",
                "minLength" to 10,
                "maxLength" to 1024
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation
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
                "ADMIN or SECCHAMPION role required to reject requests"
            )
        }

        try {
            // Parse requestId
            val requestId = (arguments["requestId"] as? Number)?.toLong()
                ?: return McpToolResult.error("VALIDATION_ERROR", "requestId is required")

            // Parse and validate comment (REQUIRED for rejection)
            val comment = arguments["comment"] as? String
            if (comment.isNullOrBlank()) {
                return McpToolResult.error("VALIDATION_ERROR", "Rejection comment is required")
            }
            if (comment.length < 10) {
                return McpToolResult.error("VALIDATION_ERROR", "Rejection comment must be at least 10 characters")
            }
            if (comment.length > 1024) {
                return McpToolResult.error("VALIDATION_ERROR", "Rejection comment must not exceed 1024 characters")
            }

            // Create review DTO
            val reviewDto = ReviewExceptionRequestDto(reviewComment = comment)

            // Call service
            val result = exceptionRequestService.rejectRequest(
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
                    "message" to "Exception request rejected"
                )
            )

        } catch (e: ConcurrentApprovalException) {
            // Concurrent modification handling
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
            // Invalid state transition
            return McpToolResult.error("INVALID_STATE", e.message ?: "Invalid state transition")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to reject request: ${e.message}")
        }
    }
}
