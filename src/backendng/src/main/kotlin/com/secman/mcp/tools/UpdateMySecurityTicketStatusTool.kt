package com.secman.mcp.tools

import com.secman.domain.CyberdefActorKind
import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketService
import jakarta.inject.Singleton

/**
 * Account-owner side of the workflow only: close a ticket with a note on what was done, or
 * reopen it. With `dryRun` the change is only checked.
 */
@Singleton
class UpdateMySecurityTicketStatusTool(private val service: CyberdefTicketService) : McpTool {
    override val name = "update_my_security_ticket_status"
    override val description = "Close one of your security tickets (comment required: what was done) or reopen it (comment required). " +
        "dryRun=true checks the change without applying it. Pass expectedVersion from the last read (requires User Delegation)."
    override val operation = McpOperation.WRITE
    override val inputSchema = CyberdefMcpSupport.statusSchema("Required: what was done (close) or why (reopen).")

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        val id = CyberdefMcpSupport.long(arguments, "id") ?: return CyberdefMcpSupport.invalid("id is required")
        val actor = CyberdefMcpSupport.actor(context)
        val toStatus = CyberdefMcpSupport.string(arguments, "toStatus")
        val comment = arguments["comment"] as? String
        val expectedVersion = CyberdefMcpSupport.long(arguments, "expectedVersion")
        return if (CyberdefMcpSupport.bool(arguments, "dryRun")) {
            CyberdefMcpSupport.toMcp(service.previewTransition(actor, id, toStatus, comment, expectedVersion, onlyAs = CyberdefActorKind.OWNER))
        } else {
            CyberdefMcpSupport.toMcp(service.transition(actor, id, toStatus, comment, expectedVersion, onlyAs = CyberdefActorKind.OWNER))
        }
    }
}
