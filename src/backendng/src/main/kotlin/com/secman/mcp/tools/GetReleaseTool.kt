package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.RequirementSnapshotRepository
import com.secman.service.ReleaseService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for getting details of a single release.
 * Feature: MCP Release Management
 *
 * Returns release metadata and optionally the requirement snapshots.
 *
 * Accessible by: ADMIN, RELEASE_MANAGER roles (via User Delegation)
 */
@Singleton
class GetReleaseTool(
    @Inject private val releaseService: ReleaseService,
    @Inject private val snapshotRepository: RequirementSnapshotRepository
) : McpTool {

    override val name = "get_release"
    override val description = "Get details of a specific release by ID, including requirement count and optionally the snapshotted requirements."
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "releaseId" to mapOf(
                "type" to "number",
                "description" to "ID of the release to retrieve"
            ),
            "includeRequirements" to mapOf(
                "type" to "boolean",
                "description" to "Whether to include the snapshotted requirements (default: false)",
                "default" to false
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
                "ADMIN or RELEASE_MANAGER role required to view release details"
            )
        }

        // Extract parameters
        val releaseId = (arguments["releaseId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "releaseId is required")

        val includeRequirements = arguments["includeRequirements"] as? Boolean ?: false

        try {
            val release = releaseService.getReleaseById(releaseId)
            val snapshotCount = snapshotRepository.countByReleaseId(releaseId)

            val releaseData = mutableMapOf<String, Any?>(
                "id" to release.id,
                "version" to release.version,
                "name" to release.name,
                "description" to release.description,
                "status" to release.status.name,
                "releaseDate" to release.releaseDate?.toString(),
                "requirementCount" to snapshotCount,
                "createdBy" to release.createdBy?.username,
                "createdAt" to release.createdAt?.toString(),
                "updatedAt" to release.updatedAt?.toString()
            )

            if (includeRequirements) {
                val snapshots = snapshotRepository.findByReleaseId(releaseId)
                releaseData["requirements"] = snapshots.map { snapshot ->
                    mapOf(
                        "snapshotId" to snapshot.id,
                        "originalRequirementId" to snapshot.originalRequirementId,
                        "internalId" to snapshot.internalId,
                        "revision" to snapshot.revision,
                        "shortreq" to snapshot.shortreq,
                        "details" to snapshot.details,
                        "chapter" to snapshot.chapter,
                        "language" to snapshot.language,
                        "motivation" to snapshot.motivation,
                        "example" to snapshot.example,
                        "usecase" to snapshot.usecase,
                        "norm" to snapshot.norm,
                        "snapshotTimestamp" to snapshot.snapshotTimestamp.toString()
                    )
                }
            }

            val result = mapOf(
                "release" to releaseData
            )

            return McpToolResult.success(result)

        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Release not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to get release: ${e.message}")
        }
    }
}
