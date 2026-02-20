package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AwsAccountSharingService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool for deleting an AWS account sharing rule.
 *
 * Feature: AWS Account Sharing
 *
 * ADMIN role is required via User Delegation.
 *
 * Input parameters:
 * - id (required): ID of the sharing rule to delete
 *
 * Returns:
 * - Success confirmation
 */
@Singleton
class DeleteAwsAccountSharingTool(
    @Inject private val awsAccountSharingService: AwsAccountSharingService
) : McpTool {

    private val log = LoggerFactory.getLogger(DeleteAwsAccountSharingTool::class.java)

    override val name = "delete_aws_account_sharing"
    override val description = "Delete an AWS account sharing rule (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "id" to mapOf(
                "type" to "number",
                "description" to "The ID of the sharing rule to delete"
            )
        ),
        "required" to listOf("id")
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
                "ADMIN role required to delete AWS account sharing rules"
            )
        }

        val id = (arguments["id"] as? Number)?.toLong()

        if (id == null) {
            return McpToolResult.error("VALIDATION_ERROR", "id is required and must be a valid number")
        }

        try {
            awsAccountSharingService.deleteSharingRule(id)

            log.info(
                "AUDIT: MCP delete_aws_account_sharing: id={}, actor={}",
                id, context.delegatedUserEmail
            )

            return McpToolResult.success(mapOf(
                "message" to "AWS account sharing rule $id deleted successfully"
            ))

        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Sharing rule not found")
        } catch (e: Exception) {
            log.error("Failed to delete AWS account sharing rule", e)
            return McpToolResult.error("EXECUTION_ERROR", "Failed to delete sharing rule: ${e.message}")
        }
    }
}
