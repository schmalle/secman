package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketService
import jakarta.inject.Singleton

/** Comment on a ticket. Open to both sides; the service scopes it to visible tickets. */
@Singleton
class AddCyberdefTicketCommentTool(private val service: CyberdefTicketService) : McpTool {
    override val name = "add_cyberdef_ticket_comment"
    override val description = "Add a comment to a security ticket you can see, without changing its status " +
        "(requires User Delegation)."
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "id" to mapOf("type" to "number", "description" to "Ticket id"),
            "comment" to mapOf("type" to "string", "description" to "Plain text, at most 4000 characters")
        ),
        "required" to listOf("id", "comment")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        val id = CyberdefMcpSupport.long(arguments, "id") ?: return CyberdefMcpSupport.invalid("id is required")
        return CyberdefMcpSupport.toMcp(service.comment(CyberdefMcpSupport.actor(context), id, arguments["comment"] as? String))
    }
}
