package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefTicketInput
import com.secman.service.cyberdef.CyberdefTicketResult
import com.secman.service.cyberdef.CyberdefTicketService
import jakarta.inject.Singleton

/**
 * Submit one vulnerability finding about an AWS account. SecMan creates a ticket and mails
 * everyone allocated to the account. Idempotent per `externalReference`.
 * Requires delegation and a CYBERDEF or ADMIN delegated user.
 */
@Singleton
class CreateCyberdefTicketTool(private val service: CyberdefTicketService) : McpTool {
    override val name = "create_cyberdef_ticket"
    override val description = "Create a security ticket for an AWS account and notify its owners " +
        "(CYBERDEF/ADMIN, requires User Delegation). Resending the same externalReference returns the existing ticket. " +
        "dryRun=true previews recipients and due date without storing or mailing; recipients=[...] mails those addresses instead of the owners."
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to CyberdefMcpSupport.TICKET_FIELDS,
        "required" to listOf("awsAccountId", "title", "severity", "description")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "CYBERDEF", "ADMIN", code = "FORBIDDEN", message = CyberdefMcpSupport.DEFENDERS_ONLY)
            ?.let { return it }
        val (request, errors) = CyberdefTicketInput.fromArguments(arguments)
        if (request == null) return CyberdefMcpSupport.invalid(errors.joinToString("; "))
        val result = service.create(CyberdefMcpSupport.actor(context), request)
        // A rejected single ticket is an error with its stable code (UNROUTABLE_ACCOUNT, ...).
        if (result is CyberdefTicketResult.Ok && result.value.outcome == CyberdefTicketService.OUTCOME_REJECTED) {
            return CyberdefMcpSupport.error(result.value.errorCode ?: CyberdefTicketResult.VALIDATION_ERROR,
                result.value.errors.joinToString("; "))
        }
        return CyberdefMcpSupport.toMcp(result)
    }
}
