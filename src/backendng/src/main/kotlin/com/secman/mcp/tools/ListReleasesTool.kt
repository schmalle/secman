package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.Release
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ReleaseService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for listing releases with optional status filtering.
 * Feature: MCP Release Management
 *
 * Accessible by: ADMIN, RELEASE_MANAGER roles (via User Delegation)
 */
@Singleton
class ListReleasesTool(
    @Inject private val releaseService: ReleaseService
) : McpTool {

    override val name = "list_releases"
    override val description = "List all releases with optional status filtering. Returns release details including version, name, status, and creation info."
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "status" to mapOf(
                "type" to "string",
                "description" to "Filter by release status",
                "enum" to listOf("PREPARATION", "ALIGNMENT", "ACTIVE", "ARCHIVED")
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Check authorization - require User Delegation with ADMIN or RELEASE_MANAGER role
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        val userRoles = context.delegatedUserRoles?.map { it.uppercase() } ?: emptyList()
        if (!userRoles.contains("ADMIN") && !userRoles.contains("RELEASE_MANAGER")) {
            return McpToolResult.error(
                "AUTHORIZATION_ERROR",
                "ADMIN or RELEASE_MANAGER role required to list releases"
            )
        }

        try {
            // Parse optional status filter
            val statusStr = arguments["status"] as? String
            val status = statusStr?.let {
                try {
                    Release.ReleaseStatus.valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) {
                    return McpToolResult.error(
                        "VALIDATION_ERROR",
                        "Invalid status: $it. Must be one of: PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED"
                    )
                }
            }

            val releases = releaseService.listReleases(status)

            val result = mapOf(
                "releases" to releases.map { release ->
                    mapOf(
                        "id" to release.id,
                        "version" to release.version,
                        "name" to release.name,
                        "description" to release.description,
                        "status" to release.status.name,
                        "releaseDate" to release.releaseDate?.toString(),
                        "createdBy" to release.createdBy?.username,
                        "createdAt" to release.createdAt?.toString(),
                        "updatedAt" to release.updatedAt?.toString()
                    )
                },
                "total" to releases.size,
                "filter" to (status?.name ?: "ALL")
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to list releases: ${e.message}")
        }
    }
}
