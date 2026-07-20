package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.VulnerabilityExceptionExpiryReminderService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that notifies vulnerability exception owners about exceptions expiring soon
 * (default: exactly 7 days from today).
 *
 * Mirrors the CLI command `send-exception-expiry-reminders`.
 * ADMIN role is required via User Delegation.
 */
@Singleton
class SendExceptionExpiryRemindersTool(
    @Inject private val expiryReminderService: VulnerabilityExceptionExpiryReminderService
) : McpTool {

    override val name = "send_exception_expiry_reminders"
    override val description = "Notify vulnerability exception owners about exceptions expiring exactly N days from today (default: 7) (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "days" to mapOf(
                "type" to "number",
                "description" to "Remind about exceptions expiring exactly this many days from today. Default: 7",
                "minimum" to 1
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Preview planned notifications without sending emails. Default: false"
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to send exception expiry reminders")
        }

        val days = (arguments["days"] as? Number)?.toInt() ?: 7
        if (days < 1) {
            return McpToolResult.error("INVALID_ARGUMENT", "days must be >= 1")
        }
        val dryRun = arguments["dryRun"] as? Boolean ?: false

        return try {
            val result = expiryReminderService.sendExpiryReminders(
                days = days,
                dryRun = dryRun,
                verbose = false
            )

            val response = mapOf(
                "success" to true,
                "status" to result.status.name,
                "days" to days,
                "exceptionsExpiring" to result.exceptionsExpiring,
                "ownersNotified" to result.ownersNotified,
                "emailsSent" to result.emailsSent,
                "emailsFailed" to result.emailsFailed,
                "recipients" to result.recipients,
                "failedRecipients" to result.failedRecipients,
                "unmappedOwners" to result.unmappedOwners,
                "alreadyNotified" to result.alreadyNotified,
                "message" to if (dryRun) {
                    "Dry run: would notify ${result.ownersNotified} owner(s) about ${result.exceptionsExpiring} exception(s) expiring in $days day(s)"
                } else {
                    "Notified ${result.emailsSent} owner(s) about exceptions expiring in $days day(s)" +
                        if (result.emailsFailed > 0) " (${result.emailsFailed} failed)" else ""
                }
            )
            McpToolResult.success(response)
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to send exception expiry reminders: ${e.message}")
        }
    }
}
