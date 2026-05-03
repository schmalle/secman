package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import com.secman.service.VulnerabilityExceptionRequestService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for fetching a single vulnerability exception request by its ID.
 *
 * Mirrors GET /api/vulnerability-exception-requests/{id}.
 *
 * Access Control:
 * - Requires User Delegation
 * - Requesters can view their own requests
 * - ADMIN or SECCHAMPION can view any request
 */
@Singleton
class GetExceptionRequestTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService,
    @Inject private val userRepository: UserRepository
) : McpTool {

    override val name = "get_exception_request"
    override val description = "Get a vulnerability exception request by ID (requires User Delegation; requester or ADMIN/SECCHAMPION)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("requestId"),
        "properties" to mapOf(
            "requestId" to mapOf(
                "type" to "number",
                "description" to "ID of the exception request to fetch"
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

            val request = exceptionRequestService.getRequestById(requestId)

            // Access control: owner or ADMIN/SECCHAMPION
            val isAdminOrSecChampion = context.isAdmin ||
                context.delegatedUserRoles?.contains("SECCHAMPION") == true
            val username = userRepository.findById(context.delegatedUserId!!).orElse(null)?.username
            val isOwner = username != null && request.requestedByUsername == username
            if (!isOwner && !isAdminOrSecChampion) {
                return McpToolResult.error(
                    "FORBIDDEN",
                    "You can only view your own exception requests"
                )
            }

            return McpToolResult.success(
                mapOf(
                    "request" to mapOf(
                        "id" to request.id,
                        "vulnerabilityId" to request.vulnerabilityId,
                        "vulnerabilityCve" to request.vulnerabilityCve,
                        "assetName" to request.assetName,
                        "assetIp" to request.assetIp,
                        "requestedByUsername" to request.requestedByUsername,
                        "subject" to request.subject.name,
                        "scope" to request.scope.name,
                        "subjectValue" to request.subjectValue,
                        "scopeValue" to request.scopeValue,
                        "assetId" to request.assetId,
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
                )
            )

        } catch (e: IllegalArgumentException) {
            val message = e.message ?: "Validation failed"
            return when {
                message.contains("not found") -> McpToolResult.error("NOT_FOUND", message)
                else -> McpToolResult.error("VALIDATION_ERROR", message)
            }
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to fetch request: ${e.message}")
        }
    }
}
