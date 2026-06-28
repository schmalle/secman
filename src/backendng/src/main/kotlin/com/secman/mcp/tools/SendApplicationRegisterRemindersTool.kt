package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ApplicationRegisterReminderService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that sends reminder emails for application register entries not reviewed recently.
 *
 * Mirrors the CLI command `send-application-register-reminders`.
 * ADMIN role is required via User Delegation.
 */
@Singleton
class SendApplicationRegisterRemindersTool(
    @Inject private val applicationRegisterReminderService: ApplicationRegisterReminderService
) : McpTool {

    override val name = "send_application_register_reminders"
    override val description = "Send reminder emails for application register entries not checked within the threshold period (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "days" to mapOf(
                "type" to "number",
                "description" to "Threshold in days: entries not reviewed within this period trigger a reminder. Default: 365",
                "minimum" to 1
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Preview planned reminders without sending emails. Default: false"
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to send application register reminders")
        }

        val days = (arguments["days"] as? Number)?.toInt() ?: 365
        if (days < 1) {
            return McpToolResult.error("INVALID_ARGUMENT", "days must be >= 1")
        }
        val dryRun = arguments["dryRun"] as? Boolean ?: false

        return try {
            val result = applicationRegisterReminderService.sendReminderEmails(days, dryRun, verbose = false)

            val response = mapOf(
                "success" to true,
                "status" to result.status.name,
                "thresholdDays" to result.thresholdDays,
                "entriesOverdue" to result.entriesOverdue,
                "recipientCount" to result.recipientCount,
                "emailsSent" to result.emailsSent,
                "emailsFailed" to result.emailsFailed,
                "recipients" to result.recipients,
                "failedRecipients" to result.failedRecipients,
                "message" to if (dryRun) {
                    "Dry run: ${result.entriesOverdue} overdue application register entry/entries, would notify ${result.recipientCount} recipient(s)"
                } else {
                    "Sent ${result.emailsSent} application register reminder(s)" +
                        if (result.emailsFailed > 0) " (${result.emailsFailed} failed)" else ""
                }
            )
            McpToolResult.success(response)
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to send application register reminders: ${e.message}")
        }
    }
}
