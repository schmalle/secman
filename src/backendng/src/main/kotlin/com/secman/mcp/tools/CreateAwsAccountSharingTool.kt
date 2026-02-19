package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AwsAccountSharingService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool for creating an AWS account sharing rule.
 *
 * Feature: AWS Account Sharing
 *
 * ADMIN role is required via User Delegation.
 *
 * Input parameters:
 * - sourceUserId (required): ID of the user whose AWS accounts will be shared
 * - targetUserId (required): ID of the user who will receive visibility
 *
 * Returns:
 * - Created sharing rule details
 */
@Singleton
class CreateAwsAccountSharingTool(
    @Inject private val awsAccountSharingService: AwsAccountSharingService
) : McpTool {

    private val log = LoggerFactory.getLogger(CreateAwsAccountSharingTool::class.java)

    override val name = "create_aws_account_sharing"
    override val description = "Create an AWS account sharing rule to share one user's AWS account visibility with another user (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "sourceUserId" to mapOf(
                "type" to "number",
                "description" to "ID of the user whose AWS accounts will be shared (the sharer)"
            ),
            "targetUserId" to mapOf(
                "type" to "number",
                "description" to "ID of the user who will receive AWS account visibility (the recipient)"
            )
        ),
        "required" to listOf("sourceUserId", "targetUserId")
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
                "ADMIN_REQUIRED",
                "ADMIN role required to create AWS account sharing rules"
            )
        }

        val sourceUserId = (arguments["sourceUserId"] as? Number)?.toLong()
        val targetUserId = (arguments["targetUserId"] as? Number)?.toLong()

        if (sourceUserId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "sourceUserId is required and must be a valid number")
        }
        if (targetUserId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "targetUserId is required and must be a valid number")
        }

        try {
            val adminUserId = context.delegatedUserId
                ?: return McpToolResult.error("DELEGATION_REQUIRED", "Delegated user ID is required")

            val request = CreateAwsAccountSharingRequest(
                sourceUserId = sourceUserId,
                targetUserId = targetUserId
            )

            val result = awsAccountSharingService.createSharingRule(request, adminUserId)

            log.info(
                "AUDIT: MCP create_aws_account_sharing: source={}, target={}, actor={}",
                result.sourceUserEmail, result.targetUserEmail, context.delegatedUserEmail
            )

            return McpToolResult.success(mapOf(
                "sharingRule" to result,
                "message" to "AWS account sharing rule created: ${result.sourceUserEmail} -> ${result.targetUserEmail} (${result.sharedAwsAccountCount} accounts)"
            ))

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid request")
        } catch (e: IllegalStateException) {
            return McpToolResult.error("CONFLICT", e.message ?: "Sharing rule already exists")
        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "User not found")
        } catch (e: Exception) {
            log.error("Failed to create AWS account sharing rule", e)
            return McpToolResult.error("EXECUTION_ERROR", "Failed to create sharing rule: ${e.message}")
        }
    }
}
