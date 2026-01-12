package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.OutdatedAssetService
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for getting assets with overdue vulnerabilities.
 * Feature: 062-mcp-vuln-exceptions
 *
 * Access Control:
 * - Requires User Delegation
 * - ADMIN or VULN role required
 * - ADMIN sees all overdue assets
 * - VULN sees only assets from assigned workgroups
 *
 * Spec reference: spec.md FR-001 through FR-005
 * User Story: US1 - View Overdue Assets (P1)
 */
@Singleton
class GetOverdueAssetsTool(
    @Inject private val outdatedAssetService: OutdatedAssetService
) : McpTool {

    override val name = "get_overdue_assets"
    override val description = "Get assets with overdue vulnerabilities (ADMIN/VULN role required, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "page" to mapOf(
                "type" to "number",
                "description" to "Page number (0-indexed, default: 0)"
            ),
            "size" to mapOf(
                "type" to "number",
                "description" to "Page size (default: 20, max: 100)"
            ),
            "minSeverity" to mapOf(
                "type" to "string",
                "enum" to listOf("CRITICAL", "HIGH", "MEDIUM", "LOW"),
                "description" to "Minimum severity filter"
            ),
            "searchTerm" to mapOf(
                "type" to "string",
                "description" to "Search by asset name (case-insensitive)"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // FR-002: Require User Delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // FR-003: Require ADMIN or VULN role
        val hasRole = context.isAdmin || context.delegatedUserRoles?.contains("VULN") == true
        if (!hasRole) {
            return McpToolResult.error(
                "ROLE_REQUIRED",
                "ADMIN or VULN role required to view overdue assets"
            )
        }

        try {
            // Parse parameters with defaults
            val page = (arguments["page"] as? Number)?.toInt() ?: 0
            val size = ((arguments["size"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
            val minSeverity = arguments["minSeverity"] as? String
            val searchTerm = arguments["searchTerm"] as? String

            // Create minimal Authentication adapter from McpExecutionContext
            val authentication = createAuthenticationFromContext(context)

            // Call service with access control
            val pageable = Pageable.from(page, size)
            val result = outdatedAssetService.getOutdatedAssets(
                authentication = authentication,
                searchTerm = searchTerm,
                minSeverity = minSeverity,
                pageable = pageable
            )

            // Map results to response format
            val assets = result.content.map { asset ->
                // Determine max severity based on counts
                val maxSeverity = when {
                    asset.criticalCount > 0 -> "CRITICAL"
                    asset.highCount > 0 -> "HIGH"
                    asset.mediumCount > 0 -> "MEDIUM"
                    asset.lowCount > 0 -> "LOW"
                    else -> "NONE"
                }
                mapOf(
                    "id" to asset.id,
                    "assetId" to asset.assetId,
                    "name" to asset.assetName,
                    "type" to asset.assetType,
                    "totalVulnerabilities" to asset.totalOverdueCount,
                    "criticalCount" to asset.criticalCount,
                    "highCount" to asset.highCount,
                    "mediumCount" to asset.mediumCount,
                    "lowCount" to asset.lowCount,
                    "oldestVulnDays" to asset.oldestVulnDays,
                    "maxSeverity" to maxSeverity
                )
            }

            return McpToolResult.success(
                mapOf(
                    "assets" to assets,
                    "totalElements" to result.totalSize,
                    "totalPages" to result.totalPages,
                    "page" to page,
                    "size" to size
                )
            )

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve overdue assets: ${e.message}")
        }
    }

    /**
     * Create a minimal Authentication adapter from McpExecutionContext.
     * This allows the OutdatedAssetService to apply its workgroup-based access control.
     */
    private fun createAuthenticationFromContext(context: McpExecutionContext): Authentication {
        return object : Authentication {
            override fun getName(): String = context.delegatedUserEmail ?: "mcp-user"

            override fun getRoles(): Collection<String> = context.delegatedUserRoles ?: emptySet()

            override fun getAttributes(): Map<String, Any> = mapOf(
                "userId" to (context.delegatedUserId ?: 0L),
                "workgroupIds" to (context.accessibleWorkgroupIds?.toList() ?: emptyList<Long>())
            )
        }
    }
}
