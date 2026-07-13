package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.GithubOwnerEmailMappingService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for creating a GitHub owner -> default email mapping. Creating a
 * mapping immediately backfills `ownerEmail` on existing repos under that
 * owner whose value is currently blank.
 *
 * Requires ADMIN or VULN role via User Delegation, matching
 * `POST /api/github/owner-email-mappings`.
 */
@Singleton
class CreateGithubOwnerEmailMappingTool(
    @Inject private val mappingService: GithubOwnerEmailMappingService
) : McpTool {

    override val name = "create_github_owner_email_mapping"
    override val description = "Create a default email mapping for a GitHub owner (org/user login); backfills ownerEmail on existing repos under that owner that don't already have one (ADMIN or VULN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "owner" to mapOf("type" to "string", "description" to "GitHub owner login (org or user)"),
            "email" to mapOf("type" to "string", "description" to "Default notification email for this owner's repositories")
        ),
        "required" to listOf("owner", "email")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }

        val hasRequiredRole = context.isAdmin || context.delegatedUserRoles?.contains("VULN") == true
        if (!hasRequiredRole) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN or VULN role required to create a GitHub owner email mapping")
        }

        val owner = arguments["owner"] as? String
        val email = arguments["email"] as? String
        if (owner.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "owner is required")
        }
        if (email.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "email is required")
        }

        return try {
            val actor = context.delegatedUserEmail ?: "mcp"
            val mapping = mappingService.create(owner, email, actor)
            McpToolResult.success(
                mapOf(
                    "id" to mapping.id,
                    "owner" to mapping.owner,
                    "email" to mapping.email,
                    "repoCount" to mappingService.repoCountFor(mapping.owner),
                    "message" to "GitHub owner email mapping created: ${mapping.owner} -> ${mapping.email}"
                )
            )
        } catch (e: GithubOwnerEmailMappingService.DuplicateOwnerException) {
            McpToolResult.error("CONFLICT", e.message ?: "A mapping for this owner already exists")
        } catch (e: GithubOwnerEmailMappingService.InvalidEmailException) {
            McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid email address")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to create GitHub owner email mapping: ${e.message}")
        }
    }
}
