package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.McpPermission
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import com.secman.service.IntegrationReadService
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

@Singleton
class ListIntegrationSubjectsTool(private val reads: IntegrationReadService, private val users: UserRepository) : McpTool {
    override val name = "list_integration_subjects"
    override val description = "List asset-scoped registered subjects, including stable GitHub instance and numeric repository IDs."
    override val operation = McpOperation.READ
    override val inputSchema: Map<String, Any> = mapOf("type" to "object", "required" to listOf("scannerId"), "properties" to mapOf(
        "scannerId" to mapOf("type" to "integer", "minimum" to 1),
        "page" to mapOf("type" to "integer", "minimum" to 0), "size" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100)
    ))

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        if (!context.hasPermission(McpPermission.INTEGRATIONS_READ) &&
            !context.hasPermission(McpPermission.ASSETS_READ) &&
            !context.hasPermission(McpPermission.INTEGRATIONS_WRITE))
            return McpToolResult.error("FORBIDDEN", "Asset read permission required")
        val scannerId = (arguments["scannerId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "scannerId is required")
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val size = (arguments["size"] as? Number)?.toInt() ?: 100
        val user = users.findById(context.delegatedUserId!!).orElse(null)
            ?: return McpToolResult.error("FORBIDDEN", "Delegated user unavailable")
        return try {
            val auth = Authentication.build(user.username, user.roles.map { it.name },
                mapOf("userId" to user.id!!, "email" to user.email))
            McpToolResult.success(reads.subjects(scannerId, page, size, auth))
        } catch (e: HttpStatusException) {
            McpToolResult.error(e.status.name, e.message ?: "Integration read rejected")
        }
    }
}
