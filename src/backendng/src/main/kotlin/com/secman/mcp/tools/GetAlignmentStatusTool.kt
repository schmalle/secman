package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AlignmentService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for querying alignment status and progress.
 * Feature: 068-requirements-alignment-process
 *
 * Returns detailed status including reviewer progress, assessment
 * summary, and requirement change breakdown.
 *
 * Accessible by: Any authenticated user with delegation
 */
@Singleton
class GetAlignmentStatusTool(
    @Inject private val alignmentService: AlignmentService
) : McpTool {

    override val name = "get_alignment_status"
    override val description = "Get the status and progress of a requirements alignment session, including reviewer completion rates and assessment summaries."
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "session_id" to mapOf(
                "type" to "number",
                "description" to "ID of the alignment session (use this OR release_id)"
            ),
            "release_id" to mapOf(
                "type" to "number",
                "description" to "ID of the release to get alignment status for (use this OR session_id)"
            ),
            "include_reviewers" to mapOf(
                "type" to "boolean",
                "description" to "Include detailed reviewer progress (default: false, requires ADMIN or RELEASE_MANAGER)"
            ),
            "include_feedback" to mapOf(
                "type" to "boolean",
                "description" to "Include per-requirement feedback summary (default: false, requires ADMIN or RELEASE_MANAGER)"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Check authorization
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Extract parameters
        val sessionId = (arguments["session_id"] as? Number)?.toLong()
        val releaseId = (arguments["release_id"] as? Number)?.toLong()
        val includeReviewers = arguments["include_reviewers"] as? Boolean ?: false
        val includeFeedback = arguments["include_feedback"] as? Boolean ?: false

        if (sessionId == null && releaseId == null) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "Either session_id or release_id is required"
            )
        }

        // Check role for detailed info
        val userRoles = context.delegatedUserRoles?.map { it.uppercase() } ?: emptyList()
        val hasManagerAccess = userRoles.contains("ADMIN") || userRoles.contains("RELEASE_MANAGER")

        if ((includeReviewers || includeFeedback) && !hasManagerAccess) {
            return McpToolResult.error(
                "AUTHORIZATION_ERROR",
                "ADMIN or RELEASE_MANAGER role required for detailed reviewer/feedback information"
            )
        }

        try {
            // Get status
            val status = if (sessionId != null) {
                alignmentService.getAlignmentStatus(sessionId)
            } else {
                alignmentService.getAlignmentStatusByReleaseId(releaseId!!)
                    ?: return McpToolResult.error(
                        "NOT_FOUND",
                        "No active alignment session for release $releaseId"
                    )
            }

            val response = mutableMapOf<String, Any>(
                "session" to mapOf(
                    "id" to status.session.id,
                    "releaseId" to status.session.release.id,
                    "releaseVersion" to status.session.release.version,
                    "releaseName" to status.session.release.name,
                    "status" to status.session.status.name,
                    "changedRequirementsCount" to status.session.changedRequirementsCount,
                    "initiatedBy" to status.session.initiatedBy.username,
                    "baselineReleaseId" to status.session.baselineRelease?.id,
                    "startedAt" to status.session.startedAt?.toString(),
                    "completedAt" to status.session.completedAt?.toString()
                ),
                "progress" to mapOf(
                    "totalReviewers" to status.totalReviewers,
                    "completedReviewers" to status.completedReviewers,
                    "inProgressReviewers" to status.inProgressReviewers,
                    "pendingReviewers" to status.pendingReviewers,
                    "completionPercent" to if (status.totalReviewers > 0) {
                        (status.completedReviewers * 100 / status.totalReviewers)
                    } else 0,
                    "totalRequirements" to status.totalRequirements,
                    "reviewedRequirements" to status.reviewedRequirements
                ),
                "assessments" to mapOf(
                    "minor" to status.assessmentSummary.minorCount,
                    "major" to status.assessmentSummary.majorCount,
                    "nok" to status.assessmentSummary.nokCount,
                    "total" to (status.assessmentSummary.minorCount +
                                status.assessmentSummary.majorCount +
                                status.assessmentSummary.nokCount)
                )
            )

            // Add detailed reviewer info if requested
            if (includeReviewers && hasManagerAccess) {
                val reviewerSummaries = alignmentService.getReviewerSummaries(status.session.id!!)
                response["reviewers"] = reviewerSummaries.map { summary ->
                    mapOf(
                        "id" to summary.reviewer.id,
                        "username" to summary.reviewer.user.username,
                        "email" to summary.reviewer.user.email,
                        "status" to summary.reviewer.status.name,
                        "reviewedCount" to summary.reviewedCount,
                        "totalCount" to summary.totalCount,
                        "assessments" to mapOf(
                            "minor" to summary.assessments.minorCount,
                            "major" to summary.assessments.majorCount,
                            "nok" to summary.assessments.nokCount
                        )
                    )
                }
            }

            // Add per-requirement feedback if requested
            if (includeFeedback && hasManagerAccess) {
                val feedbackSummaries = alignmentService.getRequirementReviewSummaries(status.session.id!!)
                response["feedback"] = feedbackSummaries.map { summary ->
                    mapOf(
                        "snapshotId" to summary.snapshot.id,
                        "requirementId" to summary.snapshot.requirementInternalId,
                        "changeType" to summary.snapshot.changeType.name,
                        "shortreq" to summary.snapshot.shortreq.take(100),
                        "reviewCount" to summary.reviewCount,
                        "assessments" to mapOf(
                            "minor" to summary.assessments.minorCount,
                            "major" to summary.assessments.majorCount,
                            "nok" to summary.assessments.nokCount
                        )
                    )
                }
            }

            return McpToolResult.success(response)

        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Resource not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to get alignment status: ${e.message}")
        }
    }
}
