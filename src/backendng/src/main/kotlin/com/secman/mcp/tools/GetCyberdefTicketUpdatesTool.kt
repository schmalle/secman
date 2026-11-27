package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketQueryService
import jakarta.inject.Singleton

/**
 * Cursor feed of every ticket event (status changes, comments, creations) after
 * `sinceEventId`. Store `nextCursor` and pass it back next time to see each event once.
 */
@Singleton
class GetCyberdefTicketUpdatesTool(private val queries: CyberdefTicketQueryService) : McpTool {
    override val name = "get_cyberdef_ticket_updates"
    override val description = "Return ticket events after sinceEventId (oldest first) plus nextCursor; poll this to process " +
        "owners' answers and status changes (CYBERDEF/ADMIN, requires User Delegation)."
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "sinceEventId" to mapOf("type" to "number", "description" to "Last event id you processed; 0 for the beginning"),
            "limit" to mapOf("type" to "number", "description" to "1-500 events (default 100)")
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val since = CyberdefMcpSupport.long(arguments, "sinceEventId") ?: 0L
        val limit = CyberdefMcpSupport.long(arguments, "limit")?.toInt() ?: 100
        return CyberdefMcpSupport.toMcp(queries.updates(CyberdefMcpSupport.actor(context), since, limit))
    }
}
