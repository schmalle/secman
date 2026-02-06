package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.Release
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.ReleaseService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for setting a release status.
 * Feature: MCP Release Management
 *
 * Status transitions follow this workflow:
 * - PREPARATION -> ACTIVE: Allowed (direct activation, skipping alignment).
 * - ALIGNMENT -> ACTIVE: Allowed (after alignment completes).
 * - ACTIVE -> ARCHIVED: Automatic when another release becomes ACTIVE.
 * - ARCHIVED -> *: Not allowed (terminal state).
 *
 * Only one release can be ACTIVE at a time.
 *
 * Accessible by: ADMIN, RELEASE_MANAGER roles (via User Delegation)
 */
@Singleton
class SetReleaseStatusTool(
    @Inject private val releaseService: ReleaseService
) : McpTool {

    override val name = "set_release_status"
    override val description = "Set a release status. Only PREPARATION or ALIGNMENT releases can be set to ACTIVE. When a release becomes ACTIVE, the previously ACTIVE release automatically becomes ARCHIVED."
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "releaseId" to mapOf(
                "type" to "number",
                "description" to "ID of the release to update"
            ),
            "status" to mapOf(
                "type" to "string",
                "description" to "New status for the release",
                "enum" to listOf("ACTIVE")
            )
        ),
        "required" to listOf("releaseId", "status")
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
                "ADMIN or RELEASE_MANAGER role required to update release status"
            )
        }

        // Extract and validate parameters
        val releaseId = (arguments["releaseId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "releaseId is required")

        val statusStr = arguments["status"] as? String
            ?: return McpToolResult.error("VALIDATION_ERROR", "status is required")

        val newStatus = try {
            Release.ReleaseStatus.valueOf(statusStr.uppercase())
        } catch (e: IllegalArgumentException) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "Invalid status: $statusStr. Only 'ACTIVE' is allowed for manual status changes."
            )
        }

        // Only allow setting to ACTIVE
        if (newStatus != Release.ReleaseStatus.ACTIVE) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "Only 'ACTIVE' status can be set manually. ARCHIVED status is automatically assigned when another release becomes ACTIVE."
            )
        }

        try {
            val updatedRelease = releaseService.updateReleaseStatus(releaseId, newStatus)

            val result = mapOf(
                "success" to true,
                "release" to mapOf(
                    "id" to updatedRelease.id,
                    "version" to updatedRelease.version,
                    "name" to updatedRelease.name,
                    "status" to updatedRelease.status.name,
                    "updatedAt" to updatedRelease.updatedAt?.toString()
                ),
                "message" to "Release ${updatedRelease.version} is now ACTIVE. Any previously active release has been set to ARCHIVED."
            )

            return McpToolResult.success(result)

        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Release not found")
        } catch (e: IllegalStateException) {
            return McpToolResult.error("CONFLICT", e.message ?: "Invalid status transition")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to update release status: ${e.message}")
        }
    }
}
