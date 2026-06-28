package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.NewAccountNotificationService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that notifies users about new AWS account mappings created in the last N hours.
 *
 * Mirrors the CLI command `notify-new-accounts`.
 * ADMIN role is required via User Delegation.
 *
 * The CLI reads notification body text from a local file; this MCP tool accepts the
 * text directly as a parameter so MCP callers can customise it per invocation.
 */
@Singleton
class NotifyNewAccountsTool(
    @Inject private val newAccountNotificationService: NewAccountNotificationService
) : McpTool {

    override val name = "notify_new_accounts"
    override val description = "Notify users about new AWS account mappings created in the last N hours (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "notificationText" to mapOf(
                "type" to "string",
                "description" to "Body text of the notification email. The list of new account IDs is appended below this text automatically."
            ),
            "hours" to mapOf(
                "type" to "number",
                "description" to "Look-back window in hours: notify about mappings created within this period. Default: 24",
                "minimum" to 1
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Preview planned notifications without sending emails. Default: false"
            )
        ),
        "required" to listOf("notificationText")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to send new-account notifications")
        }

        val notificationText = (arguments["notificationText"] as? String)?.trim()
        if (notificationText.isNullOrBlank()) {
            return McpToolResult.error("INVALID_ARGUMENT", "notificationText is required and must not be blank")
        }

        val hours = (arguments["hours"] as? Number)?.toInt() ?: 24
        if (hours < 1) {
            return McpToolResult.error("INVALID_ARGUMENT", "hours must be >= 1")
        }
        val dryRun = arguments["dryRun"] as? Boolean ?: false

        return try {
            val result = newAccountNotificationService.sendNewAccountNotifications(
                hours = hours,
                dryRun = dryRun,
                verbose = false,
                notificationText = notificationText
            )

            val response = mapOf(
                "success" to true,
                "status" to result.status.name,
                "hours" to hours,
                "accountMappingsFound" to result.accountMappingsFound,
                "usersNotified" to result.usersNotified,
                "emailsSent" to result.emailsSent,
                "emailsFailed" to result.emailsFailed,
                "recipients" to result.recipients,
                "failedRecipients" to result.failedRecipients,
                "message" to if (dryRun) {
                    "Dry run: would notify ${result.usersNotified} user(s) about ${result.accountMappingsFound} new AWS account mapping(s)"
                } else {
                    "Notified ${result.emailsSent} user(s) about new AWS account mappings in the last $hours hour(s)" +
                        if (result.emailsFailed > 0) " (${result.emailsFailed} failed)" else ""
                }
            )
            McpToolResult.success(response)
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to send new-account notifications: ${e.message}")
        }
    }
}
