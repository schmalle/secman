package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.GithubOwnerEmailMappingService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for listing GitHub owner -> default email mappings.
 * Requires ADMIN, VULN, or SECCHAMPION role via User Delegation, matching
 * `GET /api/github/owner-email-mappings`.
 */
@Singleton
class ListGithubOwnerEmailMappingsTool(
    @Inject private val mappingService: GithubOwnerEmailMappingService
) : McpTool {

    override val name = "list_github_owner_email_mappings"
    override val description = "List GitHub owner (org/user login) to default email mappings used to auto-fill repository owner emails (ADMIN, VULN, or SECCHAMPION, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        requireAnyRole(
            context, "VULN", "SECCHAMPION",
            code = "ADMIN_REQUIRED",
            message = "ADMIN, VULN, or SECCHAMPION role required to list GitHub owner email mappings"
        )?.let { return it }

        return try {
            val mappings = mappingService.list().map { m ->
                mapOf(
                    "id" to m.id,
                    "owner" to m.owner,
                    "email" to m.email,
                    "repoCount" to mappingService.repoCountFor(m.owner),
                    "createdBy" to m.createdBy,
                    "createdAt" to m.createdAt.toString(),
                    "updatedAt" to m.updatedAt.toString()
                )
            }
            McpToolResult.success(mapOf("mappings" to mappings))
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to list GitHub owner email mappings: ${e.message}")
        }
    }
}
