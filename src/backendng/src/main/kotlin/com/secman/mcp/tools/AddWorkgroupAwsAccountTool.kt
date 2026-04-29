package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.WorkgroupAwsAccountDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.DuplicateAccountException
import com.secman.service.WorkgroupAwsAccountService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool for adding an AWS account ID to a workgroup.
 *
 * Feature: Workgroup AWS Account Assignment
 *
 * ADMIN role is required via User Delegation.
 *
 * Input parameters:
 * - workgroupId (required): ID of the workgroup
 * - awsAccountId (required): AWS account ID (exactly 12 numeric digits)
 *
 * Returns:
 * - Created WorkgroupAwsAccountDto
 */
@Singleton
class AddWorkgroupAwsAccountTool(
    @Inject private val workgroupAwsAccountService: WorkgroupAwsAccountService
) : McpTool {

    private val log = LoggerFactory.getLogger(AddWorkgroupAwsAccountTool::class.java)

    override val name = "add_workgroup_aws_account"
    override val description = "Add an AWS account ID to a workgroup (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workgroupId" to mapOf(
                "type" to "number",
                "description" to "ID of the workgroup"
            ),
            "awsAccountId" to mapOf(
                "type" to "string",
                "description" to "AWS account ID (exactly 12 numeric digits)"
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
                "ADMIN role required to add AWS accounts to a workgroup"
            )
        }

        val workgroupId = (arguments["workgroupId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "workgroupId is required and must be a valid number")

        val awsAccountId = arguments["awsAccountId"] as? String
            ?: return McpToolResult.error("VALIDATION_ERROR", "awsAccountId is required and must be a string")

        val actorUsername = context.delegatedUserEmail
            ?: return McpToolResult.error("DELEGATION_REQUIRED", "Delegated user email is required")

        try {
            val saved = workgroupAwsAccountService.add(workgroupId, awsAccountId, actorUsername)
            val dto = WorkgroupAwsAccountDto.from(saved)

            log.info(
                "AUDIT: MCP add_workgroup_aws_account: workgroupId={}, awsAccountId={}, actor={}",
                workgroupId, awsAccountId, actorUsername
            )

            return McpToolResult.success(mapOf(
                "assignment" to dto,
                "message" to "AWS account $awsAccountId added to workgroup $workgroupId"
            ))

        } catch (e: DuplicateAccountException) {
            return McpToolResult.error("CONFLICT", e.message ?: "AWS account already assigned to this workgroup")
        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid request")
        } catch (e: Exception) {
            log.error("Failed to add AWS account to workgroup", e)
            return McpToolResult.error("EXECUTION_ERROR", "Failed to add AWS account to workgroup: ${e.message}")
        }
    }
}
