package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketQueryService
import jakarta.inject.Singleton

/** Account-owner view: tickets for the AWS accounts the delegated user can access. */
@Singleton
class ListMySecurityTicketsTool(private val queries: CyberdefTicketQueryService) : McpTool {
    override val name = "list_my_security_tickets"
    override val description = "List the security tickets for the AWS accounts you are allocated to (requires User Delegation)."
    override val operation = McpOperation.READ
    override val inputSchema = mapOf("type" to "object", "properties" to CyberdefMcpSupport.FILTER_PROPERTIES)

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        val (filter, error) = CyberdefMcpSupport.filter(arguments, createdByMe = false)
        if (filter == null) return error!!
        return McpToolResult.success(queries.list(CyberdefMcpSupport.actor(context), filter))
    }
}
