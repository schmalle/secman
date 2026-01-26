package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AlignmentEmailService
import com.secman.service.AlignmentService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for starting a requirements alignment process.
 * Feature: 068-requirements-alignment-process
 *
 * Initiates the alignment process for a DRAFT release, notifying all
 * REQ-role users to review requirement changes.
 *
 * Accessible by: ADMIN, RELEASE_MANAGER roles (via User Delegation)
 */
@Singleton
class StartAlignmentTool(
    @Inject private val alignmentService: AlignmentService,
    @Inject private val alignmentEmailService: AlignmentEmailService
) : McpTool {

    override val name = "start_alignment"
    override val description = "Start a requirements alignment process for a DRAFT release. Notifies all REQ-role users to review requirement changes."
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "release_id" to mapOf(
                "type" to "number",
                "description" to "ID of the DRAFT release to start alignment for"
            ),
            "send_notifications" to mapOf(
                "type" to "boolean",
                "description" to "Whether to send email notifications to reviewers (default: true)"
            )
        ),
        "required" to listOf("release_id")
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
                "ADMIN or RELEASE_MANAGER role required to start alignment"
            )
        }

        // Extract parameters
        val releaseId = (arguments["release_id"] as? Number)?.toLong()
        val sendNotifications = arguments["send_notifications"] as? Boolean ?: true

        if (releaseId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "release_id is required")
        }

        try {
            val result = alignmentService.startAlignment(releaseId, context.delegatedUserId!!)

            // Send notifications if requested
            if (sendNotifications && result.reviewers.isNotEmpty()) {
                alignmentEmailService.sendReviewRequestEmails(result.session, result.reviewers)
            }

            val response = mapOf(
                "success" to true,
                "session" to mapOf(
                    "id" to result.session.id,
                    "releaseId" to result.session.release.id,
                    "releaseVersion" to result.session.release.version,
                    "releaseName" to result.session.release.name,
                    "status" to result.session.status.name,
                    "startedAt" to result.session.startedAt?.toString()
                ),
                "reviewers" to result.reviewers.map { reviewer ->
                    mapOf(
                        "id" to reviewer.id,
                        "userId" to reviewer.user.id,
                        "username" to reviewer.user.username,
                        "email" to reviewer.user.email,
                        "reviewToken" to reviewer.reviewToken
                    )
                },
                "changes" to mapOf(
                    "total" to result.changedRequirements,
                    "added" to result.addedCount,
                    "modified" to result.modifiedCount,
                    "deleted" to result.deletedCount
                ),
                "message" to "Alignment started for release ${result.session.release.version}. ${result.reviewers.size} reviewers notified about ${result.changedRequirements} changes."
            )

            return McpToolResult.success(response)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid request")
        } catch (e: IllegalStateException) {
            return McpToolResult.error("CONFLICT_ERROR", e.message ?: "Cannot start alignment")
        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Resource not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to start alignment: ${e.message}")
        }
    }
}
