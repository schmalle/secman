package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpRequirementManagementService
import jakarta.inject.Singleton

/** Replaces the complete use-case assignment set for one requirement. */
@Singleton
class SetRequirementUseCasesTool(
    private val managementService: McpRequirementManagementService
) : McpTool {
    override val name = "set_requirement_use_cases"
    override val description =
        "Replace all use-case assignments for a requirement; pass an empty list to remove every assignment"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "requirementId" to mapOf("type" to "number", "minimum" to 1),
            "useCaseIds" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "number", "minimum" to 1),
                "maxItems" to MAX_REQUIREMENT_RELATIONSHIPS,
                "description" to "Complete replacement set; empty removes every assignment"
            )
        ),
        "required" to listOf("requirementId", "useCaseIds")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyRole(
            context,
            "ADMIN",
            "REQ",
            "SECCHAMPION",
            code = "ROLE_REQUIRED",
            message = "ADMIN, REQ or SECCHAMPION role required to assign requirement use cases"
        )?.let { return it }
        val id = parsePositiveId(arguments, "requirementId")
            ?: return McpToolResult.error("VALIDATION_ERROR", "requirementId must be a positive number")
        val (ids, error) = parseIdList(arguments, "useCaseIds", required = true)
        error?.let { return it }

        return runRequirementMutation {
            val saved = managementService.replaceRequirementUseCases(id, ids.orEmpty(), context.delegatedUserId)
            mapOf(
                "operation" to "ASSIGNMENTS_REPLACED",
                "requirementId" to saved.id,
                "useCases" to saved.usecases.sortedBy { it.name }.map(::useCaseResult)
            )
        }
    }
}
