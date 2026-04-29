package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.WorkgroupAwsAccountDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupAwsAccountService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool for listing AWS account assignments for a workgroup.
 *
 * Feature: Workgroup AWS Account Assignment
 *
 * ADMIN role is required via User Delegation.
 *
 * Input parameters:
 * - workgroupId (required): ID of the workgroup
 *
 * Returns:
 * - assignments: List of WorkgroupAwsAccountDto
 */
@Singleton
class ListWorkgroupAwsAccountsTool(
    @Inject private val workgroupAwsAccountService: WorkgroupAwsAccountService
) : McpTool {

    private val log = LoggerFactory.getLogger(ListWorkgroupAwsAccountsTool::class.java)

    override val name = "list_workgroup_aws_accounts"
    override val description = "List all AWS account assignments for a workgroup (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workgroupId" to mapOf(
                "type" to "number",
                "description" to "ID of the workgroup"
            )
        ),
        "required" to listOf("workgroupId")
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
                "ADMIN role required to list workgroup AWS accounts"
            )
        }

        val workgroupId = (arguments["workgroupId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "workgroupId is required and must be a valid number")

        try {
            val assignments = workgroupAwsAccountService.list(workgroupId)
                .map { WorkgroupAwsAccountDto.from(it) }

            log.debug(
                "MCP list_workgroup_aws_accounts: workgroupId={}, count={}, actor={}",
                workgroupId, assignments.size, context.delegatedUserEmail
            )

            return McpToolResult.success(mapOf("assignments" to assignments))

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Workgroup not found")
        } catch (e: Exception) {
            log.error("Failed to list workgroup AWS accounts", e)
            return McpToolResult.error("EXECUTION_ERROR", "Failed to list workgroup AWS accounts: ${e.message}")
        }
    }
}
