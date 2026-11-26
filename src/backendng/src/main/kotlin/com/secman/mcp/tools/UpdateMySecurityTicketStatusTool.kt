package com.secman.mcp.tools

import com.secman.domain.CyberdefActorKind
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketService
import jakarta.inject.Singleton

/** Account-owner side of the workflow only: acknowledge, work, ask, resolve, accept, dispute. */
@Singleton
class UpdateMySecurityTicketStatusTool(private val service: CyberdefTicketService) : McpTool {
    override val name = "update_my_security_ticket_status"
    override val description = "Move one of your security tickets to ACKNOWLEDGED, IN_PROGRESS, NEEDS_INFO, RESOLVED, " +
        "RISK_ACCEPTED or FALSE_POSITIVE. Pass expectedVersion from the last read (requires User Delegation)."
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "id" to mapOf("type" to "number", "description" to "Ticket id"),
            "toStatus" to mapOf("type" to "string",
                "enum" to listOf("ACKNOWLEDGED", "IN_PROGRESS", "NEEDS_INFO", "RESOLVED", "RISK_ACCEPTED", "FALSE_POSITIVE")),
            "comment" to mapOf("type" to "string", "description" to "Required for NEEDS_INFO, RESOLVED, RISK_ACCEPTED and FALSE_POSITIVE"),
            "expectedVersion" to mapOf("type" to "number", "description" to "ticket.version you last saw; a mismatch returns VERSION_CONFLICT")
        ),
        "required" to listOf("id", "toStatus")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        val id = CyberdefMcpSupport.long(arguments, "id") ?: return CyberdefMcpSupport.invalid("id is required")
        return CyberdefMcpSupport.toMcp(
            service.transition(
                CyberdefMcpSupport.actor(context), id, CyberdefMcpSupport.string(arguments, "toStatus"),
                arguments["comment"] as? String, CyberdefMcpSupport.long(arguments, "expectedVersion"),
                onlyAs = CyberdefActorKind.OWNER
            )
        )
    }
}
