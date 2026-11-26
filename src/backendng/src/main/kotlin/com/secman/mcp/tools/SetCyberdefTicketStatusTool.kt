package com.secman.mcp.tools

import com.secman.domain.CyberdefActorKind
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketService
import jakarta.inject.Singleton

/**
 * Cyber Defence side of the workflow only: answer NEEDS_INFO (→ IN_PROGRESS), verify
 * (→ CLOSED), reopen (→ REOPENED) or cancel (→ CANCELLED).
 */
@Singleton
class SetCyberdefTicketStatusTool(private val service: CyberdefTicketService) : McpTool {
    override val name = "set_cyberdef_ticket_status"
    override val description = "Cyber Defence status change: answer a NEEDS_INFO question (IN_PROGRESS), verify and close, " +
        "reopen or cancel a ticket. Pass expectedVersion from the last read (CYBERDEF/ADMIN, requires User Delegation)."
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "id" to mapOf("type" to "number", "description" to "Ticket id"),
            "toStatus" to mapOf("type" to "string", "enum" to listOf("IN_PROGRESS", "CLOSED", "REOPENED", "CANCELLED")),
            "comment" to mapOf("type" to "string", "description" to "Required for answers, reopen and most cancellations; max 4000 chars"),
            "expectedVersion" to mapOf("type" to "number", "description" to "ticket.version you last saw; a mismatch returns VERSION_CONFLICT")
        ),
        "required" to listOf("id", "toStatus")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val id = CyberdefMcpSupport.long(arguments, "id") ?: return CyberdefMcpSupport.invalid("id is required")
        return CyberdefMcpSupport.toMcp(
            service.transition(
                CyberdefMcpSupport.actor(context), id, CyberdefMcpSupport.string(arguments, "toStatus"),
                arguments["comment"] as? String, CyberdefMcpSupport.long(arguments, "expectedVersion"),
                onlyAs = CyberdefActorKind.DEFENDER
            )
        )
    }
}
