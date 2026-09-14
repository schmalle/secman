package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpRequirementManagementService
import jakarta.inject.Singleton

@Singleton
class CreateUseCaseTool(private val managementService: McpRequirementManagementService) : McpTool {
    override val name = "create_use_case"
    override val description = "Create a requirement use case"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "maxLength" to MAX_USE_CASE_NAME_LENGTH)
        ),
        "required" to listOf("name")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context,
            "ADMIN",
            "REQ",
            "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to create use cases"
        )?.let { return it }
        val name = validatedUseCaseName(arguments) ?: return invalidUseCaseName()
        return runRequirementMutation {
            useCaseResult(managementService.createUseCase(name, context.delegatedUserId)) + ("operation" to "CREATED")
        }
    }
}

internal fun validatedUseCaseName(arguments: Map<String, Any>): String? =
    (arguments["name"] as? String)?.trim()?.takeIf { it.isNotBlank() && it.length <= MAX_USE_CASE_NAME_LENGTH }

internal fun invalidUseCaseName() = McpToolResult.error(
    "VALIDATION_ERROR",
    "name is required and must not exceed $MAX_USE_CASE_NAME_LENGTH characters"
)
