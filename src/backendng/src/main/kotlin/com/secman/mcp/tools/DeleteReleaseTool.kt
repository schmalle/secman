package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ReleaseService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for deleting a release.
 * Feature: MCP Release Management
 *
 * Note: ACTIVE releases cannot be deleted. Set another release to ACTIVE first,
 * or set this release to LEGACY before deletion.
 *
 * Accessible by: ADMIN, RELEASE_MANAGER roles (via User Delegation)
 */
@Singleton
class DeleteReleaseTool(
    @Inject private val releaseService: ReleaseService
) : McpTool {

    override val name = "delete_release"
    override val description = "Delete a release and its requirement snapshots. ACTIVE releases cannot be deleted - set another release as active first."
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "releaseId" to mapOf(
                "type" to "number",
                "description" to "ID of the release to delete"
            )
        ),
        "required" to listOf("releaseId")
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
                "ADMIN or RELEASE_MANAGER role required to delete releases"
            )
        }

        // Extract and validate releaseId
        val releaseId = (arguments["releaseId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "releaseId is required")

        try {
            // Get release info before deletion for response
            val release = releaseService.getReleaseById(releaseId)
            val version = release.version
            val name = release.name

            releaseService.deleteRelease(releaseId)

            val result = mapOf(
                "success" to true,
                "deleted" to mapOf(
                    "id" to releaseId,
                    "version" to version,
                    "name" to name
                ),
                "message" to "Release $version ($name) deleted successfully"
            )

            return McpToolResult.success(result)

        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Release not found")
        } catch (e: IllegalStateException) {
            return McpToolResult.error("CONFLICT", e.message ?: "Cannot delete release in current state")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to delete release: ${e.message}")
        }
    }
}
