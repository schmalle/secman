package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpRequirementManagementService
import jakarta.inject.Singleton

@Singleton
class DeleteUseCaseTool(private val managementService: McpRequirementManagementService) : McpTool {
    override val name = "delete_use_case"
    override val description = "Delete an unassigned, non-system use case after explicit confirmation"
    override val operation = McpOperation.DELETE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "useCaseId" to mapOf("type" to "number", "minimum" to 1),
            "confirm" to mapOf("type" to "boolean", "description" to "Must be true")
        ),
        "required" to listOf("useCaseId", "confirm")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context,
            "ADMIN",
            "REQ",
            "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to delete use cases"
        )?.let { return it }
        if (arguments["confirm"] != true) {
            return McpToolResult.error("CONFIRMATION_REQUIRED", "Delete operation requires confirm: true")
        }
        val id = parsePositiveId(arguments, "useCaseId")
            ?: return McpToolResult.error("VALIDATION_ERROR", "useCaseId must be a positive number")
        return runRequirementMutation {
            managementService.deleteUseCase(id, context.delegatedUserId)
            mapOf("operation" to "DELETED", "useCaseId" to id)
        }
    }
}
