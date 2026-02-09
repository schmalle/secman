package com.secman.service

import com.secman.domain.*
import com.secman.domain.AlignmentReviewer.ReviewerStatus
import com.secman.domain.AlignmentSession.AlignmentStatus
import com.secman.domain.AlignmentSnapshot.ChangeType
import com.secman.domain.Release.ReleaseStatus
import com.secman.domain.ReviewDecision.Decision
import com.secman.domain.RequirementReview.ReviewAssessment
import com.secman.repository.*
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Service for managing the Requirements Alignment Process.
 * Feature: 068-requirements-alignment-process
 *
 * Handles:
 * - Starting alignment sessions for PREPARATION releases
 * - Detecting changed requirements between releases
 * - Managing reviewers and their feedback
 * - Finalizing alignment and activating releases
 */
@Singleton
open class AlignmentService(
    private val alignmentSessionRepository: AlignmentSessionRepository,
    private val alignmentReviewerRepository: AlignmentReviewerRepository,
    private val alignmentSnapshotRepository: AlignmentSnapshotRepository,
    private val requirementReviewRepository: RequirementReviewRepository,
    private val reviewDecisionRepository: ReviewDecisionRepository,
    private val releaseRepository: ReleaseRepository,
    private val requirementSnapshotRepository: RequirementSnapshotRepository,
    private val requirementRepository: RequirementRepository,
    private val userRepository: UserRepository,
    private val releaseService: ReleaseService
) {
    private val logger = LoggerFactory.getLogger(AlignmentService::class.java)

    // ========== Data Transfer Objects ==========

    data class AlignmentStartResult(
        val session: AlignmentSession,
        val reviewers: List<AlignmentReviewer>,
        val changedRequirements: Int,
        val addedCount: Int,
        val modifiedCount: Int,
        val deletedCount: Int
    )

    data class AlignmentStatusResult(
        val session: AlignmentSession,
        val totalReviewers: Int,
        val completedReviewers: Int,
        val inProgressReviewers: Int,
        val pendingReviewers: Int,
        val totalRequirements: Int,
        val reviewedRequirements: Int,
        val assessmentSummary: AssessmentSummary
    )

    data class AssessmentSummary(
        val okCount: Int,
        val changeCount: Int,
        val nogoCount: Int
    )

    data class ReviewerSummary(
        val reviewer: AlignmentReviewer,
        val reviewedCount: Int,
        val totalCount: Int,
        val assessments: AssessmentSummary
    )

    data class RequirementReviewSummary(
        val snapshot: AlignmentSnapshot,
        val reviewCount: Int,
        val assessments: AssessmentSummary,
        val reviews: List<RequirementReview>
    )

    // ========== Alignment Session Management ==========

    /**
     * Start an alignment process for a PREPARATION release.
     *
     * @param releaseId The release to start alignment for
     * @param initiatorId The user initiating the alignment
     * @return AlignmentStartResult with session details
     * @throws IllegalArgumentException if release is not in PREPARATION status
     * @throws IllegalStateException if no REQ-role users exist
     */
    @Transactional
    open fun startAlignment(releaseId: Long, initiatorId: Long, reviewAll: Boolean = false): AlignmentStartResult {
        logger.info("Starting alignment for release {} by user {} (reviewAll={})", releaseId, initiatorId, reviewAll)

        val release = releaseRepository.findById(releaseId)
            .orElseThrow { NoSuchElementException("Release not found: $releaseId") }

        if (release.status != ReleaseStatus.PREPARATION) {
            throw IllegalArgumentException("Can only start alignment for PREPARATION releases. Current status: ${release.status}")
        }

        if (alignmentSessionRepository.hasOpenSession(releaseId)) {
            throw IllegalStateException("Release already has an open alignment session")
        }

        val initiator = userRepository.findById(initiatorId)
            .orElseThrow { NoSuchElementException("User not found: $initiatorId") }

        // Find users with REQ role
        val reviewerUsers = userRepository.findByRolesContaining(User.Role.REQ)
        if (reviewerUsers.isEmpty()) {
            throw IllegalStateException("No users with REQ role found. Cannot start alignment without reviewers.")
        }

        // Find baseline release (last ACTIVE)
        val baselineRelease = releaseRepository.findByStatus(ReleaseStatus.ACTIVE).firstOrNull()

        // Create alignment session — always record the actual baseline for audit
        val session = AlignmentSession(
            release = release,
            initiatedBy = initiator,
            baselineRelease = baselineRelease,
            reviewScope = if (reviewAll) AlignmentSession.ReviewScope.ALL else AlignmentSession.ReviewScope.CHANGED
        )
        alignmentSessionRepository.save(session)

        // When reviewAll=true, pass null as effective baseline so detectChangedRequirements
        // treats all current requirements as ADDED (existing behavior for null baseline)
        val effectiveBaseline = if (reviewAll) null else baselineRelease

        // Detect changed requirements
        val (snapshots, addedCount, modifiedCount, deletedCount) = detectChangedRequirements(session, release, effectiveBaseline)
        session.changedRequirementsCount = snapshots.size
        alignmentSessionRepository.update(session)

        // Create reviewers
        val reviewers = reviewerUsers.map { user ->
            val reviewer = AlignmentReviewer(
                session = session,
                user = user
            )
            alignmentReviewerRepository.save(reviewer)
        }

        // Update release status to ALIGNMENT
        release.status = ReleaseStatus.ALIGNMENT
        releaseRepository.update(release)

        logger.info("Alignment started for release {}: {} changed requirements, {} reviewers",
            releaseId, snapshots.size, reviewers.size)

        return AlignmentStartResult(
            session = session,
            reviewers = reviewers,
            changedRequirements = snapshots.size,
            addedCount = addedCount,
            modifiedCount = modifiedCount,
            deletedCount = deletedCount
        )
    }

    /**
     * Detect requirements that changed between baseline and current (live) requirements.
     *
     * Compares LIVE requirements from the database against baseline snapshots
     * to detect additions, modifications, and deletions.
     */
    private fun detectChangedRequirements(
        session: AlignmentSession,
        currentRelease: Release,
        baselineRelease: Release?
    ): Quadruple<List<AlignmentSnapshot>, Int, Int, Int> {
        // Get LIVE current requirements instead of frozen PREPARATION snapshots
        val currentRequirements = requirementRepository.findCurrentRequirements()
            .associateBy { it.internalId }

        val baselineSnapshots = if (baselineRelease != null) {
            requirementSnapshotRepository.findByReleaseId(baselineRelease.id!!)
                .associateBy { it.internalId }
        } else {
            emptyMap()
        }

        val alignmentSnapshots = mutableListOf<AlignmentSnapshot>()
        var addedCount = 0
        var modifiedCount = 0
        var deletedCount = 0

        // Find ADDED and MODIFIED
        for ((internalId, currentReq) in currentRequirements) {
            val baselineSnapshot = baselineSnapshots[internalId]

            if (baselineSnapshot == null) {
                // ADDED - new requirement not in baseline
                alignmentSnapshots.add(AlignmentSnapshot.forAddedFromRequirement(session, currentReq))
                addedCount++
            } else if (hasChangesFromRequirement(baselineSnapshot, currentReq)) {
                // MODIFIED - requirement changed since baseline
                alignmentSnapshots.add(AlignmentSnapshot.forModifiedFromRequirement(session, baselineSnapshot, currentReq))
                modifiedCount++
            }
        }

        // Find DELETED - requirements in baseline but not in current
        for ((internalId, baselineSnapshot) in baselineSnapshots) {
            if (!currentRequirements.containsKey(internalId)) {
                alignmentSnapshots.add(AlignmentSnapshot.forDeleted(session, baselineSnapshot))
                deletedCount++
            }
        }

        // Save all snapshots
        alignmentSnapshotRepository.saveAll(alignmentSnapshots)

        return Quadruple(alignmentSnapshots, addedCount, modifiedCount, deletedCount)
    }

    /**
     * Check if requirement content has changed between snapshots.
     */
    private fun hasChanges(baseline: RequirementSnapshot, current: RequirementSnapshot): Boolean {
        return baseline.shortreq != current.shortreq ||
               baseline.details != current.details ||
               baseline.chapter != current.chapter ||
               baseline.revision != current.revision ||
               baseline.example != current.example ||
               baseline.motivation != current.motivation ||
               baseline.usecase != current.usecase ||
               baseline.norm != current.norm
    }

    /**
     * Check if a live requirement has changed compared to a baseline snapshot.
     */
    private fun hasChangesFromRequirement(baseline: RequirementSnapshot, current: Requirement): Boolean {
        return baseline.shortreq != current.shortreq ||
               baseline.details != current.details ||
               baseline.chapter != current.chapter ||
               baseline.revision != current.versionNumber ||
               baseline.example != current.example ||
               baseline.motivation != current.motivation ||
               baseline.usecase != current.usecase ||
               baseline.norm != current.norm
    }

    // ========== Review Submission ==========

    /**
     * Submit or update a review for a requirement.
     *
     * @param reviewerId The reviewer's AlignmentReviewer ID
     * @param snapshotId The AlignmentSnapshot ID being reviewed
     * @param assessment The assessment (OK, CHANGE, NOGO)
     * @param comment Optional comment text
     * @return The saved RequirementReview
     */
    @Transactional
    open fun submitReview(
        reviewerId: Long,
        snapshotId: Long,
        assessment: ReviewAssessment,
        comment: String?
    ): RequirementReview {
        val reviewer = alignmentReviewerRepository.findById(reviewerId)
            .orElseThrow { NoSuchElementException("Reviewer not found: $reviewerId") }

        val session = reviewer.session
        if (!session.isOpen()) {
            throw IllegalStateException("Cannot submit review for closed alignment session")
        }

        val snapshot = alignmentSnapshotRepository.findById(snapshotId)
            .orElseThrow { NoSuchElementException("Snapshot not found: $snapshotId") }

        if (snapshot.session.id != session.id) {
            throw IllegalArgumentException("Snapshot does not belong to this alignment session")
        }

        // Mark reviewer as in progress if not already
        reviewer.markStarted()
        alignmentReviewerRepository.update(reviewer)

        // Find existing review or create new
        val existingReview = requirementReviewRepository.findByReviewer_IdAndSnapshot_Id(reviewerId, snapshotId)

        val review = if (existingReview.isPresent) {
            val existing = existingReview.get()
            existing.assessment = assessment
            existing.comment = comment
            requirementReviewRepository.update(existing)
        } else {
            val newReview = RequirementReview(
                session = session,
                reviewer = reviewer,
                snapshot = snapshot,
                assessment = assessment,
                comment = comment
            )
            requirementReviewRepository.save(newReview)
        }

        // Update reviewer's reviewed count
        reviewer.reviewedCount = requirementReviewRepository.countByReviewer_Id(reviewerId).toInt()
        alignmentReviewerRepository.update(reviewer)

        logger.debug("Review submitted: reviewer={}, snapshot={}, assessment={}", reviewerId, snapshotId, assessment)

        return review
    }

    /**
     * Submit a review using the reviewer's token (for email-based access).
     */
    @Transactional
    open fun submitReviewByToken(
        reviewToken: String,
        snapshotId: Long,
        assessment: ReviewAssessment,
        comment: String?
    ): RequirementReview {
        val reviewer = alignmentReviewerRepository.findByReviewToken(reviewToken)
            .orElseThrow { NoSuchElementException("Invalid review token") }

        return submitReview(reviewer.id!!, snapshotId, assessment, comment)
    }

    /**
     * Mark a reviewer's review as complete.
     */
    @Transactional
    open fun completeReview(reviewerId: Long): AlignmentReviewer {
        val reviewer = alignmentReviewerRepository.findById(reviewerId)
            .orElseThrow { NoSuchElementException("Reviewer not found: $reviewerId") }

        if (!reviewer.session.isOpen()) {
            throw IllegalStateException("Cannot complete review for closed alignment session")
        }

        reviewer.markCompleted()
        alignmentReviewerRepository.update(reviewer)

        logger.info("Reviewer {} completed review for session {}", reviewerId, reviewer.session.id)

        return reviewer
    }

    // ========== Status and Reporting ==========

    /**
     * Get alignment status including reviewer progress and assessment summary.
     */
    @Transactional
    open fun getAlignmentStatus(sessionId: Long): AlignmentStatusResult {
        val session = alignmentSessionRepository.findById(sessionId)
            .orElseThrow { NoSuchElementException("Alignment session not found: $sessionId") }

        val reviewers = alignmentReviewerRepository.findBySession_Id(sessionId)
        val totalReviewers = reviewers.size
        val completedReviewers = reviewers.count { it.status == ReviewerStatus.COMPLETED }
        val inProgressReviewers = reviewers.count { it.status == ReviewerStatus.IN_PROGRESS }
        val pendingReviewers = reviewers.count { it.status == ReviewerStatus.PENDING }

        val totalRequirements = alignmentSnapshotRepository.countBySession_Id(sessionId).toInt()
        val reviewedRequirements = if (reviewers.isNotEmpty()) {
            reviewers.maxOfOrNull { it.reviewedCount } ?: 0
        } else 0

        val okCount = requirementReviewRepository.countBySession_IdAndAssessment(sessionId, ReviewAssessment.OK).toInt()
        val changeCount = requirementReviewRepository.countBySession_IdAndAssessment(sessionId, ReviewAssessment.CHANGE).toInt()
        val nogoCount = requirementReviewRepository.countBySession_IdAndAssessment(sessionId, ReviewAssessment.NOGO).toInt()

        return AlignmentStatusResult(
            session = session,
            totalReviewers = totalReviewers,
            completedReviewers = completedReviewers,
            inProgressReviewers = inProgressReviewers,
            pendingReviewers = pendingReviewers,
            totalRequirements = totalRequirements,
            reviewedRequirements = reviewedRequirements,
            assessmentSummary = AssessmentSummary(okCount, changeCount, nogoCount)
        )
    }

    /**
     * Get alignment status for a release (finds open session).
     */
    @Transactional
    open fun getAlignmentStatusByReleaseId(releaseId: Long): AlignmentStatusResult? {
        val session = alignmentSessionRepository.findOpenSessionByReleaseId(releaseId).orElse(null)
            ?: return null
        return getAlignmentStatus(session.id!!)
    }

    /**
     * Get detailed reviewer summaries for a session.
     */
    @Transactional
    open fun getReviewerSummaries(sessionId: Long): List<ReviewerSummary> {
        val session = alignmentSessionRepository.findById(sessionId)
            .orElseThrow { NoSuchElementException("Alignment session not found: $sessionId") }

        val reviewers = alignmentReviewerRepository.findBySession_Id(sessionId)
        val totalRequirements = alignmentSnapshotRepository.countBySession_Id(sessionId).toInt()

        return reviewers.map { reviewer ->
            val reviews = requirementReviewRepository.findByReviewer_Id(reviewer.id!!)
            val okCount = reviews.count { it.assessment == ReviewAssessment.OK }
            val changeCount = reviews.count { it.assessment == ReviewAssessment.CHANGE }
            val nogoCount = reviews.count { it.assessment == ReviewAssessment.NOGO }

            ReviewerSummary(
                reviewer = reviewer,
                reviewedCount = reviews.size,
                totalCount = totalRequirements,
                assessments = AssessmentSummary(okCount, changeCount, nogoCount)
            )
        }
    }

    /**
     * Get requirement review summaries for a session.
     */
    @Transactional
    open fun getRequirementReviewSummaries(sessionId: Long): List<RequirementReviewSummary> {
        val snapshots = alignmentSnapshotRepository.findBySession_Id(sessionId)

        return snapshots.map { snapshot ->
            val reviews = requirementReviewRepository.findBySnapshot_Id(snapshot.id!!)
            val okCount = reviews.count { it.assessment == ReviewAssessment.OK }
            val changeCount = reviews.count { it.assessment == ReviewAssessment.CHANGE }
            val nogoCount = reviews.count { it.assessment == ReviewAssessment.NOGO }

            RequirementReviewSummary(
                snapshot = snapshot,
                reviewCount = reviews.size,
                assessments = AssessmentSummary(okCount, changeCount, nogoCount),
                reviews = reviews
            )
        }
    }

    // ========== Session Finalization ==========

    /**
     * Finalize the alignment and optionally activate the release.
     *
     * @param sessionId The alignment session to finalize
     * @param activateRelease Whether to activate the release
     * @param notes Optional completion notes
     * @return The completed AlignmentSession
     */
    @Transactional
    open fun finalizeAlignment(sessionId: Long, activateRelease: Boolean, notes: String?): AlignmentSession {
        val session = alignmentSessionRepository.findById(sessionId)
            .orElseThrow { NoSuchElementException("Alignment session not found: $sessionId") }

        if (!session.isOpen()) {
            throw IllegalStateException("Cannot finalize a session that is not open")
        }

        session.complete(notes)
        alignmentSessionRepository.update(session)

        val release = session.release

        if (activateRelease) {
            // Activate the release using the existing ReleaseService
            releaseService.updateReleaseStatus(release.id!!, ReleaseStatus.ACTIVE)
            logger.info("Release {} activated after alignment finalization", release.id)
        } else {
            // Return to PREPARATION status
            release.status = ReleaseStatus.PREPARATION
            releaseRepository.update(release)
            logger.info("Release {} returned to PREPARATION after alignment finalization", release.id)
        }

        logger.info("Alignment session {} finalized (activate={})", sessionId, activateRelease)

        return session
    }

    /**
     * Cancel an in-progress alignment session.
     */
    @Transactional
    open fun cancelAlignment(sessionId: Long, notes: String?): AlignmentSession {
        val session = alignmentSessionRepository.findById(sessionId)
            .orElseThrow { NoSuchElementException("Alignment session not found: $sessionId") }

        if (!session.isOpen()) {
            throw IllegalStateException("Cannot cancel a session that is not open")
        }

        session.cancel(notes)
        alignmentSessionRepository.update(session)

        // Return release to PREPARATION status
        val release = session.release
        release.status = ReleaseStatus.PREPARATION
        releaseRepository.update(release)

        logger.info("Alignment session {} cancelled", sessionId)

        return session
    }

    // ========== Reviewer Access ==========

    /**
     * Get reviewer by token (for email-based access).
     */
    @Transactional
    open fun getReviewerByToken(reviewToken: String): AlignmentReviewer {
        val reviewer = alignmentReviewerRepository.findByReviewToken(reviewToken)
            .orElseThrow { NoSuchElementException("Invalid review token") }

        // Mark as started if first access
        if (reviewer.status == ReviewerStatus.PENDING) {
            reviewer.markStarted()
            alignmentReviewerRepository.update(reviewer)
        }

        return reviewer
    }

    /**
     * Get reviews by a specific reviewer.
     */
    @Transactional
    open fun getReviewsByReviewer(reviewerId: Long): List<RequirementReview> {
        return requirementReviewRepository.findByReviewer_Id(reviewerId)
    }

    /**
     * Get snapshots for a session (requirements to review).
     */
    @Transactional
    open fun getSnapshotsForSession(sessionId: Long): List<AlignmentSnapshot> {
        return alignmentSnapshotRepository.findBySession_Id(sessionId)
    }

    /**
     * Check if there are any changes to review for a release.
     * Used to determine if "Start Alignment" should be enabled.
     *
     * Compares LIVE requirements against the ACTIVE release's snapshots to detect
     * changes made after the PREPARATION release was created.
     */
    @Transactional
    open fun hasChangesToReview(releaseId: Long): Boolean {
        val release = releaseRepository.findById(releaseId)
            .orElseThrow { NoSuchElementException("Release not found: $releaseId") }

        val baselineRelease = releaseRepository.findByStatus(ReleaseStatus.ACTIVE).firstOrNull()

        // Get LIVE current requirements instead of frozen DRAFT snapshots
        val currentRequirements = requirementRepository.findCurrentRequirements()
            .associateBy { it.internalId }

        if (baselineRelease == null) {
            // No baseline = all requirements are new
            return currentRequirements.isNotEmpty()
        }

        val baselineSnapshots = requirementSnapshotRepository.findByReleaseId(baselineRelease.id!!)
            .associateBy { it.internalId }

        // Check for any added or modified requirements
        for ((internalId, currentReq) in currentRequirements) {
            val baselineSnapshot = baselineSnapshots[internalId]
            if (baselineSnapshot == null || hasChangesFromRequirement(baselineSnapshot, currentReq)) {
                return true
            }
        }

        // Check for deleted requirements
        for (internalId in baselineSnapshots.keys) {
            if (!currentRequirements.containsKey(internalId)) {
                return true
            }
        }

        return false
    }

    /**
     * Mark reviewer as notified (called after sending email).
     */
    @Transactional
    open fun markReviewerNotified(reviewerId: Long): AlignmentReviewer {
        val reviewer = alignmentReviewerRepository.findById(reviewerId)
            .orElseThrow { NoSuchElementException("Reviewer not found: $reviewerId") }
        reviewer.markNotified()
        return alignmentReviewerRepository.update(reviewer)
    }

    /**
     * Mark reviewer as reminded (called after sending reminder email).
     */
    @Transactional
    open fun markReviewerReminded(reviewerId: Long): AlignmentReviewer {
        val reviewer = alignmentReviewerRepository.findById(reviewerId)
            .orElseThrow { NoSuchElementException("Reviewer not found: $reviewerId") }
        reviewer.markReminded()
        return alignmentReviewerRepository.update(reviewer)
    }

    /**
     * Get incomplete reviewers for sending reminders.
     */
    @Transactional
    open fun getIncompleteReviewers(sessionId: Long): List<AlignmentReviewer> {
        return alignmentReviewerRepository.findIncompleteReviewers(sessionId)
    }

    // ========== Admin Review Decisions ==========

    /**
     * Submit or update an admin decision on a reviewer's assessment.
     * Uses upsert pattern: creates new decision or updates existing one.
     *
     * @param sessionId The alignment session ID
     * @param reviewId The requirement review ID being decided on
     * @param decision ACCEPTED or REJECTED
     * @param comment Optional admin comment
     * @param adminUser The admin/REQADMIN user making the decision
     * @return The saved ReviewDecision
     */
    @Transactional
    open fun submitReviewDecision(
        sessionId: Long,
        reviewId: Long,
        decision: Decision,
        comment: String?,
        adminUser: User
    ): ReviewDecision {
        val session = alignmentSessionRepository.findById(sessionId)
            .orElseThrow { NoSuchElementException("Alignment session not found: $sessionId") }

        if (!session.isOpen()) {
            throw IllegalStateException("Cannot submit decisions for closed alignment session")
        }

        val review = requirementReviewRepository.findById(reviewId)
            .orElseThrow { NoSuchElementException("Review not found: $reviewId") }

        if (review.session.id != sessionId) {
            throw IllegalArgumentException("Review does not belong to this alignment session")
        }

        // Upsert: find existing decision or create new
        val existingDecision = reviewDecisionRepository.findByReview_Id(reviewId)

        val reviewDecision = if (existingDecision.isPresent) {
            val existing = existingDecision.get()
            existing.decision = decision
            existing.comment = comment
            existing.decidedBy = adminUser
            existing.decidedByUsername = adminUser.username
            reviewDecisionRepository.update(existing)
        } else {
            val newDecision = ReviewDecision(
                review = review,
                session = session,
                decision = decision,
                comment = comment,
                decidedBy = adminUser,
                decidedByUsername = adminUser.username
            )
            reviewDecisionRepository.save(newDecision)
        }

        logger.debug("Review decision submitted: reviewId={}, decision={}, by={}", reviewId, decision, adminUser.username)

        return reviewDecision
    }

    /**
     * Get all admin decisions for a session, keyed by review ID.
     *
     * @param sessionId The alignment session ID
     * @return Map of review ID to ReviewDecision
     */
    @Transactional
    open fun getDecisionsForSession(sessionId: Long): Map<Long, ReviewDecision> {
        return reviewDecisionRepository.findBySession_Id(sessionId)
            .associateBy { it.review.id!! }
    }

    // ========== Helper Classes ==========

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
