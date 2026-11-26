package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketCreateRequest
import com.secman.service.cyberdef.CyberdefTicketInput
import com.secman.service.cyberdef.CyberdefTicketService
import jakarta.inject.Singleton

/**
 * Batch form of [CreateCyberdefTicketTool]: 1-100 tickets, one result per item, so a bad
 * item never fails the rest. Requires delegation and a CYBERDEF or ADMIN delegated user.
 */
@Singleton
class CreateCyberdefTicketsTool(private val service: CyberdefTicketService) : McpTool {
    override val name = "create_cyberdef_tickets"
    override val description = "Create up to 100 security tickets in one call; returns CREATED/DUPLICATE/REJECTED per item " +
        "(CYBERDEF/ADMIN, requires User Delegation)."
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "tickets" to mapOf(
                "type" to "array", "minItems" to 1, "maxItems" to CyberdefTicketInput.BATCH_MAX,
                "items" to mapOf("type" to "object", "properties" to CyberdefMcpSupport.TICKET_FIELDS,
                    "required" to listOf("awsAccountId", "title", "severity", "description"))
            )
        ),
        "required" to listOf("tickets")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val items = arguments["tickets"] as? List<*> ?: return CyberdefMcpSupport.invalid("tickets must be an array")
        if (items.isEmpty() || items.size > CyberdefTicketInput.BATCH_MAX) {
            return CyberdefMcpSupport.invalid("tickets must contain 1-${CyberdefTicketInput.BATCH_MAX} items")
        }
        val requests = mutableListOf<CyberdefTicketCreateRequest>()
        items.forEachIndexed { index, item ->
            @Suppress("UNCHECKED_CAST")
            val map = (item as? Map<String, Any?>) ?: return CyberdefMcpSupport.invalid("tickets[$index] must be an object")
            val (request, errors) = CyberdefTicketInput.fromArguments(map)
            requests += request ?: return CyberdefMcpSupport.invalid("tickets[$index]: ${errors.joinToString("; ")}")
        }
        return CyberdefMcpSupport.toMcp(service.createBatch(CyberdefMcpSupport.actor(context), requests))
    }
}
