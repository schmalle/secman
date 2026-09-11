package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.McpPermission
import com.secman.dto.IntegrationFindingFilter
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import com.secman.service.IntegrationReadService
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

private val emptySchema: Map<String, Any> = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
private val idSchema: Map<String, Any> = mapOf(
    "type" to "object",
    "required" to listOf("id"),
    "properties" to mapOf("id" to mapOf("type" to "integer", "minimum" to 1)),
)
private val pageProperties = mapOf(
    "page" to mapOf("type" to "integer", "minimum" to 0),
    "size" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100),
)

private fun delegatedAuthentication(users: UserRepository, context: McpExecutionContext): Authentication? {
    val user = users.findById(context.delegatedUserId!!).orElse(null) ?: return null
    return Authentication.build(
        user.username,
        user.roles.map { it.name },
        mapOf("userId" to user.id!!, "email" to user.email),
    )
}

private fun integrationRead(
    users: UserRepository,
    context: McpExecutionContext,
    block: (Authentication) -> Any,
): McpToolResult {
    val auth = delegatedAuthentication(users, context)
        ?: return McpToolResult.error("FORBIDDEN", "Delegated user unavailable")
    return try {
        McpToolResult.success(block(auth))
    } catch (error: HttpStatusException) {
        McpToolResult.error(error.status.name, error.message ?: "Integration read rejected")
    }
}

private fun page(arguments: Map<String, Any>): Pair<Int, Int> =
    ((arguments["page"] as? Number)?.toInt() ?: 0) to
        ((arguments["size"] as? Number)?.toInt() ?: 100)

private fun id(arguments: Map<String, Any>, name: String = "id"): Long? =
    (arguments[name] as? Number)?.toLong()

/** Exposes the aggregate health of integrations visible to the delegated user. */
@Singleton
class GetIntegrationSummaryTool(
    private val reads: IntegrationReadService,
    private val users: UserRepository,
) : McpTool {
    override val name = "get_integration_summary"
    override val description = "Get an asset-scoped health summary across registered security scanners."
    override val operation = McpOperation.READ
    override val inputSchema = emptySchema

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        if (!context.hasPermission(McpPermission.INTEGRATIONS_READ))
            return McpToolResult.error("FORBIDDEN", "Integration read permission required")
        return integrationRead(users, context, reads::summary)
    }
}

/** Lists integration findings after applying the delegated user's asset scope. */
@Singleton
class ListIntegrationFindingsTool(
    private val reads: IntegrationReadService,
    private val users: UserRepository,
) : McpTool {
    override val name = "list_integration_findings"
    override val description = "List asset-scoped scanner findings with bounded filters for security-work triage."
    override val operation = McpOperation.READ
    override val inputSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to pageProperties + mapOf(
            "scannerId" to mapOf("type" to "integer", "minimum" to 1),
            "subjectId" to mapOf("type" to "integer", "minimum" to 1),
            "githubRepositoryId" to mapOf("type" to "integer", "minimum" to 1),
            "source" to mapOf("type" to "string"),
            "owner" to mapOf("type" to "string", "maxLength" to 255),
            "severity" to mapOf("type" to "string", "enum" to listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")),
            "state" to mapOf("type" to "string", "enum" to listOf("OPEN", "RESOLVED")),
            "search" to mapOf("type" to "string", "maxLength" to 200),
        ),
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        if (!context.hasPermission(McpPermission.INTEGRATIONS_READ))
            return McpToolResult.error("FORBIDDEN", "Integration read permission required")
        val (page, size) = page(arguments)
        val filter = IntegrationFindingFilter(
            scannerId = id(arguments, "scannerId"),
            subjectId = id(arguments, "subjectId"),
            githubRepositoryId = id(arguments, "githubRepositoryId"),
            source = arguments["source"] as? String,
            owner = arguments["owner"] as? String,
            severity = arguments["severity"] as? String,
            state = arguments["state"] as? String,
            search = arguments["search"] as? String,
        )
        return integrationRead(users, context) { reads.findings(page, size, filter, it) }
    }
}

/** Resolves one integration finding without bypassing delegated asset access. */
@Singleton
class GetIntegrationFindingTool(
    private val reads: IntegrationReadService,
    private val users: UserRepository,
) : McpTool {
    override val name = "get_integration_finding"
    override val description = "Get one asset-scoped scanner finding by its SecMan identifier."
    override val operation = McpOperation.READ
    override val inputSchema = idSchema

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        if (!context.hasPermission(McpPermission.INTEGRATIONS_READ))
            return McpToolResult.error("FORBIDDEN", "Integration read permission required")
        val findingId = id(arguments) ?: return McpToolResult.error("VALIDATION_ERROR", "id is required")
        return integrationRead(users, context) { reads.finding(findingId, it) }
    }
}

/** Lists historical integration runs visible to the delegated user. */
@Singleton
class ListIntegrationRunsTool(
    private val reads: IntegrationReadService,
    private val users: UserRepository,
) : McpTool {
    override val name = "list_integration_runs"
    override val description = "List asset-scoped scanner runs for operational and regression analysis."
    override val operation = McpOperation.READ
    override val inputSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to pageProperties + mapOf(
            "scannerId" to mapOf("type" to "integer", "minimum" to 1),
            "subjectId" to mapOf("type" to "integer", "minimum" to 1),
        ),
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        if (!context.hasPermission(McpPermission.INTEGRATIONS_READ))
            return McpToolResult.error("FORBIDDEN", "Integration read permission required")
        val (page, size) = page(arguments)
        return integrationRead(users, context) {
            reads.runs(page, size, id(arguments, "scannerId"), id(arguments, "subjectId"), it)
        }
    }
}

/** Resolves one integration run together with its authorized finding snapshot. */
@Singleton
class GetIntegrationRunTool(
    private val reads: IntegrationReadService,
    private val users: UserRepository,
) : McpTool {
    override val name = "get_integration_run"
    override val description = "Get one asset-scoped scanner run with its historical finding snapshot."
    override val operation = McpOperation.READ
    override val inputSchema = idSchema

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        if (!context.hasPermission(McpPermission.INTEGRATIONS_READ))
            return McpToolResult.error("FORBIDDEN", "Integration read permission required")
        val runId = id(arguments) ?: return McpToolResult.error("VALIDATION_ERROR", "id is required")
        return integrationRead(users, context) { reads.run(runId, it) }
    }
}
