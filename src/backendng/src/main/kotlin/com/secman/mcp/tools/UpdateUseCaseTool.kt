package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpRequirementManagementService
import jakarta.inject.Singleton

@Singleton
class UpdateUseCaseTool(private val managementService: McpRequirementManagementService) : McpTool {
    override val name = "update_use_case"
    override val description = "Rename a use case; system-protected use cases cannot be renamed"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "useCaseId" to mapOf("type" to "number", "minimum" to 1),
            "name" to mapOf("type" to "string", "maxLength" to MAX_USE_CASE_NAME_LENGTH)
        ),
        "required" to listOf("useCaseId", "name")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context,
            "ADMIN",
            "REQ",
            "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to update use cases"
        )?.let { return it }
        val id = parsePositiveId(arguments, "useCaseId")
            ?: return McpToolResult.error("VALIDATION_ERROR", "useCaseId must be a positive number")
        val name = validatedUseCaseName(arguments) ?: return invalidUseCaseName()
        return runRequirementMutation {
            useCaseResult(managementService.updateUseCase(id, name, context.delegatedUserId)) + ("operation" to "UPDATED")
        }
    }
}
