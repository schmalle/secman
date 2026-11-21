package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.service.AssetFilterService
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

@Singleton
class ListVisibleAccountsTool(
    private val users: UserRepository,
    private val assets: AssetRepository,
    private val scope: AssetFilterService
) : McpTool {
    override val name = "list_visible_accounts"
    override val description = "List AWS account IDs accessible to the authenticated user."
    override val operation = McpOperation.READ
    override val inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        val user = users.findById(requireNotNull(context.delegatedUserId)).orElse(null)
            ?: return McpToolResult.error("FORBIDDEN", "Account access unavailable")
        if (!user.enabled) return McpToolResult.error("FORBIDDEN", "Account access unavailable")
        val roles = user.roles.map { it.name }
        val principal = Authentication.build(user.username, roles, mapOf("userId" to user.id!!, "email" to user.email))
        val accounts = scope.getAccessibleAwsAccountIds(principal)
        val visible = if ("ADMIN" in roles || "SECCHAMPION" in roles) accounts + assets.findDistinctCloudAccountIds() else accounts
        return McpToolResult.success(mapOf("accountIds" to visible.sorted()))
    }
}
