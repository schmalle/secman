package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupAwsAccountService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool for removing an AWS account assignment from a workgroup.
 *
 * Feature: Workgroup AWS Account Assignment
 *
 * ADMIN role is required via User Delegation.
 *
 * Input parameters:
 * - workgroupId (required): ID of the workgroup
 * - awsAccountId (required): AWS account ID to remove
 *
 * Returns:
 * - deleted: Boolean indicating whether a row was removed
 */
@Singleton
class RemoveWorkgroupAwsAccountTool(
    @Inject private val workgroupAwsAccountService: WorkgroupAwsAccountService
) : McpTool {

    private val log = LoggerFactory.getLogger(RemoveWorkgroupAwsAccountTool::class.java)

    override val name = "remove_workgroup_aws_account"
    override val description = "Remove an AWS account from a workgroup (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workgroupId" to mapOf(
                "type" to "number",
                "description" to "ID of the workgroup"
            ),
            "awsAccountId" to mapOf(
                "type" to "string",
                "description" to "AWS account ID to remove"
            )
        ),
        "required" to listOf("workgroupId", "awsAccountId")
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
                "ADMIN role required to remove AWS accounts from a workgroup"
            )
        }

        val workgroupId = (arguments["workgroupId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "workgroupId is required and must be a valid number")

        val awsAccountId = arguments["awsAccountId"] as? String
            ?: return McpToolResult.error("VALIDATION_ERROR", "awsAccountId is required and must be a string")

        try {
            val deleted = workgroupAwsAccountService.remove(workgroupId, awsAccountId)

            log.info(
                "AUDIT: MCP remove_workgroup_aws_account: workgroupId={}, awsAccountId={}, deleted={}, actor={}",
                workgroupId, awsAccountId, deleted, context.delegatedUserEmail
            )

            return McpToolResult.success(mapOf("deleted" to deleted))

        } catch (e: Exception) {
            log.error("Failed to remove AWS account from workgroup", e)
            return McpToolResult.error("EXECUTION_ERROR", "Failed to remove AWS account from workgroup: ${e.message}")
        }
    }
}
