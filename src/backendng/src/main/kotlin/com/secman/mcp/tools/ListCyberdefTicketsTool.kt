package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketQueryService
import jakarta.inject.Singleton

/** All tickets, filtered and paged, for the Cyber Defence side. */
@Singleton
class ListCyberdefTicketsTool(private val queries: CyberdefTicketQueryService) : McpTool {
    override val name = "list_cyberdef_tickets"
    override val description = "List security tickets across all accounts with status, severity, account and date filters " +
        "(CYBERDEF/ADMIN, requires User Delegation)."
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to CyberdefMcpSupport.FILTER_PROPERTIES + mapOf(
            "createdByMe" to mapOf("type" to "boolean", "description" to "Only tickets you submitted")
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val (filter, error) = CyberdefMcpSupport.filter(arguments, CyberdefMcpSupport.bool(arguments, "createdByMe"))
        if (filter == null) return error!!
        return McpToolResult.success(queries.list(CyberdefMcpSupport.actor(context), filter))
    }
}
