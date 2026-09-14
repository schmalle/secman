package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpRequirementManagementService
import jakarta.inject.Singleton

/** Deletes one requirement after explicit confirmation and reference checks. */
@Singleton
class DeleteRequirementTool(
    private val managementService: McpRequirementManagementService
) : McpTool {
    override val name = "delete_requirement"
    override val description = "Delete one requirement if it is not frozen in a release"
    override val operation = McpOperation.DELETE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "requirementId" to mapOf("type" to "number", "minimum" to 1),
            "confirm" to mapOf("type" to "boolean", "description" to "Must be true")
        ),
        "required" to listOf("requirementId", "confirm")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context,
            "ADMIN",
            "REQ",
            "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to delete requirements"
        )?.let { return it }
        if (arguments["confirm"] != true) {
            return McpToolResult.error("CONFIRMATION_REQUIRED", "Delete operation requires confirm: true")
        }
        val id = parsePositiveId(arguments, "requirementId")
            ?: return McpToolResult.error("VALIDATION_ERROR", "requirementId must be a positive number")

        return runRequirementMutation {
            managementService.deleteRequirement(id, context.delegatedUserId)
            mapOf("operation" to "DELETED", "requirementId" to id)
        }
    }
}
