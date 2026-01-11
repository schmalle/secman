package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.VulnerabilityRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for listing all installed products in the system.
 * Feature: 061-mcp-list-products
 *
 * Products are derived from vulnerability data (vulnerableProductVersions field).
 * ADMIN or SECCHAMPION role is required via User Delegation.
 *
 * Returns all unique product names sorted alphabetically.
 */
@Singleton
class ListProductsTool(
    @Inject private val vulnerabilityRepository: VulnerabilityRepository
) : McpTool {

    override val name = "list_products"
    override val description = "List all installed products in the system (ADMIN or SECCHAMPION only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "search" to mapOf(
                "type" to "string",
                "description" to "Optional search term to filter products (case-insensitive)"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation - cannot verify role without knowing the user
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN or SECCHAMPION role
        val hasRequiredRole = context.isAdmin ||
            context.delegatedUserRoles?.contains("SECCHAMPION") == true

        if (!hasRequiredRole) {
            return McpToolResult.error(
                "ROLE_REQUIRED",
                "ADMIN or SECCHAMPION role required to list products"
            )
        }

        try {
            // Get optional search parameter
            val search = (arguments["search"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

            // Retrieve products - for authorized roles, show all products
            val products = if (search != null) {
                vulnerabilityRepository.findDistinctProductsForAllFiltered(search)
            } else {
                vulnerabilityRepository.findDistinctProductsForAll()
            }

            val result = mapOf(
                "products" to products,
                "totalCount" to products.size,
                "searchTerm" to search
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve products: ${e.message}")
        }
    }
}
