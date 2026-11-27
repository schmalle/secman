package com.secman.mcp.tools

import com.secman.domain.CyberdefSeverity
import com.secman.domain.CyberdefTicketStatus
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.cyberdef.CyberdefFieldError
import com.secman.service.cyberdef.CyberdefTicketActor
import com.secman.service.cyberdef.CyberdefTicketFilter
import com.secman.service.cyberdef.CyberdefTicketResult
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Shared plumbing of the account-ticket MCP tools: who the caller is, argument parsing,
 * and the mapping of service results to MCP results. The authorization decision itself
 * stays in the service, the same code path the REST controller uses.
 */
internal object CyberdefMcpSupport {

    /** The delegated user as a ticket actor. Only the user's own roles count, never the key's. */
    fun actor(context: McpExecutionContext): CyberdefTicketActor = CyberdefTicketActor(
        userId = context.delegatedUserId!!,
        username = context.delegatedUsername ?: context.delegatedUserEmail.orEmpty(),
        email = context.delegatedUserEmail,
        roles = context.delegatedUserRoles.orEmpty().map { it.uppercase() }.toSet()
    )

    /** Error message of the CYBERDEF/ADMIN guard that every submit tool calls itself. */
    const val DEFENDERS_ONLY = "FORBIDDEN: Only Cyber Defence (CYBERDEF) or ADMIN may use this tool"

    fun <T : Any> toMcp(result: CyberdefTicketResult<T>): McpToolResult = when (result) {
        is CyberdefTicketResult.Ok -> McpToolResult.success(result.value)
        is CyberdefTicketResult.Failed -> error(result.code, withFields(result.message, result.fieldErrors))
    }

    /** JSON-RPC carries only a message, so field errors are appended as `field: problem` pairs. */
    fun withFields(message: String, errors: List<CyberdefFieldError>): String =
        if (errors.isEmpty()) message
        else message + " (" + errors.take(10).joinToString("; ") { "${it.field}: ${it.message}" } + ")"

    /**
     * The JSON-RPC layer forwards only an error's message to the client, so the stable code
     * leads the message ("UNROUTABLE_ACCOUNT: ..."). Scripts match on that prefix.
     */
    fun error(code: String, message: String): McpToolResult.Error = McpToolResult.Error(code, "$code: $message")

    fun invalid(message: String): McpToolResult.Error = error(CyberdefTicketResult.VALIDATION_ERROR, message)

    /** A numeric argument that may arrive as a JSON number or a numeric string. */
    fun long(arguments: Map<String, Any>, key: String): Long? = when (val value = arguments[key]) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    fun string(arguments: Map<String, Any>, key: String): String? = (arguments[key] as? String)?.takeIf { it.isNotBlank() }

    /** A boolean argument; anything but `true`/"true" is false. */
    fun bool(arguments: Map<String, Any>, key: String): Boolean = when (val value = arguments[key]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }

