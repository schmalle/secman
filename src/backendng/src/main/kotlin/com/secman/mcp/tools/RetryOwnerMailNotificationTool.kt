package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.OwnerMailDeliveryService
import jakarta.inject.Singleton

@Singleton
class RetryOwnerMailNotificationTool(private val service: OwnerMailDeliveryService) : McpTool {
    override val name = "retry_owner_mail_notification"
    override val description = "Explicitly retry a retained FAILED AWS owner welcome notification (ADMIN only); SENT and uncertain delivery cannot be retried"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf("type" to "object", "properties" to mapOf(
        "notificationId" to mapOf("type" to "integer", "minimum" to 1)
    ), "required" to listOf("notificationId"))
    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "ADMIN", message = "ADMIN delegation required")?.let { return it }
        val id = (arguments["notificationId"] as? Number)?.toString()?.toLongOrNull()
        if (id == null || id < 1) return McpToolResult.error("VALIDATION_ERROR", "notificationId must be a positive integer")
        return riskAssessmentTool { service.retry(id, context.delegatedUserId!!) }
    }
}
