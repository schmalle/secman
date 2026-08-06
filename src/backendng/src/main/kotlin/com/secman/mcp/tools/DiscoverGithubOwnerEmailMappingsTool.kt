package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.GithubOwnerEmailDiscoveryService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that auto-discovers GitHub owner emails via the GitHub API
 * (`GET /users/{owner}` public email) for already-imported repositories that
 * don't yet have an `ownerEmail`. Creates a `github_owner_email_mapping` row
 * per discovered owner (backfilling matching repos), unless `dryRun` is set.
 *
 * Requires ADMIN or VULN role via User Delegation, matching
 * `POST /api/github/owner-email-mappings/discover`.
 */
@Singleton
class DiscoverGithubOwnerEmailMappingsTool(
    @Inject private val discoveryService: GithubOwnerEmailDiscoveryService
) : McpTool {

    override val name = "discover_github_owner_email_mappings"
    override val description = "Auto-discover GitHub owner emails via the GitHub API's public profile field for already-imported repos with no mapped email, and create the resulting owner->email mappings (ADMIN or VULN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Preview discovered mappings without creating them (default false)"
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        requireAnyRole(
            context, "VULN",
            code = "ADMIN_REQUIRED",
            message = "ADMIN or VULN role required to discover GitHub owner email mappings"
        )?.let { return it }

        val dryRun = arguments["dryRun"] as? Boolean ?: false

        return try {
            val actor = context.delegatedUserEmail ?: "mcp"
            val result = discoveryService.discover(dryRun, actor)
            McpToolResult.success(
                mapOf(
                    "status" to result.status,
                    "ownersEvaluated" to result.ownersEvaluated,
                    "ownersDiscovered" to result.ownersDiscovered,
                    "discoveredMappings" to result.discoveredMappings.map {
                        mapOf("owner" to it.owner, "email" to it.email, "repoCount" to it.repoCount)
                    },
                    "ownersSkippedNoPublicEmail" to result.ownersSkippedNoPublicEmail,
                    "errors" to result.errors,
                    "message" to (
                        if (dryRun) "Dry run: would create ${result.ownersDiscovered} owner email mapping(s)"
                        else "Created ${result.ownersDiscovered} owner email mapping(s)"
                        ) + if (result.errors.isNotEmpty()) " with ${result.errors.size} errors" else ""
                )
            )
        } catch (e: IllegalStateException) {
            McpToolResult.error("NO_GITHUB_CONFIG", e.message ?: "No active GitHub App configuration")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to discover GitHub owner email mappings: ${e.message}")
        }
    }
}
