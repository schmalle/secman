package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketQueryService
import jakarta.inject.Singleton

/** Account-owner view of one ticket. Outside the caller's accounts it is NOT_FOUND. */
@Singleton
class GetMySecurityTicketTool(private val queries: CyberdefTicketQueryService) : McpTool {
    override val name = "get_my_security_ticket"
    override val description = "Get one security ticket for your accounts by id or ticketKey, with its activity log and the " +
        "statuses you may set next (requires User Delegation)."
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "id" to mapOf("type" to "number", "description" to "Ticket id"),
            "ticketKey" to mapOf("type" to "string", "description" to "e.g. CD-2026-000123")
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        val actor = CyberdefMcpSupport.actor(context)
        val id = CyberdefMcpSupport.long(arguments, "id")
        val result = if (id != null) queries.detail(actor, id)
            else queries.find(actor, CyberdefMcpSupport.string(arguments, "ticketKey"), externalReference = null)
        return CyberdefMcpSupport.toMcp(result)
    }
}
