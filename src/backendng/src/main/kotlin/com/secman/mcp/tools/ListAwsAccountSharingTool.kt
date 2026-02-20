package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AwsAccountSharingService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool for listing AWS account sharing rules with pagination.
 *
 * Feature: AWS Account Sharing
 *
 * ADMIN role is required via User Delegation.
 *
 * Input parameters:
 * - page (optional): Page number (0-indexed). Defaults to 0.
 * - size (optional): Page size. Defaults to 20, max 100.
 *
 * Returns:
 * - sharingRules: List of sharing rule DTOs
 * - page, size, totalElements, totalPages
 */
@Singleton
class ListAwsAccountSharingTool(
    @Inject private val awsAccountSharingService: AwsAccountSharingService
) : McpTool {

    private val log = LoggerFactory.getLogger(ListAwsAccountSharingTool::class.java)

    override val name = "list_aws_account_sharing"
    override val description = "List AWS account sharing rules (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "page" to mapOf(
                "type" to "number",
                "description" to "Page number (0-indexed)",
                "default" to 0,
                "minimum" to 0
            ),
            "size" to mapOf(
                "type" to "number",
                "description" to "Page size",
                "default" to 20,
                "minimum" to 1,
                "maximum" to 100
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

        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to list AWS account sharing rules"
            )
        }

        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val size = ((arguments["size"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

        try {
            val result = awsAccountSharingService.listSharingRules(page, size)

            log.debug(
                "MCP list_aws_account_sharing: page={}, size={}, total={}, actor={}",
                page, size, result["totalElements"], context.delegatedUserEmail
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            log.error("Failed to list AWS account sharing rules", e)
            return McpToolResult.error("EXECUTION_ERROR", "Failed to list sharing rules: ${e.message}")
        }
    }
}
