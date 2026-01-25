package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.RequirementSnapshotRepository
import com.secman.service.ReleaseService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for creating a new release with requirement snapshots.
 * Feature: MCP Release Management
 *
 * Creates a release in DRAFT status with all current requirements snapshotted.
 * Version must follow semantic versioning format (MAJOR.MINOR.PATCH).
 *
 * Accessible by: ADMIN, RELEASE_MANAGER roles (via User Delegation)
 */
@Singleton
class CreateReleaseTool(
    @Inject private val releaseService: ReleaseService,
    @Inject private val snapshotRepository: RequirementSnapshotRepository
) : McpTool {

    override val name = "create_release"
    override val description = "Create a new release with requirement snapshots. Version must follow semantic versioning (MAJOR.MINOR.PATCH). New releases start in DRAFT status."
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "version" to mapOf(
                "type" to "string",
                "description" to "Semantic version (MAJOR.MINOR.PATCH, e.g., '1.0.0')"
            ),
            "name" to mapOf(
                "type" to "string",
                "description" to "Human-readable release name"
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "Optional detailed description of the release"
            )
        ),
        "required" to listOf("version", "name")
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
                "ADMIN or RELEASE_MANAGER role required to create releases"
            )
        }

        // Extract and validate required parameters
        val version = (arguments["version"] as? String)?.trim()
        val name = (arguments["name"] as? String)?.trim()
        val description = arguments["description"] as? String

        if (version.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Version is required")
        }

        if (name.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "Name is required")
        }

        try {
            val release = releaseService.createReleaseForUser(
                version = version,
                name = name,
                description = description,
                userId = context.delegatedUserId!!
            )

            val snapshotCount = snapshotRepository.countByReleaseId(release.id!!)

            val result = mapOf(
                "success" to true,
                "release" to mapOf(
                    "id" to release.id,
                    "version" to release.version,
                    "name" to release.name,
                    "description" to release.description,
                    "status" to release.status.name,
                    "requirementCount" to snapshotCount,
                    "createdBy" to release.createdBy?.username,
                    "createdAt" to release.createdAt?.toString()
                ),
                "message" to "Release ${release.version} created successfully with $snapshotCount requirement snapshots"
            )

            return McpToolResult.success(result)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid input")
        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Resource not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to create release: ${e.message}")
        }
    }
}
