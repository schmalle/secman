package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RequirementComparisonService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for comparing two releases and showing requirement differences.
 * Feature: MCP Release Management
 *
 * Compares requirement snapshots between two releases and returns added,
 * deleted, modified, and unchanged counts with field-level diffs.
 *
 * Accessible by: ADMIN, RELEASE_MANAGER roles (via User Delegation)
 *
 * Input parameters:
 * - fromReleaseId (required): Baseline release ID
 * - toReleaseId (required): Comparison release ID
 *
 * Output:
 * - fromRelease: Baseline release info (id, version, name)
 * - toRelease: Comparison release info (id, version, name)
 * - summary: Counts of added, deleted, modified, unchanged requirements
 * - added: List of requirements added in the target release
 * - deleted: List of requirements removed in the target release
 * - modified: List of changed requirements with field-level diffs
 */
@Singleton
class CompareReleasesTool(
    @Inject private val comparisonService: RequirementComparisonService
) : McpTool {

    override val name = "compare_releases"
    override val description = "Compare two releases and show requirement differences (added, deleted, modified, unchanged). Requires ADMIN or RELEASE_MANAGER role and User Delegation."
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "fromReleaseId" to mapOf(
                "type" to "number",
                "description" to "Baseline release ID (the older release to compare from)"
            ),
            "toReleaseId" to mapOf(
                "type" to "number",
                "description" to "Target release ID (the newer release to compare to)"
            )
        ),
        "required" to listOf("fromReleaseId", "toReleaseId")
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
                "ADMIN or RELEASE_MANAGER role required to compare releases"
            )
        }

        // Extract parameters
        val fromReleaseId = (arguments["fromReleaseId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "fromReleaseId is required")

        val toReleaseId = (arguments["toReleaseId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "toReleaseId is required")

        try {
            val result = comparisonService.compare(fromReleaseId, toReleaseId)

            val response = mapOf(
                "fromRelease" to mapOf(
                    "id" to result.fromRelease.id,
                    "version" to result.fromRelease.version,
                    "name" to result.fromRelease.name,
                    "createdAt" to result.fromRelease.createdAt.toString()
                ),
                "toRelease" to mapOf(
                    "id" to result.toRelease.id,
                    "version" to result.toRelease.version,
                    "name" to result.toRelease.name,
                    "createdAt" to result.toRelease.createdAt.toString()
                ),
                "summary" to mapOf(
                    "addedCount" to result.added.size,
                    "deletedCount" to result.deleted.size,
                    "modifiedCount" to result.modified.size,
                    "unchangedCount" to result.unchanged
                ),
                "added" to result.added.map { req ->
                    mapOf(
                        "id" to req.originalRequirementId,
                        "internalId" to req.internalId,
                        "revision" to req.revision,
                        "shortreq" to req.shortreq,
                        "details" to req.details
                    )
                },
                "deleted" to result.deleted.map { req ->
                    mapOf(
                        "id" to req.originalRequirementId,
                        "internalId" to req.internalId,
                        "revision" to req.revision,
                        "shortreq" to req.shortreq,
                        "details" to req.details
                    )
                },
                "modified" to result.modified.map { diff ->
                    mapOf(
                        "id" to diff.id,
                        "internalId" to diff.internalId,
                        "oldRevision" to diff.oldRevision,
                        "newRevision" to diff.newRevision,
                        "shortreq" to diff.shortreq,
                        "changes" to diff.changes.map { change ->
                            mapOf(
                                "field" to change.fieldName,
                                "oldValue" to change.oldValue,
                                "newValue" to change.newValue
                            )
                        }
                    )
                }
            )

            return McpToolResult.success(response)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid release IDs")
        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Release not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to compare releases: ${e.message}")
        }
    }
}
