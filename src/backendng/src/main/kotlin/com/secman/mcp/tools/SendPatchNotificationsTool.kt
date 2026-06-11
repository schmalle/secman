package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.UserVulnerabilityNotificationService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for notifying users about missing patches (overdue vulnerabilities),
 * restricted to users whose login email starts with a given first character.
 *
 * ADMIN role is required via User Delegation. Mirrors the CLI command
 * `send-patch-notifications`.
 *
 * Input parameters:
 * - emailPrefix (required): First character of the email address to notify (e.g. "a").
 * - days (optional): Missing-patch age threshold in days (default: 30).
 * - dryRun (optional): If true, returns planned recipients without sending emails (default: false).
 *
 * Output:
 * - status: Execution status (SUCCESS, FAILURE, PARTIAL_FAILURE, DRY_RUN)
 * - awsAccountsAffected: AWS accounts with overdue vulnerabilities
 * - usersNotified: Number of matched users notified
 * - emailsSent / emailsFailed: Delivery counters
 * - recipients / failedRecipients: Email lists
 * - message: Human-readable summary
 */
@Singleton
class SendPatchNotificationsTool(
    @Inject private val userVulnerabilityNotificationService: UserVulnerabilityNotificationService
) : McpTool {

    override val name = "send_patch_notifications"
    override val description = "Notify users about missing patches (overdue vulnerabilities), filtered by the first character of their email address (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "emailPrefix" to mapOf(
                "type" to "string",
                "description" to "First character of the email address to notify (e.g. 'a'). Required."
            ),
            "days" to mapOf(
                "type" to "number",
                "description" to "Missing-patch (vulnerability) age threshold in days. Default: 30",
                "minimum" to 1
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "If true, returns planned recipients without actually sending emails. Default: false"
            )
        ),
        "required" to listOf("emailPrefix")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation for audit trail
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN role
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to send patch notifications"
            )
        }

        val emailPrefix = (arguments["emailPrefix"] as? String)?.trim()
        if (emailPrefix.isNullOrEmpty()) {
            return McpToolResult.error(
                "INVALID_ARGUMENT",
                "emailPrefix is required (e.g. 'a' — the first character of the email address)"
            )
        }

        val days = (arguments["days"] as? Number)?.toInt() ?: 30
        if (days < 1) {
            return McpToolResult.error("INVALID_ARGUMENT", "days must be >= 1")
        }
        val dryRun = arguments["dryRun"] as? Boolean ?: false

        try {
            val result = userVulnerabilityNotificationService.sendUserVulnerabilityNotifications(
                thresholdDays = days,
                dryRun = dryRun,
                verbose = false,
                notificationUser = null,
                emailPrefix = emailPrefix
            )

            val response = mapOf(
                "success" to true,
                "status" to result.status.name,
                "emailPrefix" to emailPrefix,
                "thresholdDays" to result.thresholdDays,
                "awsAccountsAffected" to result.awsAccountsAffected,
                "usersNotified" to result.usersNotified,
                "emailsSent" to result.emailsSent,
                "emailsFailed" to result.emailsFailed,
                "recipients" to result.recipients,
                "failedRecipients" to result.failedRecipients,
                "message" to if (dryRun) {
                    "Dry run: would notify ${result.usersNotified} users matching '$emailPrefix*' about missing patches"
                } else {
                    "Notified ${result.emailsSent} users matching '$emailPrefix*' about missing patches" +
                        if (result.emailsFailed > 0) " (${result.emailsFailed} failed)" else ""
                }
            )

            return McpToolResult.success(response)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to send patch notifications: ${e.message}")
        }
    }
}
