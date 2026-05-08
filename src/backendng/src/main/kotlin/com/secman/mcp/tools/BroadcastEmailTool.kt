package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.EmailBroadcastService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for sending a custom HTML email broadcast to every user with a recorded login.
 *
 * Wraps EmailBroadcastService.createJob() + runJobAsync() so a job row is persisted and
 * progress is queryable through the same admin job history (POST /api/admin/email-broadcast).
 *
 * ADMIN role is required via User Delegation. dryRun returns the recipient count without
 * creating a job.
 */
@Singleton
class BroadcastEmailTool(
    @Inject private val emailBroadcastService: EmailBroadcastService
) : McpTool {

    override val name = "broadcast_email"
    override val description = "Send a custom HTML email broadcast to every user with a recorded login (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "subject" to mapOf(
                "type" to "string",
                "description" to "Email subject line (1-255 characters)"
            ),
            "htmlContent" to mapOf(
                "type" to "string",
                "description" to "HTML body content (rendered inside the SecMan branded shell). Required unless dryRun=true."
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "If true, returns the recipient count without creating a broadcast job. Default: false."
            )
        ),
        "required" to listOf("subject")
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
                "ADMIN role required to send email broadcasts"
            )
        }

        val subject = (arguments["subject"] as? String)?.trim()
        if (subject.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "subject is required and must not be blank")
        }
        if (subject.length > 255) {
            return McpToolResult.error("VALIDATION_ERROR", "subject must be 255 characters or fewer")
        }

        val dryRun = arguments["dryRun"] as? Boolean ?: false

        return try {
            if (dryRun) {
                val count = emailBroadcastService.recipientCount()
                McpToolResult.success(
                    mapOf(
                        "dryRun" to true,
                        "recipientCount" to count,
                        "message" to "Dry run: would send to $count recipient(s)"
                    )
                )
            } else {
                val htmlContent = (arguments["htmlContent"] as? String)?.trim()
                if (htmlContent.isNullOrBlank()) {
                    return McpToolResult.error("VALIDATION_ERROR", "htmlContent is required when dryRun is false")
                }
                if (htmlContent.length > 1_000_000) {
                    return McpToolResult.error("VALIDATION_ERROR", "htmlContent must be 1,000,000 characters or fewer")
                }

                val createdBy = context.delegatedUserEmail ?: "mcp-system"
                val job = emailBroadcastService.createJob(
                    subject = subject,
                    htmlContent = htmlContent,
                    createdBy = createdBy
                )
                emailBroadcastService.runJobAsync(job.id!!)

                McpToolResult.success(
                    mapOf(
                        "dryRun" to false,
                        "jobId" to job.id,
                        "totalRecipients" to job.totalRecipients,
                        "status" to job.status.name,
                        "message" to "Broadcast job ${job.id} queued for ${job.totalRecipients} recipient(s)"
                    )
                )
            }
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to send email broadcast: ${e.message}")
        }
    }
}
