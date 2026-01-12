package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityExceptionRequestService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for cancelling the user's own pending exception request.
 * Feature: 062-mcp-vuln-exceptions
 *
 * Access Control:
 * - Requires User Delegation
 * - Any authenticated user can cancel their own requests
 * - Ownership check: only the original requester can cancel
 * - Only PENDING requests can be cancelled (or auto-approved by same user)
 *
 * Spec reference: spec.md FR-022 through FR-024
 * User Story: US7 - Cancel Exception Request (P3)
 */
@Singleton
class CancelExceptionRequestTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "cancel_exception_request"
    override val description = "Cancel your own pending exception request (requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("requestId"),
        "properties" to mapOf(
            "requestId" to mapOf(
                "type" to "number",
                "description" to "ID of the exception request to cancel"
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

        try {
            // Parse requestId
            val requestId = (arguments["requestId"] as? Number)?.toLong()
                ?: return McpToolResult.error("VALIDATION_ERROR", "requestId is required")

            // Call service - ownership check is done inside the service
            val result = exceptionRequestService.cancelRequest(
                requestId = requestId,
                requesterUserId = context.delegatedUserId!!,
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
                        "createdAt" to result.createdAt.toString(),
                        "updatedAt" to result.updatedAt.toString()
                    ),
                    "message" to "Exception request cancelled successfully"
                )
            )

        } catch (e: IllegalArgumentException) {
            val message = e.message ?: "Validation failed"
            return when {
                message.contains("not found") -> McpToolResult.error(
                    "NOT_FOUND",
                    "Exception request with ID not found"
                )
                message.contains("original requester") -> McpToolResult.error(
                    "FORBIDDEN",
                    "Only the original requester can cancel this request"
                )
                else -> McpToolResult.error("VALIDATION_ERROR", message)
            }
        } catch (e: IllegalStateException) {
            // Invalid state transition
            return McpToolResult.error("INVALID_STATE", e.message ?: "Invalid state transition")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to cancel request: ${e.message}")
        }
    }
}
