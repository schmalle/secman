package com.secman.mcp.tools

import com.secman.domain.CyberdefActorKind
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketService
import jakarta.inject.Singleton

/**
 * Cyber Defence side of the workflow only: close a ticket (e.g. verified or withdrawn) or
 * reopen one the owners closed. With `dryRun` it reports who would be mailed instead.
 */
@Singleton
class SetCyberdefTicketStatusTool(private val service: CyberdefTicketService) : McpTool {
    override val name = "set_cyberdef_ticket_status"
    override val description = "Cyber Defence status change: CLOSED (comment optional) or reopen to OPEN (comment required). " +
        "dryRun=true reports who would be mailed without changing anything. Pass expectedVersion from the last read " +
        "(CYBERDEF/ADMIN, requires User Delegation)."
    override val operation = McpOperation.WRITE
    override val inputSchema = CyberdefMcpSupport.statusSchema("Optional when closing, required when reopening.")

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val id = CyberdefMcpSupport.long(arguments, "id") ?: return CyberdefMcpSupport.invalid("id is required")
        val actor = CyberdefMcpSupport.actor(context)
        val toStatus = CyberdefMcpSupport.string(arguments, "toStatus")
        val comment = arguments["comment"] as? String
        val expectedVersion = CyberdefMcpSupport.long(arguments, "expectedVersion")
        return if (CyberdefMcpSupport.bool(arguments, "dryRun")) {
            CyberdefMcpSupport.toMcp(service.previewTransition(actor, id, toStatus, comment, expectedVersion, onlyAs = CyberdefActorKind.DEFENDER))
        } else {
            CyberdefMcpSupport.toMcp(service.transition(actor, id, toStatus, comment, expectedVersion, onlyAs = CyberdefActorKind.DEFENDER))
        }
    }
}
