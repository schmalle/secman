package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.GithubOwnerEmailMappingService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for deleting a GitHub owner -> default email mapping. Does not
 * un-set any `ownerEmail` the mapping previously backfilled.
 *
 * Requires ADMIN or VULN role via User Delegation, matching
 * `DELETE /api/github/owner-email-mappings/{id}`.
 */
@Singleton
class DeleteGithubOwnerEmailMappingTool(
    @Inject private val mappingService: GithubOwnerEmailMappingService
) : McpTool {

    override val name = "delete_github_owner_email_mapping"
    override val description = "Delete a GitHub owner email mapping (ADMIN or VULN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "id" to mapOf("type" to "number", "description" to "The ID of the mapping to delete")
        ),
        "required" to listOf("id")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        val hasRequiredRole = context.isAdmin || context.delegatedUserRoles?.contains("VULN") == true
        if (!hasRequiredRole) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN or VULN role required to delete a GitHub owner email mapping")
        }

        val id = (arguments["id"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "id is required and must be a valid number")

        return try {
            mappingService.delete(id)
            McpToolResult.success(mapOf("message" to "GitHub owner email mapping $id deleted successfully"))
        } catch (e: GithubOwnerEmailMappingService.NotFoundException) {
            McpToolResult.error("NOT_FOUND", e.message ?: "Mapping not found")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to delete GitHub owner email mapping: ${e.message}")
        }
    }
}
