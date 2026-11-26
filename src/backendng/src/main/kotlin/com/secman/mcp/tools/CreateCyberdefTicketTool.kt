package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketArguments
import com.secman.service.cyberdef.CyberdefTicketResult
import com.secman.service.cyberdef.CyberdefTicketSubmissionService
import jakarta.inject.Singleton

/**
 * Submit one vulnerability finding about an AWS account. SecMan creates a ticket and mails
 * everyone allocated to the account. Idempotent per `ticket.externalReference`.
 * Requires delegation and a CYBERDEF or ADMIN delegated user.
 */
@Singleton
class CreateCyberdefTicketTool(private val submissions: CyberdefTicketSubmissionService) : McpTool {
    override val name = "create_cyberdef_ticket"
    override val description = "Create a security ticket for an AWS account and notify its owners " +
        "(CYBERDEF/ADMIN, requires User Delegation). Arguments: {ticket, delivery}. Returns the same per-item result " +
        "as create_cyberdef_tickets: CREATED, DUPLICATE (same externalReference), WOULD_CREATE (delivery.dryRun) or " +
        "REJECTED with field errors. delivery.recipients mails those addresses instead of the owners."
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf("ticket" to CyberdefMcpSupport.TICKET_SCHEMA, "delivery" to CyberdefMcpSupport.DELIVERY_SCHEMA),
        "required" to listOf("ticket")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val (request, errors) = CyberdefTicketArguments.single(arguments)
        if (request == null) return CyberdefMcpSupport.error(CyberdefTicketResult.VALIDATION_ERROR,
            CyberdefMcpSupport.withFields("invalid arguments", errors))
        return CyberdefMcpSupport.toMcp(submissions.create(CyberdefMcpSupport.actor(context), request))
    }
}
