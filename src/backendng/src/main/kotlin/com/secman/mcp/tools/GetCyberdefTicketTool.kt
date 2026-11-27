package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketQueryService
import jakarta.inject.Singleton

/** One ticket with description, resources, full activity and the allowed next statuses. */
@Singleton
class GetCyberdefTicketTool(private val queries: CyberdefTicketQueryService) : McpTool {
    override val name = "get_cyberdef_ticket"
    override val description = "Get one security ticket by id, ticketKey or your own externalReference, including its " +
        "activity log and the statuses you may set next (CYBERDEF/ADMIN, requires User Delegation)."
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "id" to mapOf("type" to "number", "description" to "Ticket id"),
            "ticketKey" to mapOf("type" to "string", "description" to "e.g. CD-2026-000123"),
            "externalReference" to mapOf("type" to "string", "description" to "The externalReference you submitted")
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val actor = CyberdefMcpSupport.actor(context)
        val id = CyberdefMcpSupport.long(arguments, "id")
        val result = if (id != null) queries.detail(actor, id)
            else queries.find(actor, CyberdefMcpSupport.string(arguments, "ticketKey"), CyberdefMcpSupport.string(arguments, "externalReference"))
        return CyberdefMcpSupport.toMcp(result)
    }
}
