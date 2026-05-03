package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityExceptionRequestService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool to permanently delete an exception request owned by the delegated user.
 *
 * Mirrors DELETE /api/vulnerability-exception-requests/{id}/delete.
 *
 * Behaviour:
 * - For APPROVED requests, the associated VulnerabilityException is removed first
 *   (so the CVE is no longer excepted), then the request row itself is deleted.
 * - For other statuses, only the request row is deleted.
 * - Pending count is republished if the deleted request was PENDING.
 *
 * Access Control:
 * - Requires User Delegation
 * - Only the original requester can delete the request (enforced by service)
 */
@Singleton
class DeleteExceptionRequestTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "delete_exception_request"
    override val description = "Permanently delete one of your own exception requests; for APPROVED requests this also removes the underlying exception (requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("requestId"),
        "properties" to mapOf(
            "requestId" to mapOf(
                "type" to "number",
                "description" to "ID of the exception request to delete"
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

        try {
            val requestId = (arguments["requestId"] as? Number)?.toLong()
                ?: return McpToolResult.error("VALIDATION_ERROR", "requestId is required")

            exceptionRequestService.deleteRequest(
                requestId = requestId,
                requesterUserId = context.delegatedUserId!!,
                clientIp = null
            )

            return McpToolResult.success(
                mapOf(
                    "requestId" to requestId,
                    "message" to "Exception request deleted successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: "Validation failed"
            return when {
                message.contains("not found") -> McpToolResult.error("NOT_FOUND", message)
                message.contains("original requester") -> McpToolResult.error("FORBIDDEN", message)
                else -> McpToolResult.error("VALIDATION_ERROR", message)
            }
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to delete request: ${e.message}")
        }
    }
}
