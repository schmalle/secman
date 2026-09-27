package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.OwnerMailDeliveryService
import jakarta.inject.Singleton

@Singleton
class ListOwnerMailNotificationsTool(private val service: OwnerMailDeliveryService) : McpTool {
    override val name = "list_owner_mail_notifications"
    override val description = "List retained AWS owner welcome-mail delivery outcomes (ADMIN only)"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf("type" to "object", "properties" to mapOf(
        "page" to mapOf("type" to "integer", "minimum" to 0),
        "pageSize" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100)
    ))
    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "ADMIN", message = "ADMIN delegation required")?.let { return it }
        if (listOf("page", "pageSize").any { key -> arguments.containsKey(key) &&
                (arguments[key] as? Number)?.toString()?.toIntOrNull() == null }) {
            return McpToolResult.error("VALIDATION_ERROR", "page and pageSize must be integers")
        }
        return riskAssessmentTool { service.list((arguments["page"] as? Number)?.toInt() ?: 0,
            (arguments["pageSize"] as? Number)?.toInt() ?: 20) }
    }
}