    /** Accepts a single string, a comma list or an array. */
    fun stringList(arguments: Map<String, Any>, key: String): List<String> = when (val value = arguments[key]) {
        is String -> value.split(',')
        is List<*> -> value.mapNotNull { it as? String }
        else -> emptyList()
    }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }

    /** Builds a list filter, or returns the validation error to send back. */
    fun filter(arguments: Map<String, Any>, createdByMe: Boolean): Pair<CyberdefTicketFilter?, McpToolResult.Error?> {
        val statuses = stringList(arguments, "status").map { name ->
            CyberdefTicketStatus.entries.firstOrNull { it.name == name }
                ?: return null to invalid("status must be any of ${CyberdefTicketStatus.entries.joinToString()}")
        }.toSet()
        val severities = stringList(arguments, "severity").map { name ->
            CyberdefSeverity.entries.firstOrNull { it.name == name }
                ?: return null to invalid("severity must be any of ${CyberdefSeverity.entries.joinToString()}")
        }.toSet()
        val updatedSince = string(arguments, "updatedSince")?.let {
            try {
                Instant.parse(it.trim())
            } catch (e: DateTimeParseException) {
                return null to invalid("updatedSince must be an ISO-8601 instant")
            }
        }
        return CyberdefTicketFilter(
            statuses = statuses, severities = severities, accountId = string(arguments, "awsAccountId"),
            createdByMe = createdByMe, overdueOnly = bool(arguments, "overdue"), updatedSince = updatedSince,
            search = string(arguments, "q"), page = long(arguments, "page")?.toInt() ?: 0,
            size = long(arguments, "size")?.toInt() ?: 50
        ) to null
    }

    val RECIPIENTS_PROPERTY: Map<String, Any> = mapOf(
        "type" to "array", "items" to mapOf("type" to "string"), "maxItems" to 20,
        "description" to "Mail these addresses instead of the account's owners (max 20). Each must be a SecMan user or " +
            "mapping address, or on secman.cyberdef.recipient-override.allowed-domains; otherwise RECIPIENT_NOT_ALLOWED."
    )

    val DRY_RUN_PROPERTY: Map<String, Any> = mapOf(
        "type" to "boolean",
        "description" to "Validate and resolve recipients and due date, but store nothing and send no mail (default false)"
    )

    /** Shared schema of the two status tools; only the allowed targets differ. */
    fun statusSchema(commentHint: String): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "id" to mapOf("type" to "number", "description" to "Ticket id"),
            "toStatus" to mapOf("type" to "string", "enum" to listOf("OPEN", "CLOSED")),
            "comment" to mapOf("type" to "string", "description" to "$commentHint Max 4000 characters."),
            "expectedVersion" to mapOf("type" to "number", "description" to "ticket.version you last saw; a mismatch returns VERSION_CONFLICT"),
            "dryRun" to mapOf("type" to "boolean", "description" to "Check the change and report who would be mailed; change nothing")
        ),
        "required" to listOf("id", "toStatus")
    )

    val FILTER_PROPERTIES: Map<String, Any> = mapOf(
        "status" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to CyberdefTicketStatus.entries.map { it.name }),
            "description" to "Only these statuses (default: all)"),
        "severity" to mapOf("type" to "array", "items" to mapOf("type" to "string", "enum" to CyberdefSeverity.entries.map { it.name }),
            "description" to "Only these severities (default: all)"),
        "awsAccountId" to mapOf("type" to "string", "description" to "Only this 12-digit AWS account"),
        "overdue" to mapOf("type" to "boolean", "description" to "Only open tickets past their due date"),
        "updatedSince" to mapOf("type" to "string", "description" to "ISO-8601 instant; only tickets changed since then"),
        "q" to mapOf("type" to "string", "description" to "Search in title, or an exact ticket key such as CD-2026-000123"),
        "page" to mapOf("type" to "number", "description" to "0-based page (default 0)"),
        "size" to mapOf("type" to "number", "description" to "Page size, 1-200 (default 50)")
    )

    val TICKET_FIELDS: Map<String, Any> = mapOf(
        "externalReference" to mapOf("type" to "string",
            "description" to "Your own id for this finding (1-128 chars of A-Z a-z 0-9 . _ : / # -). Resending it returns the existing ticket instead of a duplicate."),
        "awsAccountId" to mapOf("type" to "string", "description" to "12-digit AWS account id"),
        "title" to mapOf("type" to "string", "description" to "Short summary, at most 200 characters"),
        "severity" to mapOf("type" to "string", "enum" to CyberdefSeverity.entries.map { it.name }),
        "description" to mapOf("type" to "string", "description" to "Markdown, at most 20 000 characters"),
        "recommendedAction" to mapOf("type" to "string", "description" to "What the owners should do, at most 10 000 characters"),
        "cves" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Up to 100 CVE ids"),
        "resources" to mapOf(
            "type" to "array",
            "description" to "Up to 500 affected resources. For an ARN, type and region may be omitted (derived); its account must match awsAccountId.",
            "items" to mapOf("type" to "object", "properties" to mapOf(
                "type" to mapOf("type" to "string", "description" to "e.g. EC2_INSTANCE, S3_BUCKET, RDS_SNAPSHOT"),
                "identifier" to mapOf("type" to "string", "description" to "ARN, instance id or hostname"),
                "region" to mapOf("type" to "string", "description" to "e.g. eu-central-1")
            ), "required" to listOf("identifier"))
        ),
        "references" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Up to 20 https links (display only)"),
        "sourceSystem" to mapOf("type" to "string", "description" to "Where the finding came from, e.g. guardduty"),
        "dueAt" to mapOf("type" to "string", "description" to "ISO-8601 instant; may only shorten the severity SLA"),
    )

    val TICKET_SCHEMA: Map<String, Any> = mapOf(
        "type" to "object", "properties" to TICKET_FIELDS,
        "required" to listOf("awsAccountId", "title", "severity", "description")
    )

    /** Sending options, once per call; in a batch they apply to every ticket. */
    val DELIVERY_SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "dryRun" to DRY_RUN_PROPERTY,
            "recipients" to RECIPIENTS_PROPERTY,
            "allowUnrouted" to mapOf("type" to "boolean",
                "description" to "Create tickets even if nobody is allocated to the account (default false: rejected as UNROUTABLE_ACCOUNT)")
        )
    )
}
