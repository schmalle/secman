package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AdminSummaryService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for sending admin summary emails.
 *
 * ADMIN role is required via User Delegation.
 * Delegates to AdminSummaryService.sendSummaryEmail() which gathers system statistics,
 * renders email templates, and sends to all ADMIN users.
 *
 * Input parameters:
 * - dryRun (optional): If true, returns planned recipients without sending emails (default: false)
 *
 * Output:
 * - recipientCount: Total number of recipients
 * - emailsSent: Number of successfully sent emails
 * - emailsFailed: Number of failed emails
 * - status: Execution status (SUCCESS, FAILURE, PARTIAL_FAILURE, DRY_RUN)
 * - recipients: List of recipient email addresses
 * - failedRecipients: List of failed recipient email addresses
 * - message: Human-readable summary
 */
@Singleton
class SendAdminSummaryTool(
    @Inject private val adminSummaryService: AdminSummaryService
) : McpTool {

    override val name = "send_admin_summary"
    override val description = "Send admin summary email with system statistics to all ADMIN users (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "If true, returns planned recipients without actually sending emails. Default: false"
            )
        ),
        "required" to emptyList<String>()
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
                "ADMIN role required to send admin summary emails"
            )
        }

        val dryRun = arguments["dryRun"] as? Boolean ?: false

        try {
            val result = adminSummaryService.sendSummaryEmail(dryRun = dryRun)

            val response = mapOf(
                "success" to true,
                "recipientCount" to result.recipientCount,
                "emailsSent" to result.emailsSent,
                "emailsFailed" to result.emailsFailed,
                "status" to result.status.name,
                "recipients" to result.recipients,
                "failedRecipients" to result.failedRecipients,
                "message" to if (dryRun) {
                    "Dry run: would send admin summary to ${result.recipientCount} recipients"
                } else {
                    "Sent admin summary to ${result.emailsSent}/${result.recipientCount} recipients" +
                        if (result.emailsFailed > 0) " (${result.emailsFailed} failed)" else ""
                }
            )

            return McpToolResult.success(response)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to send admin summary: ${e.message}")
        }
    }
}
