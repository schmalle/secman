package com.secman.controller

import com.secman.domain.AlignmentReviewer.ReviewerStatus
import com.secman.domain.AlignmentSession.AlignmentStatus
import com.secman.domain.AlignmentSnapshot.ChangeType
import com.secman.domain.RequirementReview.ReviewAssessment
import com.secman.repository.UserRepository
import com.secman.service.AlignmentEmailService
import com.secman.service.AlignmentService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import org.slf4j.LoggerFactory

/**
 * REST controller for the Requirements Alignment Process.
 * Feature: 068-requirements-alignment-process
 *
 * Provides endpoints for:
 * - Starting alignment on DRAFT releases
 * - Submitting requirement reviews
 * - Viewing alignment status and feedback
 * - Finalizing/cancelling alignment sessions
 */
@Controller("/api")
class AlignmentController(
    @Inject private val alignmentService: AlignmentService,
    @Inject private val alignmentEmailService: AlignmentEmailService,
    @Inject private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(AlignmentController::class.java)

    // ========== Release-Based Endpoints ==========

    /**
     * POST /api/releases/{id}/alignment/start - Start alignment process
     * Authorization: ADMIN or RELEASE_MANAGER only
     */
    @Post("/releases/{releaseId}/alignment/start")
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun startAlignment(
        @PathVariable releaseId: Long,
        authentication: Authentication
    ): HttpResponse<Map<String, Any>> {
        logger.info("Starting alignment for release {} by user {}", releaseId, authentication.name)

        try {
            val user = userRepository.findByUsername(authentication.name)
                .orElseThrow { NoSuchElementException("User not found: ${authentication.name}") }

            val result = alignmentService.startAlignment(releaseId, user.id!!)

            // Send notification emails to all reviewers
            alignmentEmailService.sendReviewRequestEmails(result.session, result.reviewers)

            return HttpResponse.status<Map<String, Any>>(HttpStatus.CREATED)
                .body(mapOf(
                    "success" to true,
                    "message" to "Alignment process started successfully",
                    "session" to toSessionResponse(result.session),
                    "reviewerCount" to result.reviewers.size,
                    "changedRequirements" to result.changedRequirements,
                    "changes" to mapOf(
                        "added" to result.addedCount,
                        "modified" to result.modifiedCount,
                        "deleted" to result.deletedCount
                    )
                ))
        } catch (e: IllegalArgumentException) {
            logger.warn("Failed to start alignment: ${e.message}")
            return HttpResponse.badRequest(mapOf(
                "error" to "Bad Request",
                "message" to (e.message ?: "Invalid request")
            ))
        } catch (e: IllegalStateException) {
            logger.warn("Cannot start alignment: ${e.message}")
            return HttpResponse.status<Map<String, Any>>(HttpStatus.CONFLICT).body(mapOf(
                "error" to "Conflict",
                "message" to (e.message ?: "Cannot start alignment")
            ))
        } catch (e: NoSuchElementException) {
            logger.warn("Resource not found: ${e.message}")
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Resource not found")
            ))
        }
    }

    /**
     * GET /api/releases/{id}/alignment - Get alignment status for a release
     */
    @Get("/releases/{releaseId}/alignment")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun getAlignmentByRelease(@PathVariable releaseId: Long): HttpResponse<*> {
        logger.debug("Getting alignment status for release {}", releaseId)

        val status = alignmentService.getAlignmentStatusByReleaseId(releaseId)
            ?: return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to "No active alignment session for this release"
            ))

        return HttpResponse.ok(toAlignmentStatusResponse(status))
    }

    /**
     * GET /api/releases/{id}/alignment/check - Check if release has changes to review
     */
    @Get("/releases/{releaseId}/alignment/check")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun checkAlignmentRequired(@PathVariable releaseId: Long): HttpResponse<Map<String, Any>> {
        try {
            val hasChanges = alignmentService.hasChangesToReview(releaseId)
            return HttpResponse.ok(mapOf(
                "hasChanges" to hasChanges,
                "releaseId" to releaseId
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Release not found")
            ))
        }
    }

    // ========== Session-Based Endpoints ==========

    /**
     * GET /api/alignment/{sessionId} - Get alignment session details
     */
    @Get("/alignment/{sessionId}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun getAlignmentSession(@PathVariable sessionId: Long): HttpResponse<*> {
        try {
            val status = alignmentService.getAlignmentStatus(sessionId)
            return HttpResponse.ok(toAlignmentStatusResponse(status))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Alignment session not found")
            ))
        }
    }

    /**
     * GET /api/alignment/{sessionId}/snapshots - Get requirement changes to review
     */
    @Get("/alignment/{sessionId}/snapshots")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun getAlignmentSnapshots(
        @PathVariable sessionId: Long,
        @QueryValue changeType: ChangeType?
    ): HttpResponse<Map<String, Any>> {
        try {
            val snapshots = alignmentService.getSnapshotsForSession(sessionId)
            val filtered = if (changeType != null) {
                snapshots.filter { it.changeType == changeType }
            } else {
                snapshots
            }

            return HttpResponse.ok(mapOf(
                "sessionId" to sessionId,
                "snapshots" to filtered.map { snapshot ->
                    mapOf(
                        "id" to snapshot.id,
                        "requirementId" to snapshot.requirementInternalId,
                        "changeType" to snapshot.changeType.name,
                        "shortreq" to snapshot.shortreq,
                        "previousShortreq" to snapshot.previousShortreq,
                        "details" to snapshot.details,
                        "previousDetails" to snapshot.previousDetails,
                        "chapter" to snapshot.chapter,
                        "previousChapter" to snapshot.previousChapter,
                        "versionNumber" to snapshot.versionNumber,
                        "baselineVersionNumber" to snapshot.baselineVersionNumber,
                        "changeSummary" to snapshot.getChangeSummary()
                    )
                },
                "totalCount" to snapshots.size,
                "filteredCount" to filtered.size
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Session not found")
            ))
        }
    }

    /**
     * GET /api/alignment/{sessionId}/reviewers - Get reviewer details
     */
    @Get("/alignment/{sessionId}/reviewers")
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun getAlignmentReviewers(@PathVariable sessionId: Long): HttpResponse<Map<String, Any>> {
        try {
            val summaries = alignmentService.getReviewerSummaries(sessionId)

            return HttpResponse.ok(mapOf(
                "sessionId" to sessionId,
                "reviewers" to summaries.map { summary ->
                    mapOf(
                        "id" to summary.reviewer.id,
                        "userId" to summary.reviewer.user.id,
                        "username" to summary.reviewer.user.username,
                        "email" to summary.reviewer.user.email,
                        "status" to summary.reviewer.status.name,
                        "reviewedCount" to summary.reviewedCount,
                        "totalCount" to summary.totalCount,
                        "completionPercent" to if (summary.totalCount > 0) {
                            (summary.reviewedCount * 100 / summary.totalCount)
                        } else 0,
                        "assessments" to mapOf(
                            "minor" to summary.assessments.minorCount,
                            "major" to summary.assessments.majorCount,
                            "nok" to summary.assessments.nokCount
                        ),
                        "startedAt" to summary.reviewer.startedAt?.toString(),
                        "completedAt" to summary.reviewer.completedAt?.toString(),
                        "notifiedAt" to summary.reviewer.notifiedAt?.toString(),
                        "reminderCount" to summary.reviewer.reminderCount
                    )
                },
                "totalReviewers" to summaries.size
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Session not found")
            ))
        }
    }

    /**
     * GET /api/alignment/{sessionId}/feedback - Get aggregated feedback per requirement
     */
    @Get("/alignment/{sessionId}/feedback")
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun getAlignmentFeedback(@PathVariable sessionId: Long): HttpResponse<Map<String, Any>> {
        try {
            val summaries = alignmentService.getRequirementReviewSummaries(sessionId)

            return HttpResponse.ok(mapOf(
                "sessionId" to sessionId,
                "requirements" to summaries.map { summary ->
                    mapOf(
                        "snapshotId" to summary.snapshot.id,
                        "requirementId" to summary.snapshot.requirementInternalId,
                        "shortreq" to summary.snapshot.shortreq,
                        "changeType" to summary.snapshot.changeType.name,
                        "reviewCount" to summary.reviewCount,
                        "assessments" to mapOf(
                            "minor" to summary.assessments.minorCount,
                            "major" to summary.assessments.majorCount,
                            "nok" to summary.assessments.nokCount
                        ),
                        "reviews" to summary.reviews.map { review ->
                            mapOf(
                                "id" to review.id,
                                "reviewerName" to review.reviewer.user.username,
                                "assessment" to review.assessment.name,
                                "comment" to review.comment,
                                "createdAt" to review.createdAt?.toString(),
                                "updatedAt" to review.updatedAt?.toString()
                            )
                        }
                    )
                },
                "totalRequirements" to summaries.size
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Session not found")
            ))
        }
    }

    /**
     * POST /api/alignment/{sessionId}/remind - Send reminder emails
     */
    @Post("/alignment/{sessionId}/remind")
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun sendReminders(@PathVariable sessionId: Long): HttpResponse<Map<String, Any>> {
        try {
            val status = alignmentService.getAlignmentStatus(sessionId)
            if (status.session.status != AlignmentStatus.OPEN) {
                return HttpResponse.badRequest(mapOf(
                    "error" to "Bad Request",
                    "message" to "Cannot send reminders for closed sessions"
                ))
            }

            val incompleteReviewers = alignmentService.getIncompleteReviewers(sessionId)
            if (incompleteReviewers.isEmpty()) {
                return HttpResponse.ok(mapOf(
                    "success" to true,
                    "message" to "All reviewers have completed their reviews",
                    "remindersSent" to 0
                ))
            }

            val results = alignmentEmailService.sendReminderEmails(status.session, incompleteReviewers)
            val successCount = results.values.count { it }

            return HttpResponse.ok(mapOf(
                "success" to true,
                "message" to "Reminders sent to $successCount of ${incompleteReviewers.size} reviewers",
                "remindersSent" to successCount,
                "totalIncomplete" to incompleteReviewers.size
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Session not found")
            ))
        }
    }

    /**
     * POST /api/alignment/{sessionId}/finalize - Finalize alignment
     */
    @Post("/alignment/{sessionId}/finalize")
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun finalizeAlignment(
        @PathVariable sessionId: Long,
        @Body request: FinalizeAlignmentRequest
    ): HttpResponse<Map<String, Any>> {
        logger.info("Finalizing alignment session {} (activate={})", sessionId, request.activateRelease)

        try {
            val session = alignmentService.finalizeAlignment(
                sessionId = sessionId,
                activateRelease = request.activateRelease,
                notes = request.notes
            )

            return HttpResponse.ok(mapOf(
                "success" to true,
                "message" to if (request.activateRelease) {
                    "Alignment completed and release activated"
                } else {
                    "Alignment completed, release returned to DRAFT"
                },
                "session" to toSessionResponse(session)
            ))
        } catch (e: IllegalStateException) {
            return HttpResponse.badRequest(mapOf(
                "error" to "Bad Request",
                "message" to (e.message ?: "Cannot finalize session")
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Session not found")
            ))
        }
    }

    /**
     * POST /api/alignment/{sessionId}/cancel - Cancel alignment
     */
    @Post("/alignment/{sessionId}/cancel")
    @Secured("ADMIN", "RELEASE_MANAGER")
    fun cancelAlignment(
        @PathVariable sessionId: Long,
        @Body request: CancelAlignmentRequest?
    ): HttpResponse<Map<String, Any>> {
        logger.info("Cancelling alignment session {}", sessionId)

        try {
            val session = alignmentService.cancelAlignment(sessionId, request?.notes)

            return HttpResponse.ok(mapOf(
                "success" to true,
                "message" to "Alignment cancelled, release returned to DRAFT",
                "session" to toSessionResponse(session)
            ))
        } catch (e: IllegalStateException) {
            return HttpResponse.badRequest(mapOf(
                "error" to "Bad Request",
                "message" to (e.message ?: "Cannot cancel session")
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Session not found")
            ))
        }
    }

    // ========== Review Submission Endpoints ==========

    /**
     * POST /api/alignment/review/{token} - Submit review using token (email link)
     * This endpoint is public but requires valid token
     */
    @Post("/alignment/review/{token}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun submitReviewByToken(
        @PathVariable token: String,
        @Body request: SubmitReviewRequest
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Submitting review via token for snapshot {}", request.snapshotId)

        try {
            val review = alignmentService.submitReviewByToken(
                reviewToken = token,
                snapshotId = request.snapshotId,
                assessment = request.assessment,
                comment = request.comment
            )

            return HttpResponse.ok(mapOf(
                "success" to true,
                "reviewId" to review.id!!,
                "assessment" to review.assessment.name,
                "message" to "Review submitted successfully"
            ))
        } catch (e: IllegalStateException) {
            return HttpResponse.badRequest(mapOf(
                "error" to "Bad Request",
                "message" to (e.message ?: "Cannot submit review")
            ))
        } catch (e: IllegalArgumentException) {
            return HttpResponse.badRequest(mapOf(
                "error" to "Bad Request",
                "message" to (e.message ?: "Invalid request")
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Invalid token or snapshot")
            ))
        }
    }

    /**
     * GET /api/alignment/review/{token} - Get reviewer info and requirements (via token)
     */
    @Get("/alignment/review/{token}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun getReviewPageData(@PathVariable token: String): HttpResponse<Map<String, Any>> {
        try {
            val reviewer = alignmentService.getReviewerByToken(token)
            val session = reviewer.session
            val snapshots = alignmentService.getSnapshotsForSession(session.id!!)
            val existingReviews = alignmentService.getReviewsByReviewer(reviewer.id!!)
                .associateBy { it.snapshot.id }

            return HttpResponse.ok(mapOf(
                "reviewer" to mapOf(
                    "id" to reviewer.id,
                    "username" to reviewer.user.username,
                    "status" to reviewer.status.name,
                    "reviewedCount" to reviewer.reviewedCount
                ),
                "session" to mapOf(
                    "id" to session.id,
                    "status" to session.status.name,
                    "releaseName" to session.release.name,
                    "releaseVersion" to session.release.version,
                    "changedCount" to session.changedRequirementsCount,
                    "startedAt" to session.startedAt?.toString()
                ),
                "snapshots" to snapshots.map { snapshot ->
                    val existingReview = existingReviews[snapshot.id]
                    mapOf(
                        "id" to snapshot.id,
                        "requirementId" to snapshot.requirementInternalId,
                        "changeType" to snapshot.changeType.name,
                        "shortreq" to snapshot.shortreq,
                        "previousShortreq" to snapshot.previousShortreq,
                        "details" to snapshot.details,
                        "previousDetails" to snapshot.previousDetails,
                        "chapter" to snapshot.chapter,
                        "previousChapter" to snapshot.previousChapter,
                        "changeSummary" to snapshot.getChangeSummary(),
                        "existingReview" to if (existingReview != null) mapOf(
                            "id" to existingReview.id,
                            "assessment" to existingReview.assessment.name,
                            "comment" to existingReview.comment
                        ) else null
                    )
                },
                "isOpen" to session.isOpen()
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to "Invalid or expired review token"
            ))
        }
    }

    /**
     * POST /api/alignment/review/{token}/complete - Mark review as complete
     */
    @Post("/alignment/review/{token}/complete")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun completeReviewByToken(@PathVariable token: String): HttpResponse<Map<String, Any>> {
        try {
            val reviewer = alignmentService.getReviewerByToken(token)
            val completedReviewer = alignmentService.completeReview(reviewer.id!!)

            return HttpResponse.ok(mapOf(
                "success" to true,
                "message" to "Review marked as complete",
                "status" to completedReviewer.status.name,
                "reviewedCount" to completedReviewer.reviewedCount
            ))
        } catch (e: IllegalStateException) {
            return HttpResponse.badRequest(mapOf(
                "error" to "Bad Request",
                "message" to (e.message ?: "Cannot complete review")
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to "Invalid or expired review token"
            ))
        }
    }

    // ========== Helper Methods ==========

    private fun toSessionResponse(session: com.secman.domain.AlignmentSession): Map<String, Any?> {
        return mapOf(
            "id" to session.id,
            "releaseId" to session.release.id,
            "releaseName" to session.release.name,
            "releaseVersion" to session.release.version,
            "status" to session.status.name,
            "changedRequirementsCount" to session.changedRequirementsCount,
            "initiatedBy" to session.initiatedBy.username,
            "baselineReleaseId" to session.baselineRelease?.id,
            "startedAt" to session.startedAt?.toString(),
            "completedAt" to session.completedAt?.toString(),
            "completionNotes" to session.completionNotes
        )
    }

    private fun toAlignmentStatusResponse(status: AlignmentService.AlignmentStatusResult): Map<String, Any?> {
        return mapOf(
            "session" to toSessionResponse(status.session),
            "reviewers" to mapOf(
                "total" to status.totalReviewers,
                "completed" to status.completedReviewers,
                "inProgress" to status.inProgressReviewers,
                "pending" to status.pendingReviewers,
                "completionPercent" to if (status.totalReviewers > 0) {
                    (status.completedReviewers * 100 / status.totalReviewers)
                } else 0
            ),
            "requirements" to mapOf(
                "total" to status.totalRequirements,
                "reviewed" to status.reviewedRequirements
            ),
            "assessments" to mapOf(
                "minor" to status.assessmentSummary.minorCount,
                "major" to status.assessmentSummary.majorCount,
                "nok" to status.assessmentSummary.nokCount
            )
        )
    }
}

// ========== Request DTOs ==========

@Serdeable
data class SubmitReviewRequest(
    val snapshotId: Long,
    val assessment: ReviewAssessment,
    val comment: String? = null
)

@Serdeable
data class FinalizeAlignmentRequest(
    val activateRelease: Boolean = true,
    val notes: String? = null
)

@Serdeable
data class CancelAlignmentRequest(
    val notes: String? = null
)
