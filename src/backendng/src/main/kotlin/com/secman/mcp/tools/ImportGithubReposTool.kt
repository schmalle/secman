package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.GithubRepoImportService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that imports the GitHub App installation's repositories into
 * secman (repository inventory + open Dependabot alert counts + per-run
 * finding snapshots). Mirrors the CLI command `import-github-repos` and
 * `POST /api/github/import`.
 *
 * Requires ADMIN or VULN role via User Delegation, matching the REST
 * controller's `@Secured("ADMIN", "VULN")` guard.
 */
@Singleton
class ImportGithubReposTool(
    @Inject private val importService: GithubRepoImportService
) : McpTool {

    override val name = "import_github_repos"
    override val description = "Import GitHub repositories accessible via the configured GitHub App, including their open high/critical Dependabot alert counts (ADMIN or VULN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        requireAnyRole(
            context, "VULN",
            code = "ADMIN_REQUIRED",
            message = "ADMIN or VULN role required to import GitHub repositories"
        )?.let { return it }

        return try {
            val result = importService.importRepositories()
            McpToolResult.success(
                mapOf(
                    "success" to true,
                    "reposDiscovered" to result.reposDiscovered,
                    "reposNew" to result.reposNew,
                    "reposUpdated" to result.reposUpdated,
                    "totalCritical" to result.totalCritical,
                    "totalHigh" to result.totalHigh,
                    "reposWithAlertsDisabled" to result.reposWithAlertsDisabled,
                    "errors" to result.errors,
                    "importedAt" to result.importedAt.toString(),
                    "message" to "Imported ${result.reposDiscovered} repositories " +
                        "(${result.reposNew} new, ${result.reposUpdated} updated, " +
                        "${result.totalCritical} critical / ${result.totalHigh} high open alerts)" +
                        if (result.errors.isNotEmpty()) " with ${result.errors.size} errors" else ""
                )
            )
        } catch (e: IllegalStateException) {
            McpToolResult.error("NO_GITHUB_CONFIG", e.message ?: "No active GitHub App configuration")
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to import GitHub repositories: ${e.message}")
        }
    }
}
