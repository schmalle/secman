package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.RequirementReview.ReviewAssessment
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AlignmentReviewerRepository
import com.secman.service.AlignmentService
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for submitting a review on a requirement change.
 * Feature: 068-requirements-alignment-process
 *
 * Allows REQ-role users to submit their assessment (OK/CHANGE/NOGO)
 * and optional comments on requirement changes.
 *
 * Accessible by: REQ role users (via User Delegation)
 */
@Singleton
class SubmitReviewTool(
    @Inject private val alignmentService: AlignmentService,
    @Inject private val alignmentReviewerRepository: AlignmentReviewerRepository
) : McpTool {

    override val name = "submit_review"
    override val description = "Submit a review assessment for a requirement change in an alignment session. Assessment can be OK (acceptable), CHANGE (needs rework), or NOGO (not acceptable)."
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "session_id" to mapOf(
                "type" to "number",
                "description" to "ID of the alignment session"
            ),
            "snapshot_id" to mapOf(
                "type" to "number",
                "description" to "ID of the requirement snapshot to review"
            ),
            "assessment" to mapOf(
                "type" to "string",
                "description" to "Assessment of the change: OK (acceptable), CHANGE (needs rework), or NOGO (not acceptable)",
                "enum" to listOf("OK", "CHANGE", "NOGO")
            ),
            "comment" to mapOf(
                "type" to "string",
                "description" to "Optional comment explaining the assessment"
            )
        ),
        "required" to listOf("session_id", "snapshot_id", "assessment")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Check authorization - require User Delegation with REQ role
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        val userRoles = context.delegatedUserRoles?.map { it.uppercase() } ?: emptyList()
        if (!userRoles.contains("REQ") && !userRoles.contains("ADMIN")) {
            return McpToolResult.error(
                "AUTHORIZATION_ERROR",
                "REQ or ADMIN role required to submit reviews"
            )
        }

        // Extract parameters
        val sessionId = (arguments["session_id"] as? Number)?.toLong()
        val snapshotId = (arguments["snapshot_id"] as? Number)?.toLong()
        val assessmentStr = arguments["assessment"] as? String
        val comment = arguments["comment"] as? String

        if (sessionId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "session_id is required")
        }
        if (snapshotId == null) {
            return McpToolResult.error("VALIDATION_ERROR", "snapshot_id is required")
        }
        if (assessmentStr.isNullOrBlank()) {
            return McpToolResult.error("VALIDATION_ERROR", "assessment is required")
        }

        val assessment = try {
            ReviewAssessment.valueOf(assessmentStr.uppercase())
        } catch (e: IllegalArgumentException) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "Invalid assessment. Must be one of: OK, CHANGE, NOGO"
            )
        }

        try {
            // Find the reviewer for this user in this session
            val reviewer = alignmentReviewerRepository.findBySession_IdAndUser_Id(
                sessionId,
                context.delegatedUserId!!
            ).orElseThrow {
                NoSuchElementException("You are not a reviewer for this alignment session")
            }

            val review = alignmentService.submitReview(
                reviewerId = reviewer.id!!,
                snapshotId = snapshotId,
                assessment = assessment,
                comment = comment
            )

            val response = mapOf(
                "success" to true,
                "review" to mapOf(
                    "id" to review.id,
                    "snapshotId" to review.snapshot.id,
                    "requirementId" to review.snapshot.requirementInternalId,
                    "assessment" to review.assessment.name,
                    "comment" to review.comment,
                    "createdAt" to review.createdAt?.toString(),
                    "updatedAt" to review.updatedAt?.toString()
                ),
                "reviewerProgress" to mapOf(
                    "reviewedCount" to reviewer.reviewedCount,
                    "status" to reviewer.status.name
                ),
                "message" to "Review submitted: ${assessment.name} for requirement ${review.snapshot.requirementInternalId}"
            )

            return McpToolResult.success(response)

        } catch (e: IllegalStateException) {
            return McpToolResult.error("CONFLICT_ERROR", e.message ?: "Cannot submit review")
        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid request")
        } catch (e: NoSuchElementException) {
            return McpToolResult.error("NOT_FOUND", e.message ?: "Resource not found")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to submit review: ${e.message}")
        }
    }
}
