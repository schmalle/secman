package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketArguments
import com.secman.service.cyberdef.CyberdefTicketInput
import com.secman.service.cyberdef.CyberdefTicketResult
import com.secman.service.cyberdef.CyberdefTicketSubmissionService
import jakarta.inject.Singleton

/**
 * Batch form of [CreateCyberdefTicketTool]: 1-100 tickets with one shared `delivery`, one
 * result per ticket, so a bad ticket never fails the rest. Each recipient gets one digest
 * mail for the whole batch. Requires delegation and a CYBERDEF or ADMIN delegated user.
 */
@Singleton
class CreateCyberdefTicketsTool(private val submissions: CyberdefTicketSubmissionService) : McpTool {
    override val name = "create_cyberdef_tickets"
    override val description = "Create up to 100 security tickets in one call: {tickets: [...], delivery}. Returns " +
        "CREATED/DUPLICATE/WOULD_CREATE/REJECTED per item with field errors; recipients get one digest mail per batch " +
        "(CYBERDEF/ADMIN, requires User Delegation)."
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "tickets" to mapOf("type" to "array", "minItems" to 1, "maxItems" to CyberdefTicketInput.BATCH_MAX,
                "items" to CyberdefMcpSupport.TICKET_SCHEMA),
            "delivery" to CyberdefMcpSupport.DELIVERY_SCHEMA
        ),
        "required" to listOf("tickets")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val (request, errors) = CyberdefTicketArguments.batch(arguments)
        if (request == null) return CyberdefMcpSupport.error(CyberdefTicketResult.VALIDATION_ERROR,
            CyberdefMcpSupport.withFields("invalid arguments", errors))
        return CyberdefMcpSupport.toMcp(submissions.createBatch(CyberdefMcpSupport.actor(context), request))
    }
}
