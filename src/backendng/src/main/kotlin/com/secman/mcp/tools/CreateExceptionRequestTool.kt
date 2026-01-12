package com.secman.mcp.tools

import com.secman.domain.ExceptionScope
import com.secman.domain.McpOperation
import com.secman.dto.CreateExceptionRequestDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityExceptionRequestService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * MCP tool for creating vulnerability exception requests.
 * Feature: 062-mcp-vuln-exceptions
 *
 * Access Control:
 * - Requires User Delegation
 * - Any authenticated user can create requests
 * - ADMIN/SECCHAMPION requests are auto-approved
 *
 * Spec reference: spec.md FR-006 through FR-010
 * User Story: US2 - Create Exception Request (P1)
 */
@Singleton
class CreateExceptionRequestTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {

    override val name = "create_exception_request"
    override val description = "Create a vulnerability exception request (requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("vulnerabilityId", "reason", "expirationDate"),
        "properties" to mapOf(
            "vulnerabilityId" to mapOf(
                "type" to "number",
                "description" to "ID of the vulnerability to request exception for"
            ),
            "reason" to mapOf(
                "type" to "string",
                "description" to "Business justification (50-2048 characters)",
                "minLength" to 50,
                "maxLength" to 2048
            ),
            "expirationDate" to mapOf(
                "type" to "string",
                "format" to "date-time",
                "description" to "When the exception should expire (ISO-8601, must be future date)"
            ),
            "scope" to mapOf(
                "type" to "string",
                "enum" to listOf("SINGLE_VULNERABILITY", "CVE_PATTERN"),
                "default" to "SINGLE_VULNERABILITY",
                "description" to "Exception scope (default: SINGLE_VULNERABILITY)"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // FR-006: Require User Delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        try {
            // Parse and validate vulnerabilityId
            val vulnerabilityId = (arguments["vulnerabilityId"] as? Number)?.toLong()
                ?: return McpToolResult.error("VALIDATION_ERROR", "vulnerabilityId is required")

            // Parse and validate reason
            val reason = arguments["reason"] as? String
                ?: return McpToolResult.error("VALIDATION_ERROR", "reason is required")

            if (reason.length < 50) {
                return McpToolResult.error("VALIDATION_ERROR", "Reason must be at least 50 characters")
            }
            if (reason.length > 2048) {
                return McpToolResult.error("VALIDATION_ERROR", "Reason must not exceed 2048 characters")
            }

            // Parse and validate expirationDate
            val expirationDateStr = arguments["expirationDate"] as? String
                ?: return McpToolResult.error("VALIDATION_ERROR", "expirationDate is required")

            val expirationDate = try {
                LocalDateTime.parse(expirationDateStr)
            } catch (e: DateTimeParseException) {
                return McpToolResult.error("VALIDATION_ERROR", "Invalid date format. Use ISO-8601 (e.g., 2026-04-11T00:00:00)")
            }

            if (expirationDate.isBefore(LocalDateTime.now())) {
                return McpToolResult.error("VALIDATION_ERROR", "Expiration date must be in the future")
            }

            // Parse scope with default
            val scopeStr = arguments["scope"] as? String ?: "SINGLE_VULNERABILITY"
            val scope = try {
                ExceptionScope.valueOf(scopeStr)
            } catch (e: IllegalArgumentException) {
                return McpToolResult.error("VALIDATION_ERROR", "Invalid scope. Must be SINGLE_VULNERABILITY or CVE_PATTERN")
            }

            // Create DTO
            val dto = CreateExceptionRequestDto(
                vulnerabilityId = vulnerabilityId,
                scope = scope,
                reason = reason,
                expirationDate = expirationDate
            )

            // Call service
            val result = exceptionRequestService.createRequest(
                dto = dto,
                requesterUserId = context.delegatedUserId!!,
                clientIp = null
            )

            // Determine message based on auto-approval
            val message = if (result.autoApproved) {
                "Exception request auto-approved (ADMIN/SECCHAMPION role)"
            } else {
                "Exception request created successfully. Status: PENDING"
            }

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
                        "createdAt" to result.createdAt.toString(),
                        "updatedAt" to result.updatedAt.toString()
                    ),
                    "message" to message
                )
            )

        } catch (e: IllegalArgumentException) {
            // Handle specific validation errors from service
            val message = e.message ?: "Validation failed"
            return when {
                message.contains("not found") -> McpToolResult.error("NOT_FOUND", message)
                message.contains("active exception request") -> McpToolResult.error("CONFLICT", message)
                else -> McpToolResult.error("VALIDATION_ERROR", message)
            }
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to create exception request: ${e.message}")
        }
    }
}
