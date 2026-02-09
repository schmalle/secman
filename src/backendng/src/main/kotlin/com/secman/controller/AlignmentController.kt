package com.secman.controller

import com.secman.domain.AlignmentReviewer.ReviewerStatus
import com.secman.domain.AlignmentSession.AlignmentStatus
import com.secman.domain.AlignmentSnapshot.ChangeType
import com.secman.domain.RequirementReview.ReviewAssessment
import com.secman.domain.ReviewDecision.Decision
import com.secman.repository.UserRepository
import com.secman.service.AlignmentEmailService
import com.secman.service.AlignmentService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import org.apache.poi.ss.usermodel.DataValidationConstraint
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
        @Body request: StartAlignmentRequest?,
        authentication: Authentication
    ): HttpResponse<Map<String, Any>> {
        logger.info("Starting alignment for release {} by user {} (reviewAll={})", releaseId, authentication.name, request?.reviewAll)

        try {
            val user = userRepository.findByUsername(authentication.name)
                .orElseThrow { NoSuchElementException("User not found: ${authentication.name}") }

            val result = alignmentService.startAlignment(releaseId, user.id!!, request?.reviewAll ?: false)

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
                            "ok" to summary.assessments.okCount,
                            "change" to summary.assessments.changeCount,
                            "nogo" to summary.assessments.nogoCount
                        ),
                        "reviewToken" to summary.reviewer.reviewToken,
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
    @Secured("ADMIN", "RELEASE_MANAGER", "REQADMIN")
    fun getAlignmentFeedback(@PathVariable sessionId: Long): HttpResponse<Map<String, Any>> {
        try {
            val summaries = alignmentService.getRequirementReviewSummaries(sessionId)
            val decisions = alignmentService.getDecisionsForSession(sessionId)

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
                            "ok" to summary.assessments.okCount,
                            "change" to summary.assessments.changeCount,
                            "nogo" to summary.assessments.nogoCount
                        ),
                        "reviews" to summary.reviews.map { review ->
                            val decision = decisions[review.id]
                            mapOf(
                                "id" to review.id,
                                "reviewerName" to review.reviewer.user.username,
                                "assessment" to review.assessment.name,
                                "comment" to review.comment,
                                "createdAt" to review.createdAt?.toString(),
                                "updatedAt" to review.updatedAt?.toString(),
                                "adminDecision" to if (decision != null) mapOf(
                                    "id" to decision.id,
                                    "decision" to decision.decision.name,
                                    "comment" to decision.comment,
                                    "decidedBy" to decision.decidedByUsername,
                                    "createdAt" to decision.createdAt?.toString()
                                ) else null
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
     * PUT /api/alignment/{sessionId}/decisions - Submit admin decision on a review
     * Authorization: ADMIN or REQADMIN only
     */
    @Put("/alignment/{sessionId}/decisions")
    @Secured("ADMIN", "REQADMIN")
    fun submitReviewDecision(
        @PathVariable sessionId: Long,
        @Body request: SubmitReviewDecisionRequest,
        authentication: Authentication
    ): HttpResponse<Map<String, Any>> {
        logger.debug("Submitting review decision for session {} review {}", sessionId, request.reviewId)

        try {
            val user = userRepository.findByUsername(authentication.name)
                .orElseThrow { NoSuchElementException("User not found: ${authentication.name}") }

            val decision = alignmentService.submitReviewDecision(
                sessionId = sessionId,
                reviewId = request.reviewId,
                decision = request.decision,
                comment = request.comment,
                adminUser = user
            )

            return HttpResponse.ok(mapOf(
                "success" to true,
                "message" to "Decision submitted successfully",
                "decision" to mapOf(
                    "id" to decision.id!!,
                    "reviewId" to request.reviewId,
                    "decision" to decision.decision.name,
                    "comment" to (decision.comment ?: ""),
                    "decidedBy" to decision.decidedByUsername
                )
            ))
        } catch (e: IllegalStateException) {
            return HttpResponse.badRequest(mapOf(
                "error" to "Bad Request",
                "message" to (e.message ?: "Cannot submit decision")
            ))
        } catch (e: IllegalArgumentException) {
            return HttpResponse.badRequest(mapOf(
                "error" to "Bad Request",
                "message" to (e.message ?: "Invalid request")
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to (e.message ?: "Resource not found")
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

            // Send feedback summary emails to all reviewers after successful activation
            if (request.activateRelease) {
                try {
                    alignmentEmailService.sendFeedbackSummaryEmails(session)
                } catch (e: Exception) {
                    logger.error("Failed to send feedback summary emails for session {}: {}", sessionId, e.message)
                    // Don't fail the finalization if emails fail
                }
            }

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

    // ========== Review Export/Import Endpoints ==========

    /**
     * GET /api/alignment/review/{token}/export - Export review as Excel
     * Returns .xlsx with assessment dropdown validation for offline review.
     */
    @Get("/alignment/review/{token}/export")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun exportReviewToExcel(@PathVariable token: String): HttpResponse<*> {
        try {
            val reviewer = alignmentService.getReviewerByToken(token)
            val session = reviewer.session
            val snapshots = alignmentService.getSnapshotsForSession(session.id!!)
            val existingReviews = alignmentService.getReviewsByReviewer(reviewer.id!!)
                .associateBy { it.snapshot.id }

            val workbook = createReviewExcelWorkbook(snapshots, existingReviews)
            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)
            workbook.close()

            val inputStream = ByteArrayInputStream(outputStream.toByteArray())
            val filename = "review_${session.release.name}_v${session.release.version}.xlsx"
                .replace(" ", "_")

            return HttpResponse.ok(StreamedFile(inputStream, MediaType.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .header("Content-Disposition", "attachment; filename=\"$filename\"")
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "error" to "Not Found",
                "message" to "Invalid or expired review token"
            ))
        }
    }

    /**
     * POST /api/alignment/review/{token}/import - Import reviews from Excel
     * Parses .xlsx and submits reviews for each row with a non-empty Assessment.
     *
     * Validation layers:
     * 1. File-level: size, extension, content-type
     * 2. Structure: "Review" sheet required, all expected headers present
     * 3. Row-level: snapshot ID cross-reference, assessment enum, comment length, duplicate detection
     * 4. Limits: max 1000 data rows, max 50 reported errors
     */
    @Post("/alignment/review/{token}/import", consumes = [MediaType.MULTIPART_FORM_DATA])
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun importReviewsFromExcel(
        @PathVariable token: String,
        @Part file: CompletedFileUpload
    ): HttpResponse<Map<String, Any>> {
        logger.info("Importing reviews from Excel for token")

        try {
            // --- File-level validation ---
            val originalFilename = file.filename
            if (file.size > 10 * 1024 * 1024) {
                return HttpResponse.badRequest(mapOf(
                    "success" to false,
                    "message" to "File size exceeds 10MB limit"
                ))
            }
            if (!originalFilename.endsWith(".xlsx", ignoreCase = true)) {
                return HttpResponse.badRequest(mapOf(
                    "success" to false,
                    "message" to "Only .xlsx files are supported"
                ))
            }
            val contentType = file.contentType.map { it.toString() }.orElse("")
            if (contentType.isNotEmpty()
                && !contentType.contains("spreadsheetml.sheet", ignoreCase = true)
                && !contentType.contains("excel", ignoreCase = true)
                && !contentType.contains("octet-stream", ignoreCase = true)) {
                return HttpResponse.badRequest(mapOf(
                    "success" to false,
                    "message" to "Invalid file type. Please upload a valid .xlsx Excel file"
                ))
            }

            val reviewer = alignmentService.getReviewerByToken(token)
            val session = reviewer.session

            if (!session.isOpen()) {
                return HttpResponse.badRequest(mapOf(
                    "success" to false,
                    "message" to "Review session is closed"
                ))
            }

            // Build snapshot lookup: ID -> requirementInternalId (for cross-reference)
            val snapshots = alignmentService.getSnapshotsForSession(session.id!!)
            val snapshotById = snapshots.associateBy { it.id }
            val validSnapshotIds = snapshotById.keys.filterNotNull().toSet()

            val workbook = XSSFWorkbook(file.inputStream)

            // --- Structural validation: require "Review" sheet ---
            val sheet = workbook.getSheet("Review")
            if (sheet == null) {
                workbook.close()
                return HttpResponse.badRequest(mapOf(
                    "success" to false,
                    "message" to "Excel file must contain a sheet named 'Review'. " +
                        "Please use the exported file as a template."
                ))
            }

            val headerRow = sheet.getRow(0)
            if (headerRow == null) {
                workbook.close()
                return HttpResponse.badRequest(mapOf(
                    "success" to false,
                    "message" to "Excel file has no header row"
                ))
            }

            // Map headers case-insensitively
            val headerMap = mutableMapOf<String, Int>()
            for (i in 0 until headerRow.lastCellNum) {
                val cell = headerRow.getCell(i) ?: continue
                val cellValue = try { cell.stringCellValue.trim().lowercase() } catch (e: Exception) { continue }
                headerMap[cellValue] = i
            }

            // Validate ALL expected headers are present
            val expectedHeaders = listOf(
                "snapshot id", "requirement id", "change type", "chapter",
                "short req", "details", "previous short req", "previous details",
                "assessment", "comment"
            )
            val missingHeaders = expectedHeaders.filter { it !in headerMap }
            if (missingHeaders.isNotEmpty()) {
                workbook.close()
                return HttpResponse.badRequest(mapOf(
                    "success" to false,
                    "message" to "Missing required columns: ${missingHeaders.joinToString(", ") { "'${it}'" }}. " +
                        "Please use the exported file as a template without modifying the header row."
                ))
            }

            val snapshotIdCol = headerMap["snapshot id"]!!
            val requirementIdCol = headerMap["requirement id"]!!
            val assessmentCol = headerMap["assessment"]!!
            val commentCol = headerMap["comment"]!!

            // --- Row count limit ---
            val maxRows = 1000
            val dataRowCount = sheet.lastRowNum // row 0 is header
            if (dataRowCount > maxRows) {
                workbook.close()
                return HttpResponse.badRequest(mapOf(
                    "success" to false,
                    "message" to "File contains $dataRowCount data rows, exceeding the limit of $maxRows"
                ))
            }

            var imported = 0
            var skipped = 0
            val errors = mutableListOf<String>()
            val maxErrors = 50
            val seenSnapshotIds = mutableSetOf<Long>()
            val maxCommentLength = 2000

            for (rowNum in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowNum) ?: continue

                // Read assessment value safely (handle non-string cells)
                val assessmentValue = try {
                    val cell = row.getCell(assessmentCol)
                    cell?.stringCellValue?.trim() ?: ""
                } catch (e: Exception) {
                    try {
                        row.getCell(assessmentCol)?.toString()?.trim() ?: ""
                    } catch (e2: Exception) { "" }
                }
                if (assessmentValue.isEmpty()) {
                    skipped++
                    continue
                }

                // Stop collecting error details after limit (still process rows)
                val canAddError = errors.size < maxErrors

                // Parse Snapshot ID (numeric or string)
                val snapshotId = try {
                    row.getCell(snapshotIdCol)?.numericCellValue?.toLong()
                } catch (e: Exception) {
                    try {
                        row.getCell(snapshotIdCol)?.stringCellValue?.trim()?.toLongOrNull()
                    } catch (e2: Exception) { null }
                }

                if (snapshotId == null) {
                    if (canAddError) errors.add("Row ${rowNum + 1}: Invalid or missing Snapshot ID")
                    continue
                }

                // Validate snapshot belongs to session
                if (snapshotId !in validSnapshotIds) {
                    if (canAddError) errors.add("Row ${rowNum + 1}: Snapshot ID $snapshotId does not belong to this review session")
                    continue
                }

                // Cross-reference: verify Requirement ID matches the snapshot
                val expectedReqId = snapshotById[snapshotId]?.requirementInternalId
                val rowReqId = try {
                    row.getCell(requirementIdCol)?.stringCellValue?.trim()
                } catch (e: Exception) {
                    try { row.getCell(requirementIdCol)?.toString()?.trim() } catch (e2: Exception) { null }
                }
                if (rowReqId != null && rowReqId.isNotEmpty() && rowReqId != expectedReqId) {
                    if (canAddError) errors.add("Row ${rowNum + 1}: Requirement ID '$rowReqId' does not match Snapshot ID $snapshotId (expected '$expectedReqId'). Row data may have been modified.")
                    continue
                }

                // Duplicate detection within file
                if (snapshotId in seenSnapshotIds) {
                    if (canAddError) errors.add("Row ${rowNum + 1}: Duplicate Snapshot ID $snapshotId (already processed earlier in file)")
                    continue
                }
                seenSnapshotIds.add(snapshotId)

                // Validate assessment enum strictly
                val assessment = try {
                    ReviewAssessment.valueOf(assessmentValue.uppercase())
                } catch (e: IllegalArgumentException) {
                    if (canAddError) errors.add("Row ${rowNum + 1}: Invalid assessment '$assessmentValue' (must be OK, CHANGE, or NOGO)")
                    continue
                }

                // Read and validate comment length
                val rawComment = try {
                    row.getCell(commentCol)?.stringCellValue?.trim()
                } catch (e: Exception) {
                    try { row.getCell(commentCol)?.toString()?.trim() } catch (e2: Exception) { null }
                }
                val comment = if (rawComment.isNullOrEmpty()) null else rawComment
                if (comment != null && comment.length > maxCommentLength) {
                    if (canAddError) errors.add("Row ${rowNum + 1}: Comment exceeds $maxCommentLength character limit (${comment.length} chars)")
                    continue
                }

                try {
                    alignmentService.submitReviewByToken(token, snapshotId, assessment, comment)
                    imported++
                } catch (e: Exception) {
                    if (canAddError) errors.add("Row ${rowNum + 1}: ${e.message}")
                }
            }

            workbook.close()

            if (errors.size >= maxErrors) {
                errors.add("... (showing first $maxErrors errors only)")
            }

            return HttpResponse.ok(mapOf(
                "success" to true,
                "imported" to imported,
                "skipped" to skipped,
                "errors" to errors,
                "message" to "Imported $imported review(s), skipped $skipped, ${errors.size} error(s)"
            ))
        } catch (e: NoSuchElementException) {
            return HttpResponse.notFound(mapOf(
                "success" to false,
                "message" to "Invalid or expired review token"
            ))
        } catch (e: Exception) {
            logger.error("Failed to import reviews from Excel", e)
            return HttpResponse.badRequest(mapOf(
                "success" to false,
                "message" to "Failed to parse Excel file: ${e.message}"
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
            "reviewScope" to session.reviewScope.name,
            "startedAt" to session.startedAt?.toString(),
            "completedAt" to session.completedAt?.toString(),
            "completionNotes" to session.completionNotes
        )
    }

    private fun createReviewExcelWorkbook(
        snapshots: List<com.secman.domain.AlignmentSnapshot>,
        existingReviews: Map<Long?, com.secman.domain.RequirementReview>
    ): XSSFWorkbook {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Review")

        val headers = arrayOf(
            "Snapshot ID", "Requirement ID", "Change Type", "Chapter",
            "Short Req", "Details", "Previous Short Req", "Previous Details",
            "Assessment", "Comment"
        )

        // Header style
        val headerStyle = workbook.createCellStyle()
        val headerFont = workbook.createFont()
        headerFont.bold = true
        headerStyle.setFont(headerFont)

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }

        // Data rows
        snapshots.forEachIndexed { index, snapshot ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(snapshot.id?.toDouble() ?: 0.0)
            row.createCell(1).setCellValue(snapshot.requirementInternalId)
            row.createCell(2).setCellValue(snapshot.changeType.name)
            row.createCell(3).setCellValue(snapshot.chapter ?: "")
            row.createCell(4).setCellValue(snapshot.shortreq)
            row.createCell(5).setCellValue(snapshot.details ?: "")
            row.createCell(6).setCellValue(snapshot.previousShortreq ?: "")
            row.createCell(7).setCellValue(snapshot.previousDetails ?: "")

            // Pre-populate from existing reviews
            val review = existingReviews[snapshot.id]
            row.createCell(8).setCellValue(review?.assessment?.name ?: "")
            row.createCell(9).setCellValue(review?.comment ?: "")
        }

        // Add data validation dropdown for Assessment column (column 8)
        if (snapshots.isNotEmpty()) {
            val validationHelper = sheet.dataValidationHelper
            val constraint = validationHelper.createExplicitListConstraint(arrayOf("OK", "CHANGE", "NOGO"))
            val addressList = CellRangeAddressList(1, snapshots.size, 8, 8)
            val validation = validationHelper.createValidation(constraint, addressList)
            validation.showErrorBox = true
            validation.createErrorBox("Invalid Assessment", "Please select OK, CHANGE, or NOGO")
            sheet.addValidationData(validation)
        }

        // Auto-size columns
        for (i in headers.indices) {
            sheet.autoSizeColumn(i)
            if (sheet.getColumnWidth(i) < 3000) {
                sheet.setColumnWidth(i, 3000)
            }
        }

        return workbook
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
                "ok" to status.assessmentSummary.okCount,
                "change" to status.assessmentSummary.changeCount,
                "nogo" to status.assessmentSummary.nogoCount
            )
        )
    }
}

// ========== Request DTOs ==========

@Serdeable
data class StartAlignmentRequest(
    val reviewAll: Boolean = false
)

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

@Serdeable
data class SubmitReviewDecisionRequest(
    val reviewId: Long,
    val decision: Decision,
    val comment: String? = null
)
