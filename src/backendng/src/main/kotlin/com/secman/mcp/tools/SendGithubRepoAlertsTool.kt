package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.GithubRepoAlertService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that alerts GitHub repo owners whose open high+critical
 * Dependabot alert count has not decreased over the last `thresholdDays`
 * days (default 30). Repos with an active alert exception are skipped;
 * repos without an ownerEmail are reported as unmapped. Mirrors the CLI
 * command `alert-github-repo-owners`.
 *
 * ADMIN role is required via User Delegation.
 */
@Singleton
class SendGithubRepoAlertsTool(
    @Inject private val alertService: GithubRepoAlertService
) : McpTool {

    override val name = "send_github_repo_alerts"
    override val description = "Alert GitHub repo owners whose open high/critical vulnerability count has not decreased over the last thresholdDays days (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "If true, returns planned recipients without actually sending emails. Default: false"
            ),
            "thresholdDays" to mapOf(
                "type" to "number",
                "description" to "Comparison window in days. Default: 30",
                "minimum" to 1
            ),
            "force" to mapOf(
                "type" to "boolean",
                "description" to "If true, alerts every eligible owner with open high/critical alerts regardless of whether the count has decreased. Default: false"
            ),
            "onlyEmail" to mapOf(
                "type" to "string",
                "description" to "If set, restricts the run to repos owned by this email address (case-insensitive)"
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to send GitHub repo alerts"
            )
        }

        val dryRun = arguments["dryRun"] as? Boolean ?: false
        val thresholdDays = (arguments["thresholdDays"] as? Number)?.toInt() ?: 30
        val force = arguments["force"] as? Boolean ?: false
        val onlyEmail = arguments["onlyEmail"] as? String
        if (thresholdDays < 1) {
            return McpToolResult.error("INVALID_ARGUMENT", "thresholdDays must be >= 1")
        }

        return try {
            val result = alertService.sendGithubRepoAlerts(
                dryRun = dryRun,
                thresholdDays = thresholdDays,
                force = force,
                onlyEmail = onlyEmail
            )
            McpToolResult.success(
                mapOf(
                    "success" to true,
                    "status" to result.status.name,
                    "thresholdDays" to result.thresholdDays,
                    "reposEvaluated" to result.reposEvaluated,
                    "reposAlerted" to result.reposAlerted,
                    "reposExcepted" to result.reposExcepted,
                    "reposSkippedInsufficientHistory" to result.reposSkippedInsufficientHistory,
                    "unmappedRepos" to result.unmappedRepos,
                    "emailsSent" to result.emailsSent,
                    "emailsFailed" to result.emailsFailed,
                    "recipients" to result.recipients,
                    "failedRecipients" to result.failedRecipients,
                    "message" to if (dryRun) {
                        "Dry run: ${result.reposAlerted} repos have non-decreasing high/critical vulnerabilities " +
                            "(${result.unmappedRepos.size} without owner email, ${result.reposExcepted.size} excepted)"
                    } else {
                        "Alerted ${result.emailsSent} owners about ${result.reposAlerted} repos" +
                            if (result.emailsFailed > 0) " (${result.emailsFailed} failed)" else ""
                    }
                )
            )
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to send GitHub repo alerts: ${e.message}")
        }
    }
}
